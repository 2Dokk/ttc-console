package com.gsc.tracking;

import com.gsc.clock.SimulationClock;
import com.gsc.events.OpsEventLog;
import com.gsc.orbit.LookAngles;
import com.gsc.orbit.OrbitService;
import com.gsc.orbit.Pass;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Antenna tracking loop: every tick computes where the satellite is, whether it is above the mask
 * (AOS/LOS), and keeps a short schedule of upcoming passes. Broadcasts a snapshot on /topic/state.
 */
@Service
public class TrackingService implements LinkStatus {

    private static final Duration PREDICTION_HORIZON = Duration.ofHours(48);
    private static final int PREDICTED_PASSES = 12;
    private static final int MIN_UPCOMING = 3;

    private final OrbitService orbit;
    private final SimulationClock clock;
    private final OpsEventLog events;
    private final SimpMessagingTemplate messaging;

    private volatile boolean visible;
    private volatile TrackingSnapshot snapshot;
    private volatile List<Pass> passes = List.of();
    private Boolean lastVisible;

    public TrackingService(OrbitService orbit, SimulationClock clock, OpsEventLog events,
                           SimpMessagingTemplate messaging) {
        this.orbit = orbit;
        this.clock = clock;
        this.events = events;
        this.messaging = messaging;
    }

    @Scheduled(fixedRate = 250)
    public void tick() {
        Instant now = clock.now();
        LookAngles look = orbit.look(now);
        boolean vis = look.elevationDeg() >= orbit.station().minElevationDeg();
        List<Pass> schedule = refreshPasses(now);

        if (lastVisible != null && vis != lastVisible) {
            if (vis) {
                events.info("LINK", String.format("AOS: %s 고도각 %.0f° 위로 진입 (방위각 %.0f°), 교신 시작",
                        orbit.satellite().name(), orbit.station().minElevationDeg(), look.azimuthDeg()));
            } else {
                events.info("LINK", "LOS: 교신 종료, 위성은 텔레메트리를 온보드 레코더에 기록합니다");
            }
        }
        lastVisible = vis;
        visible = vis;

        Pass current = vis ? schedule.stream().filter(p -> p.contains(now)).findFirst().orElse(null) : null;
        Pass next = schedule.stream().filter(p -> p.aos().isAfter(now)).findFirst().orElse(null);
        snapshot = new TrackingSnapshot(now, clock.speed(), orbit.subPoint(now), look, vis,
                orbit.isSunlit(now), current, next);
        messaging.convertAndSend("/topic/state", snapshot);
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    public TrackingSnapshot snapshot() {
        if (snapshot == null) {
            tick();
        }
        return snapshot;
    }

    public List<Pass> upcomingPasses(int count) {
        Instant now = clock.now();
        return refreshPasses(now).stream().filter(p -> p.los().isAfter(now)).limit(count).toList();
    }

    public Pass nextPass() {
        Instant now = clock.now();
        return refreshPasses(now).stream().filter(p -> p.aos().isAfter(now)).findFirst()
                .orElseThrow(() -> new IllegalStateException("예측 범위(48시간) 안에 다음 패스가 없습니다"));
    }

    /** Re-predicts only when the cached schedule runs low, so AOS/LOS times stay stable mid-pass. */
    private synchronized List<Pass> refreshPasses(Instant now) {
        long upcoming = passes.stream().filter(p -> p.los().isAfter(now)).count();
        if (upcoming < MIN_UPCOMING) {
            passes = orbit.predictPasses(now, PREDICTION_HORIZON, PREDICTED_PASSES);
        }
        return passes;
    }
}
