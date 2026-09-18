package com.gsc.link;

import com.gsc.clock.SimulationClock;
import com.gsc.command.CommandDispatcher;
import com.gsc.command.CommandRepository;
import com.gsc.config.GscProperties;
import com.gsc.spacecraft.SpacecraftSimulator;
import com.gsc.telemetry.TelemetryRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Startup alignment before the first tick:
 * <ul>
 *   <li>Mission time resumes from the latest stored history if that is ahead of the wall clock
 *       (time was skipped forward in a previous run), so history never overlaps itself.</li>
 *   <li>Ground V(S) and spacecraft V(R) are brought into agreement. A real system would do this
 *       with a COP-1 "Set V(R)" control directive; here both ends restart together.</li>
 * </ul>
 */
@Component
public class SimulationBootstrap {

    private final CommandRepository commands;
    private final TelemetryRepository telemetry;
    private final SimulationClock clock;
    private final CommandDispatcher dispatcher;
    private final SpacecraftSimulator spacecraft;
    private final long satelliteId;

    public SimulationBootstrap(CommandRepository commands, TelemetryRepository telemetry, SimulationClock clock,
                               CommandDispatcher dispatcher, SpacecraftSimulator spacecraft, GscProperties props) {
        this.commands = commands;
        this.telemetry = telemetry;
        this.clock = clock;
        this.dispatcher = dispatcher;
        this.spacecraft = spacecraft;
        this.satelliteId = props.satelliteId();
    }

    @PostConstruct
    public void synchronize() {
        Stream.of(telemetry.latestRecordedAt(), commands.latestActivity())
                .flatMap(Optional::stream)
                .max(Instant::compareTo)
                .filter(latest -> latest.isAfter(clock.now()))
                .ifPresent(clock::jumpTo);

        commands.recoverAfterRestart();
        long firstSeq = commands.maxSeq(satelliteId) + 1;
        dispatcher.initialize(firstSeq);
        spacecraft.resetFarm(firstSeq);
    }
}
