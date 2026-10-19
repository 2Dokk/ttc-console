package com.gsc.command;

import com.gsc.clock.SimulationClock;
import com.gsc.config.GscProperties;
import com.gsc.events.OpsEventLog;
import com.gsc.link.Uplink;
import com.gsc.protocol.TcFrame;
import com.gsc.tracking.LinkStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ground-side frame operation procedure, a simplified CCSDS COP-1 FOP (sliding window, go-back-N):
 * <ul>
 *   <li>Out of contact nothing is transmitted; commands wait as PENDING.</li>
 *   <li>In contact, up to {@code windowSize} frames may be unacknowledged at once. Sequence numbers
 *       are assigned on first transmission in issue order, so expired/cancelled commands leave no gap.</li>
 *   <li>If the oldest outstanding frame is not acknowledged within {@code ackTimeout}, every
 *       outstanding frame is retransmitted in order (the spacecraft discards what it already has).</li>
 * </ul>
 * Retransmission timers use wall time: they model the RF round trip, which the simulation speed-up
 * does not shorten.
 */
@Component
public class CommandDispatcher {

    private final CommandRepository repo;
    private final CommandService commands;
    private final Uplink uplink;
    private final LinkStatus link;
    private final SimulationClock missionClock;
    private final Clock wallClock;
    private final OpsEventLog events;
    private final long satelliteId;
    private final int windowSize;
    private final Duration ackTimeout;
    private final int transmissionLimit;

    /** seq -> wall time of its latest transmission. */
    private final Map<Long, Instant> lastTransmission = new ConcurrentHashMap<>();
    /** 위성이 번호 공백을 보고했을 때 세워지는 깃발. 타이머를 기다리지 않고 재전송한다. */
    private final AtomicBoolean retransmitRequested = new AtomicBoolean();
    /** seq -> 운용자가 송신을 재개한 시점의 누적 송신 횟수. 한도는 그 이후부터 다시 센다. */
    private final Map<Long, Integer> attemptsAtResume = new ConcurrentHashMap<>();
    /** V(S): the sequence number the next new frame will carry. */
    private long nextSeq = 1;
    private boolean wasVisible;
    private volatile boolean suspended;

    public CommandDispatcher(CommandRepository repo, CommandService commands, Uplink uplink, LinkStatus link,
                             SimulationClock missionClock, Clock wallClock, OpsEventLog events,
                             GscProperties props) {
        this.repo = repo;
        this.commands = commands;
        this.uplink = uplink;
        this.link = link;
        this.missionClock = missionClock;
        this.wallClock = wallClock;
        this.events = events;
        this.satelliteId = props.satelliteId();
        this.windowSize = props.fop().windowSize();
        this.ackTimeout = Duration.ofMillis(props.fop().ackTimeoutMs());
        this.transmissionLimit = props.fop().transmissionLimit();
    }

    /** Called once at startup, after recovery, before the first tick. */
    public synchronized void initialize(long firstSeq) {
        nextSeq = firstSeq;
        lastTransmission.clear();
        attemptsAtResume.clear();
        retransmitRequested.set(false);
        suspended = false;
    }

    /** 위성의 재전송 요청(CLCW). 다음 tick에서 타이머와 무관하게 재전송한다. */
    @EventListener
    public void onRetransmitRequested(RetransmitRequested request) {
        retransmitRequested.set(true);
    }

    /**
     * 재전송 한도를 넘겨 송신을 멈춘 상태인지. 링크가 사실상 끊겼는데도 조용히 재시도만 반복하는 것을
     * 막기 위한 것으로, 운용자가 확인하고 {@link #resume()} 해야 다시 송신한다.
     */
    public boolean isSuspended() {
        return suspended;
    }

    public synchronized void resume() {
        if (!suspended) {
            return;
        }
        // 재전송 한도는 재개 시점부터 다시 센다. 누적 횟수로 판단하면 재개하자마자 다시 중지된다.
        attemptsAtResume.clear();
        for (Command command : repo.findOutstanding(satelliteId)) {
            attemptsAtResume.put(command.seq(), command.attempts());
        }
        suspended = false;
        lastTransmission.clear();
        events.info("FOP", "운용자 확인: 명령 송신을 재개합니다");
    }

    @Scheduled(fixedDelay = 200)
    public synchronized void tick() {
        Instant missionNow = missionClock.now();
        for (Command expired : repo.expire(missionNow)) {
            commands.publish(expired);
            events.warn("CMD", String.format("#%d %s 송신 전에 유효 기한 만료",
                    expired.id(), CommandService.describe(expired)));
        }

        boolean visible = link.isVisible();
        boolean acquired = visible && !wasVisible;
        wasVisible = visible;
        if (!visible || suspended) {
            return;
        }

        Instant wallNow = wallClock.instant();
        List<Command> outstanding = repo.findOutstanding(satelliteId);
        Set<Long> outstandingSeqs = outstanding.stream().map(Command::seq).collect(Collectors.toSet());
        lastTransmission.keySet().retainAll(outstandingSeqs);

        if (acquired) {
            List<Command> queued = repo.findPending(satelliteId, Integer.MAX_VALUE);
            if (!queued.isEmpty() || !outstanding.isEmpty()) {
                events.info("FOP", String.format("AOS: 대기 %d건, 응답 대기 %d건, 업링크 시작",
                        queued.size(), outstanding.size()));
            }
        }

        boolean requested = retransmitRequested.getAndSet(false) && !outstanding.isEmpty();
        boolean timedOut = !outstanding.isEmpty() && timedOut(outstanding.get(0).seq(), wallNow);
        if (requested || timedOut) {
            long from = outstanding.get(0).seq();
            long to = outstanding.get(outstanding.size() - 1).seq();
            Command oldest = outstanding.get(0);
            int attemptsSinceResume = oldest.attempts() - attemptsAtResume.getOrDefault(oldest.seq(), 0);
            if (attemptsSinceResume >= transmissionLimit) {
                suspended = true;
                events.warn("FOP", String.format(
                        "seq %d 를 %d회 보냈지만 응답이 없어 명령 송신을 중지했습니다. 링크를 확인한 뒤 재개하세요",
                        from, attemptsSinceResume));
                return;
            }
            if (requested) {
                events.warn("FOP", String.format("위성이 번호 공백을 보고: 타이머를 기다리지 않고 seq %d..%d 즉시 재전송",
                        from, to));
            } else if (!acquired) {
                events.warn("FOP", String.format("seq %d 응답 시간 초과, Go-Back-N: seq %d..%d 재전송",
                        from, from, to));
            }
            for (Command command : outstanding) {
                transmit(command, command.seq(), missionNow, wallNow);
            }
        }

        int free = windowSize - outstanding.size();
        if (free > 0) {
            for (Command command : repo.findPending(satelliteId, free)) {
                if (transmit(command, nextSeq, missionNow, wallNow)) {
                    nextSeq++;
                }
            }
        }
    }

    private boolean timedOut(long seq, Instant wallNow) {
        Instant sent = lastTransmission.get(seq);
        return sent == null || Duration.between(sent, wallNow).compareTo(ackTimeout) >= 0;
    }

    private boolean transmit(Command command, long seq, Instant missionNow, Instant wallNow) {
        return repo.markSent(command.id(), seq, missionNow).map(sent -> {
            uplink.transmit(new TcFrame(seq, sent.type(), sent.args()));
            lastTransmission.put(seq, wallNow);
            commands.publish(sent);
            return true;
        }).orElse(false);
    }
}
