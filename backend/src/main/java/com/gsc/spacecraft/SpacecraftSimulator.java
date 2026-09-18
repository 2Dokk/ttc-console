package com.gsc.spacecraft;

import com.gsc.clock.SimulationClock;
import com.gsc.config.GscProperties;
import com.gsc.orbit.OrbitService;
import com.gsc.protocol.ExecutionReport;
import com.gsc.protocol.PlaybackBatch;
import com.gsc.protocol.RealtimeFrameEmitted;
import com.gsc.protocol.TcFrame;
import com.gsc.protocol.TmFrame;
import com.gsc.protocol.TmSample;
import com.gsc.tracking.LinkStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The simulated satellite. Integrates the bus model along the real orbit (so eclipses drain the
 * battery), accepts uplink frames through its FARM, executes them in order, and transmits:
 * <ul>
 *   <li>in contact: a real-time frame every second, plus recorder playback chunks;</li>
 *   <li>out of contact: nothing; housekeeping goes to the onboard recorder instead.</li>
 * </ul>
 */
@Component
public class SpacecraftSimulator {

    /** Integration step. Also bounds the work done when mission time is skipped forward. */
    private static final Duration STEP = Duration.ofSeconds(10);
    private static final Duration MAX_CATCH_UP = Duration.ofHours(24);
    private static final int EXECUTION_HISTORY = 8;

    private final SimulationClock clock;
    private final OrbitService orbit;
    private final LinkStatus link;
    private final ApplicationEventPublisher publisher;
    private final Duration recorderInterval;
    private final int playbackChunk;

    private final SpacecraftBus bus = new SpacecraftBus(42L);
    private final Farm farm = new Farm(1);
    private final OnboardRecorder recorder;
    private final Deque<TcFrame> accepted = new ArrayDeque<>();
    private final Deque<ExecutionReport> recentExecutions = new ArrayDeque<>();

    private Instant lastStep;
    private Instant nextRecordAt;
    private boolean sunlit = true;
    private long realtimeSeq;
    private long recordedSeq;

    public SpacecraftSimulator(SimulationClock clock, OrbitService orbit, LinkStatus link,
                               ApplicationEventPublisher publisher, GscProperties props) {
        this.clock = clock;
        this.orbit = orbit;
        this.link = link;
        this.publisher = publisher;
        this.recorderInterval = Duration.ofSeconds(props.spacecraft().recorderIntervalSec());
        this.playbackChunk = props.spacecraft().playbackChunk();
        this.recorder = new OnboardRecorder(props.spacecraft().recorderCapacity());
    }

    @Scheduled(fixedRate = 1000)
    public synchronized void tick() {
        Instant now = clock.now();
        advanceTo(now);
        executeAccepted(now);

        if (!link.isVisible()) {
            return;
        }
        List<ExecutionReport> executions = List.copyOf(recentExecutions);
        publisher.publishEvent(new RealtimeFrameEmitted(new TmFrame(
                bus.sample(++realtimeSeq, now, sunlit), farm.clcw(), executions)));
        if (!recorder.isEmpty()) {
            List<TmSample> chunk = recorder.drain(playbackChunk);
            publisher.publishEvent(new PlaybackBatch(chunk, executions, recorder.size()));
        }
    }

    /** Uplink entry point, called by the RF link when a frame survives the channel. */
    public synchronized void receiveTc(TcFrame frame) {
        if (farm.receive(frame.seq()) == Farm.Verdict.ACCEPTED) {
            accepted.addLast(frame);
        }
    }

    /** Aligns V(R) with the ground's V(S) at startup (both sides restart together in this simulator). */
    public synchronized void resetFarm(long vr) {
        farm.reset(vr);
        accepted.clear();
    }

    public synchronized int recorderSize() {
        return recorder.size();
    }

    private void advanceTo(Instant now) {
        if (lastStep == null) {
            lastStep = now;
            nextRecordAt = now;
            sunlit = orbit.isSunlit(now);
            return;
        }
        Instant t = lastStep;
        if (Duration.between(t, now).compareTo(MAX_CATCH_UP) > 0) {
            t = now.minus(MAX_CATCH_UP);
        }
        if (nextRecordAt.isBefore(t)) {
            nextRecordAt = t;
        }
        while (t.isBefore(now)) {
            Instant next = t.plus(STEP).isBefore(now) ? t.plus(STEP) : now;
            sunlit = orbit.isSunlit(next);
            bus.step(Duration.between(t, next).toMillis() / 1000.0, sunlit);
            t = next;
            if (!t.isBefore(nextRecordAt)) {
                if (!orbit.isVisible(t)) {
                    recorder.record(bus.sample(++recordedSeq, t, sunlit));
                }
                nextRecordAt = t.plus(recorderInterval);
            }
        }
        lastStep = now;
    }

    private void executeAccepted(Instant now) {
        while (!accepted.isEmpty()) {
            recentExecutions.addLast(bus.execute(accepted.removeFirst(), now));
            if (recentExecutions.size() > EXECUTION_HISTORY) {
                recentExecutions.removeFirst();
            }
        }
    }
}
