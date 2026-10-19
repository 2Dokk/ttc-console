package com.gsc.link;

import com.gsc.config.GscProperties;
import com.gsc.protocol.PlaybackBatch;
import com.gsc.protocol.RealtimeFrameEmitted;
import com.gsc.protocol.TcFrame;
import com.gsc.protocol.TcFrameCodec;
import com.gsc.protocol.TmFrameCodec;
import com.gsc.spacecraft.SpacecraftSimulator;
import com.gsc.telemetry.TelemetryIngestService;
import com.gsc.tracking.LinkStatus;
import jakarta.annotation.PreDestroy;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 안테나와 위성 사이의 RF 채널 모의. 프레임은 교신 가능할 때만 통과하고, 전파 지연 뒤에 도착하며,
 * 다음 세 가지 방식으로 손상·유실된다.
 *
 * <ul>
 *   <li><b>프레임 유실</b>: 설정한 확률로 프레임이 통째로 사라진다.</li>
 *   <li><b>버스트 손실</b>: 정상/악화 두 상태를 오가는 모델(Gilbert-Elliott)로, 악화 상태에서는 연속으로 유실된다.
 *       실제로는 기상이나 간섭으로 수 프레임이 몰아서 사라지는 경우가 많다.</li>
 *   <li><b>비트 오류</b>: 원격명령 프레임의 비트 하나가 뒤집힌다. 위성은 프레임 끝의 CRC로 이를 검출해 폐기한다.</li>
 * </ul>
 *
 * 난수는 시드로 고정할 수 있어, 같은 손실·오류 패턴을 반복 재현할 수 있다(시드 0이면 매번 달라진다).
 * 전파 지연에는 흔들림(jitter)을 줄 수 있고, 이 때문에 프레임 순서가 뒤바뀔 수도 있다.
 *
 * <p>레코더 재생 데이터는 무손실로 가정한다(실제로는 CFDP 같은 파일 단위 재전송이 필요).
 */
@Component
public class SpaceLink implements Uplink {

    /** 운용 화면에서 조절할 수 있는 채널 품질. */
    public record Config(double uplinkLoss, double downlinkLoss, double bitErrorRate) {}

    public record Stats(double uplinkLoss, double downlinkLoss, double bitErrorRate, long oneWayDelayMs,
                        long jitterMs, boolean burstEnabled, long tcFrames, long tcLost, long tcCorrupted,
                        long tcCrcRejected, long tmFrames, long tmLost, long tmCorrupted) {}

    private static final Logger log = LoggerFactory.getLogger(SpaceLink.class);

