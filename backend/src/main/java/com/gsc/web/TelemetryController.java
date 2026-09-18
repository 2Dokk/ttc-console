package com.gsc.web;

import com.gsc.clock.SimulationClock;
import com.gsc.telemetry.TelemetryIngestService;
import com.gsc.telemetry.TelemetryPoint;
import java.time.Duration;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryIngestService telemetry;
    private final SimulationClock clock;

    public TelemetryController(TelemetryIngestService telemetry, SimulationClock clock) {
        this.telemetry = telemetry;
        this.clock = clock;
    }

    /** Stored telemetry for the last {@code minutes} of mission time, real-time and playback merged. */
    @GetMapping
    public List<TelemetryPoint> history(@RequestParam(defaultValue = "360") int minutes,
                                        @RequestParam(defaultValue = "3000") int limit) {
        return telemetry.history(clock.now().minus(Duration.ofMinutes(minutes)), Math.min(limit, 10_000));
    }
}
