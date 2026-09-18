package com.gsc.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.gsc.MutableClock;
import com.gsc.clock.SimulationClock;
import com.gsc.link.SimulationBootstrap;
import com.gsc.link.Uplink;
import com.gsc.protocol.Clcw;
import com.gsc.protocol.ExecutionReport;
import com.gsc.protocol.TcFrame;
import com.gsc.spacecraft.Farm;
import com.gsc.tracking.LinkStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Drives the FOP dispatcher by hand against a real PostgreSQL, with the RF link replaced by a
 * recorder and visibility switched by the test.
 */
@SpringBootTest(properties = {"gsc.scheduling.enabled=false", "gsc.tle.refresh-on-startup=false"})
@Testcontainers
class CommandUplinkIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-09-18T06:00:00Z"));
        }

        @Bean
        @Primary
        SwitchableLink switchableLink() {
            return new SwitchableLink();
        }

        @Bean
        @Primary
        RecordingUplink recordingUplink() {
            return new RecordingUplink();
        }
    }

    static class SwitchableLink implements LinkStatus {
        volatile boolean visible;

        @Override
        public boolean isVisible() {
            return visible;
        }
    }

    static class RecordingUplink implements Uplink {
        final List<TcFrame> frames = new ArrayList<>();

        @Override
        public synchronized void transmit(TcFrame frame) {
            frames.add(frame);
        }

        synchronized List<TcFrame> drain() {
            List<TcFrame> out = new ArrayList<>(frames);
            frames.clear();
            return out;
        }
    }

    @Autowired CommandService commands;
    @Autowired CommandDispatcher dispatcher;
    @Autowired CommandRepository repo;
    @Autowired SwitchableLink link;
    @Autowired RecordingUplink uplink;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired SimulationClock missionClock;
    @Autowired SimulationBootstrap bootstrap;

    @BeforeEach
    void reset() {
        jdbc.update("truncate command restart identity");
        link.visible = false;
        uplink.drain();
        dispatcher.initialize(1);
        dispatcher.tick(); // settle the AOS edge detector in the LOS state
    }

    @Test
    void outOfContactCommandsWaitAndGoOutInIssueOrderAtAos() {
        Command first = issue(CommandType.SET_MODE, Map.of("mode", "MISSION"));
        Command second = issue(CommandType.PAYLOAD_POWER, Map.of("on", true));
        Command third = issue(CommandType.PING, Map.of());

        dispatcher.tick();
        assertThat(uplink.drain()).isEmpty();
        assertThat(status(first)).isEqualTo(CommandStatus.PENDING);

        link.visible = true;
        dispatcher.tick();

        List<TcFrame> sent = uplink.drain();
        assertThat(sent).extracting(TcFrame::seq).containsExactly(1L, 2L, 3L);
        assertThat(sent).extracting(TcFrame::type)
                .containsExactly(CommandType.SET_MODE, CommandType.PAYLOAD_POWER, CommandType.PING);
        assertThat(List.of(status(first), status(second), status(third))).containsOnly(CommandStatus.SENT);
    }

    @Test
    void windowLimitsUnacknowledgedFrames() {
        for (int i = 0; i < 6; i++) {
            issue(CommandType.PING, Map.of());
        }
        link.visible = true;

        dispatcher.tick();
        assertThat(uplink.drain()).extracting(TcFrame::seq).containsExactly(1L, 2L, 3L, 4L);

        commands.onClcw(new Clcw(3, false));
        dispatcher.tick();

        assertThat(uplink.drain()).extracting(TcFrame::seq).containsExactly(5L, 6L);
        assertThat(repo.findRecent(10)).filteredOn(c -> c.status() == CommandStatus.ACKED)
                .extracting(Command::seq).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void ackTimeoutRetransmitsEveryOutstandingFrameInOrder() {
        issue(CommandType.PING, Map.of());
        issue(CommandType.PING, Map.of());
        Command third = issue(CommandType.PING, Map.of());
        link.visible = true;
        dispatcher.tick();
        uplink.drain();

        commands.onClcw(new Clcw(2, false));
        clock.advance(Duration.ofSeconds(2));
        dispatcher.tick();
        assertThat(uplink.drain()).as("before the timeout nothing is resent").isEmpty();

        clock.advance(Duration.ofSeconds(2));
        dispatcher.tick();

        assertThat(uplink.drain()).extracting(TcFrame::seq).containsExactly(2L, 3L);
        assertThat(repo.findById(third.id()).orElseThrow().attempts()).isEqualTo(2);
    }

    @Test
    void lateAcknowledgementNeverRollsBackAnExecutedCommand() {
        Command command = issue(CommandType.PING, Map.of());
        link.visible = true;
        dispatcher.tick();

        // execution report overtakes the CLCW (e.g. the frame carrying the ACK was lost)
        ExecutionReport report = new ExecutionReport(1, true, "PONG", Instant.parse("2026-09-18T06:00:01Z"));
        commands.onExecutionReports(List.of(report));
        commands.onClcw(new Clcw(2, false));
        commands.onExecutionReports(List.of(report));

        Command after = repo.findById(command.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(CommandStatus.EXECUTED);
        assertThat(after.ackedAt()).isNotNull();
    }

    @Test
    void rejectedExecutionIsReportedSeparatelyFromDelivery() {
        Command command = issue(CommandType.PAYLOAD_POWER, Map.of("on", true));
        link.visible = true;
        dispatcher.tick();
        commands.onClcw(new Clcw(2, false));

        commands.onExecutionReports(List.of(new ExecutionReport(1, false, "payload requires MISSION mode",
                Instant.parse("2026-09-18T06:00:01Z"))));

        Command after = repo.findById(command.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(after.resultMessage()).contains("MISSION");
    }

    @Test
    void expiredAndCancelledCommandsNeverConsumeASequenceNumber() {
        Command expiring = commands.create(new CreateCommandRequest(null, CommandType.PING, Map.of(), 1));
        Command cancelled = issue(CommandType.PING, Map.of());
        Command kept = issue(CommandType.HEATER, Map.of("on", true));
        commands.cancel(cancelled.id());

        clock.advance(Duration.ofMinutes(2));
        dispatcher.tick();
        link.visible = true;
        dispatcher.tick();

        assertThat(status(expiring)).isEqualTo(CommandStatus.EXPIRED);
        assertThat(status(cancelled)).isEqualTo(CommandStatus.CANCELLED);
        assertThat(uplink.drain()).extracting(TcFrame::seq).containsExactly(1L);
        assertThat(repo.findById(kept.id()).orElseThrow().seq()).isEqualTo(1L);
    }

    @Test
    void lossyChannelStillDeliversEveryCommandExactlyOnceAndInOrder() {
        int count = 12;
        for (int i = 0; i < count; i++) {
            issue(CommandType.PING, Map.of());
        }
        Farm farm = new Farm(1);
        List<Long> executedOnboard = new ArrayList<>();
        Random channel = new Random(7);
        link.visible = true;

        for (int step = 0; step < 500 && !repo.findRecent(count).stream()
                .allMatch(c -> c.status() == CommandStatus.ACKED); step++) {
            dispatcher.tick();
            for (TcFrame frame : uplink.drain()) {
                if (channel.nextDouble() < 0.4) {
                    continue; // uplink frame lost
                }
                if (farm.receive(frame.seq()) == Farm.Verdict.ACCEPTED) {
                    executedOnboard.add(frame.seq());
                }
            }
            if (channel.nextDouble() >= 0.3) { // downlink frame carrying the CLCW survived
                commands.onClcw(farm.clcw());
            }
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(executedOnboard).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        List<Command> all = repo.findRecent(count);
        assertThat(all).extracting(Command::status).containsOnly(CommandStatus.ACKED);
        assertThat(all.stream().mapToInt(Command::attempts).sum())
                .as("losses forced retransmissions").isGreaterThan(count);
    }

    @Test
    void restartResumesMissionTimeAfterTheLatestStoredHistory() {
        Instant ahead = missionClock.now().plus(Duration.ofHours(5));
        jdbc.update("""
                insert into telemetry_sample (satellite_id, seq_count, source, recorded_at, received_at,
                    battery_pct, temp_c, roll_deg, pitch_deg, yaw_deg, mode, heater_on, payload_on, sunlit)
                values (1, 1, 'PLAYBACK', ?, ?, 80, 15, 0, 0, 0, 'NOMINAL', false, false, true)""",
                CommandRepository.utc(ahead), CommandRepository.utc(ahead));

        bootstrap.synchronize();

        assertThat(missionClock.now()).isAfterOrEqualTo(ahead);
        jdbc.update("truncate telemetry_sample");
    }

    private Command issue(CommandType type, Map<String, Object> args) {
        return commands.create(new CreateCommandRequest(null, type, args, null));
    }

    private CommandStatus status(Command command) {
        return repo.findById(command.id()).orElseThrow().status();
    }
}