    private final LinkStatus link;
    private final SpacecraftSimulator spacecraft;
    private final TelemetryIngestService ingest;
    private final long delayMs;
    private final long jitterMs;
    private final GscProperties.Link.Burst burst;
    private final Random rng;
    private final ScheduledExecutorService rf = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "rf-link");
        thread.setDaemon(true);
        return thread;
    });

    private volatile double uplinkLoss;
    private volatile double downlinkLoss;
    private volatile double bitErrorRate;
    private boolean inBadState;

    private final AtomicLong tcFrames = new AtomicLong();
    private final AtomicLong tcLost = new AtomicLong();
    private final AtomicLong tcCorrupted = new AtomicLong();
    private final AtomicLong tmFrames = new AtomicLong();
    private final AtomicLong tmLost = new AtomicLong();
    private final AtomicLong tmCorrupted = new AtomicLong();

    public SpaceLink(LinkStatus link, SpacecraftSimulator spacecraft, TelemetryIngestService ingest,
                     GscProperties props) {
        this.link = link;
        this.spacecraft = spacecraft;
        this.ingest = ingest;
        this.delayMs = props.link().oneWayDelayMs();
        this.jitterMs = props.link().jitterMs();
        this.burst = props.link().burst();
        this.rng = props.link().seed() == 0 ? new Random() : new Random(props.link().seed());
        configure(new Config(props.link().uplinkLoss(), props.link().downlinkLoss(), props.link().bitErrorRate()));
    }

    @Override
    public void transmit(TcFrame frame) {
        tcFrames.incrementAndGet();
        byte[] encoded = TcFrameCodec.encode(frame);
        if (!link.isVisible() || drops(uplinkLoss)) {
            tcLost.incrementAndGet();
            return;
        }
        byte[] onAir = maybeCorrupt(encoded, tcCorrupted);
        deliver(() -> {
            if (link.isVisible()) {
                spacecraft.receiveTc(onAir);
            } else {
                tcLost.incrementAndGet();
            }
        });
    }

    @EventListener
    public void onRealtimeFrame(RealtimeFrameEmitted event) {
        tmFrames.incrementAndGet();
        byte[] encoded = TmFrameCodec.encode(event.frame());
        if (drops(downlinkLoss)) {
            tmLost.incrementAndGet();
            return;
        }
        byte[] onAir = maybeCorrupt(encoded, tmCorrupted);
        deliver(() -> ingest.onRealtimeFrame(onAir));
    }

    @EventListener
    public void onPlayback(PlaybackBatch batch) {
        deliver(() -> ingest.onPlayback(batch));
    }

    public void configure(Config config) {
        if (outOfRange(config.uplinkLoss()) || outOfRange(config.downlinkLoss())
                || outOfRange(config.bitErrorRate())) {
            throw new IllegalArgumentException("손실률과 비트 오류율은 0~1 사이여야 합니다");
        }
        uplinkLoss = config.uplinkLoss();
        downlinkLoss = config.downlinkLoss();
        bitErrorRate = config.bitErrorRate();
    }

    public Stats stats() {
        return new Stats(uplinkLoss, downlinkLoss, bitErrorRate, delayMs, jitterMs, burst.enabled(),
                tcFrames.get(), tcLost.get(), tcCorrupted.get(), spacecraft.crcRejected(),
                tmFrames.get(), tmLost.get(), tmCorrupted.get());
    }

    /** 버스트 상태를 한 단계 진행시킨 뒤, 이번 프레임이 유실되는지 판단한다. */
    private synchronized boolean drops(double baseLoss) {
        if (burst.enabled()) {
            if (inBadState) {
                inBadState = rng.nextDouble() >= burst.badToGood();
            } else {
                inBadState = rng.nextDouble() < burst.goodToBad();
            }
        }
        double rate = burst.enabled() && inBadState ? Math.max(baseLoss, burst.lossInBadState()) : baseLoss;
        return rng.nextDouble() < rate;
    }

    /** 설정 확률로 프레임의 비트 하나를 뒤집는다. 수신 측 CRC가 이를 검출해야 한다. */
    private synchronized byte[] maybeCorrupt(byte[] frame, AtomicLong counter) {
        if (bitErrorRate <= 0 || rng.nextDouble() >= bitErrorRate) {
            return frame;
        }
        byte[] corrupted = frame.clone();
        int bit = rng.nextInt(corrupted.length * 8);
        corrupted[bit / 8] ^= (byte) (1 << (bit % 8));
        counter.incrementAndGet();
        return corrupted;
    }

    /** 전파 지연 + 흔들림. 흔들림이 크면 프레임 도착 순서가 뒤바뀔 수 있다. */
    private synchronized long travelTimeMs() {
        if (jitterMs <= 0) {
            return delayMs;
        }
        return Math.max(0, delayMs + rng.nextInt((int) (2 * jitterMs + 1)) - jitterMs);
    }

    private static boolean outOfRange(double value) {
        return value < 0 || value > 1;
    }

    private void deliver(Runnable delivery) {
        rf.schedule(() -> {
            try {
                delivery.run();
            } catch (RuntimeException e) {
                log.error("frame delivery failed", e);
            }
        }, travelTimeMs(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        rf.shutdownNow();
    }
}
