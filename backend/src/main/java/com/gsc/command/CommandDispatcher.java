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
import java.util.stream.Collectors;
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

    /** seq -> wall time of its latest transmission. */
    private final Map<Long, Instant> lastTransmission = new ConcurrentHashMap<>();
    /** V(S): the sequence number the next new frame will carry. */
    private long nextSeq = 1;
    private boolean wasVisible;

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
    }

    /** Called once at startup, after recovery, before the first tick. */
    public synchronized void initialize(long firstSeq) {
        nextSeq = firstSeq;
        lastTransmission.clear();
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
        if (!visible) {
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

        if (!outstanding.isEmpty() && timedOut(outstanding.get(0).seq(), wallNow)) {
            if (!acquired) {
                events.warn("FOP", String.format("seq %d 응답 시간 초과, Go-Back-N: seq %d..%d 재전송",
                        outstanding.get(0).seq(), outstanding.get(0).seq(),
                        outstanding.get(outstanding.size() - 1).seq()));
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
