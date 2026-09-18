package com.gsc.link;

import com.gsc.config.GscProperties;
import com.gsc.protocol.PlaybackBatch;
import com.gsc.protocol.RealtimeFrameEmitted;
import com.gsc.protocol.TcFrame;
import com.gsc.spacecraft.SpacecraftSimulator;
import com.gsc.telemetry.TelemetryIngestService;
import com.gsc.tracking.LinkStatus;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Simulated RF channel between antenna and spacecraft: frames only get through while the satellite
 * is above the mask, arrive after a propagation delay, and are randomly lost at the configured rates.
 * A single delivery thread keeps frames in order, like a real link.
 *
 * <p>Recorder playback is treated as reliable (real systems protect it with file-level
 * retransmission such as CFDP, which is out of scope here).
 */
@Component
public class SpaceLink implements Uplink {

    public record Config(double uplinkLoss, double downlinkLoss) {}

    public record Stats(double uplinkLoss, double downlinkLoss, long oneWayDelayMs,
                        long tcFrames, long tcLost, long tmFrames, long tmLost) {}

    private static final Logger log = LoggerFactory.getLogger(SpaceLink.class);

    private final LinkStatus link;
    private final SpacecraftSimulator spacecraft;
    private final TelemetryIngestService ingest;
    private final long delayMs;
    private final ScheduledExecutorService rf = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "rf-link");
        thread.setDaemon(true);
        return thread;
    });

    private volatile double uplinkLoss;
    private volatile double downlinkLoss;
    private final AtomicLong tcFrames = new AtomicLong();
    private final AtomicLong tcLost = new AtomicLong();
    private final AtomicLong tmFrames = new AtomicLong();
    private final AtomicLong tmLost = new AtomicLong();

    public SpaceLink(LinkStatus link, SpacecraftSimulator spacecraft, TelemetryIngestService ingest,
                     GscProperties props) {
        this.link = link;
        this.spacecraft = spacecraft;
        this.ingest = ingest;
        this.delayMs = props.link().oneWayDelayMs();
        configure(new Config(props.link().uplinkLoss(), props.link().downlinkLoss()));
    }

    @Override
    public void transmit(TcFrame frame) {
        tcFrames.incrementAndGet();
        if (!link.isVisible() || lost(uplinkLoss)) {
            tcLost.incrementAndGet();
            return;
        }
        deliver(() -> {
            if (link.isVisible()) {
                spacecraft.receiveTc(frame);
            } else {
                tcLost.incrementAndGet();
            }
        });
    }

    @EventListener
    public void onRealtimeFrame(RealtimeFrameEmitted event) {
        tmFrames.incrementAndGet();
        if (lost(downlinkLoss)) {
            tmLost.incrementAndGet();
            return;
        }
        deliver(() -> ingest.onRealtimeFrame(event.frame()));
    }

    @EventListener
    public void onPlayback(PlaybackBatch batch) {
        deliver(() -> ingest.onPlayback(batch));
    }

    public void configure(Config config) {
        if (config.uplinkLoss() < 0 || config.uplinkLoss() > 1 || config.downlinkLoss() < 0
                || config.downlinkLoss() > 1) {
            throw new IllegalArgumentException("손실률은 0~1 사이여야 합니다");
        }
        uplinkLoss = config.uplinkLoss();
        downlinkLoss = config.downlinkLoss();
    }

    public Stats stats() {
        return new Stats(uplinkLoss, downlinkLoss, delayMs, tcFrames.get(), tcLost.get(), tmFrames.get(),
                tmLost.get());
    }

    private static boolean lost(double rate) {
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    private void deliver(Runnable delivery) {
        rf.schedule(() -> {
            try {
                delivery.run();
            } catch (RuntimeException e) {
                log.error("frame delivery failed", e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        rf.shutdownNow();
    }
}
