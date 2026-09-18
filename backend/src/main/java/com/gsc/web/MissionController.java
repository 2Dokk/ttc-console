package com.gsc.web;

import com.gsc.catalog.GroundStation;
import com.gsc.clock.SimulationClock;
import com.gsc.config.GscProperties;
import com.gsc.events.OpsEventLog;
import com.gsc.events.OpsEventLog.OpsEvent;
import com.gsc.link.SpaceLink;
import com.gsc.orbit.LookAngles;
import com.gsc.orbit.OrbitService;
import com.gsc.orbit.Pass;
import com.gsc.orbit.SubPoint;
import com.gsc.telemetry.TelemetryIngestService;
import com.gsc.tracking.TrackingService;
import com.gsc.tracking.TrackingSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MissionController {

    /** Seconds before AOS to land on when skipping ahead, so the operator sees the acquisition. */
    private static final Duration SKIP_LEAD = Duration.ofSeconds(60);

    public record SatelliteInfo(long id, String name, int noradId, Instant tleEpoch) {}

    public record MissionConfig(SatelliteInfo satellite, GroundStation station, int fopWindowSize,
                                long fopAckTimeoutMs) {}

    public record SpeedRequest(double speed) {}

    public record LinkStatusView(SpaceLink.Stats channel, TelemetryIngestService.Stats reception) {}

    private final OrbitService orbit;
    private final TrackingService tracking;
    private final SimulationClock clock;
    private final SpaceLink spaceLink;
    private final TelemetryIngestService telemetry;
    private final OpsEventLog events;
    private final GscProperties props;

    public MissionController(OrbitService orbit, TrackingService tracking, SimulationClock clock,
                             SpaceLink spaceLink, TelemetryIngestService telemetry, OpsEventLog events,
                             GscProperties props) {
        this.orbit = orbit;
        this.tracking = tracking;
        this.clock = clock;
        this.spaceLink = spaceLink;
        this.telemetry = telemetry;
        this.events = events;
        this.props = props;
    }

    @GetMapping("/config")
    public MissionConfig config() {
        var sat = orbit.satellite();
        return new MissionConfig(new SatelliteInfo(sat.id(), sat.name(), sat.noradId(), orbit.tleEpoch()),
                orbit.station(), props.fop().windowSize(), props.fop().ackTimeoutMs());
    }

    @GetMapping("/state")
    public TrackingSnapshot state() {
        return tracking.snapshot();
    }

    @GetMapping("/passes")
    public List<Pass> passes(@RequestParam(defaultValue = "6") int count) {
        return tracking.upcomingPasses(Math.min(count, 12));
    }

    /** The pass in progress, or the next one, with its az/el curve for the sky plot. */
    @GetMapping("/passes/active/skytrack")
    public SkyTrack skyTrack() {
        TrackingSnapshot now = tracking.snapshot();
        Pass pass = now.currentPass() != null ? now.currentPass() : tracking.nextPass();
        return new SkyTrack(pass, orbit.skyTrack(pass.aos(), pass.los(), Duration.ofSeconds(10)));
    }

    public record SkyTrack(Pass pass, List<LookAngles> points) {}

    @GetMapping("/orbit/track")
    public List<SubPoint> groundTrack(@RequestParam(defaultValue = "45") int behindMin,
                                      @RequestParam(defaultValue = "95") int aheadMin,
                                      @RequestParam(defaultValue = "30") int stepSec) {
        if (behindMin < 0 || aheadMin < 0 || behindMin + aheadMin > 24 * 60 || stepSec < 5) {
            throw new IllegalArgumentException("궤적 범위는 24시간 이내, 간격은 5초 이상이어야 합니다");
        }
        Instant now = clock.now();
        return orbit.groundTrack(now.minus(Duration.ofMinutes(behindMin)), now.plus(Duration.ofMinutes(aheadMin)),
                Duration.ofSeconds(stepSec));
    }

    @PostMapping("/clock/speed")
    public TrackingSnapshot setSpeed(@RequestBody SpeedRequest request) {
        clock.setSpeed(request.speed());
        events.info("CLOCK", String.format("시뮬레이션 배속 ×%.0f", request.speed()));
        return tracking.snapshot();
    }

    @PostMapping("/clock/skip-to-next-pass")
    public TrackingSnapshot skipToNextPass() {
        Pass next = tracking.nextPass();
        Instant target = next.aos().minus(SKIP_LEAD);
        if (target.isAfter(clock.now())) {
            clock.jumpTo(target);
            events.info("CLOCK", String.format("%s로 건너뜀 (AOS %d초 전, 최대 고도각 %.0f°)", target,
                    SKIP_LEAD.toSeconds(), next.maxElevationDeg()));
        }
        tracking.tick();
        return tracking.snapshot();
    }

    @GetMapping("/link")
    public LinkStatusView link() {
        return new LinkStatusView(spaceLink.stats(), telemetry.stats());
    }

    @PutMapping("/link")
    public LinkStatusView configureLink(@RequestBody SpaceLink.Config config) {
        spaceLink.configure(config);
        events.info("LINK", String.format("채널 손실률 설정: 업링크 %.0f%%, 다운링크 %.0f%%",
                config.uplinkLoss() * 100, config.downlinkLoss() * 100));
        return link();
    }

    @GetMapping("/events")
    public List<OpsEvent> events(@RequestParam(defaultValue = "100") int limit) {
        return events.recent(Math.min(limit, 300));
    }
}
