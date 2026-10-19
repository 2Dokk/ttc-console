package com.gsc.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.gsc.MutableClock;
import com.gsc.clock.SimulationClock;
import com.gsc.link.SimulationBootstrap;
import com.gsc.link.Uplink;
import com.gsc.protocol.Clcw;
import com.gsc.protocol.ExecutionReport;
import com.gsc.protocol.TcFrame;
import com.gsc.protocol.TcFrameCodec;
import com.gsc.spacecraft.Farm;
import com.gsc.tracking.LinkStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.LongStream;
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
@SpringBootTest(properties = {"gsc.scheduling.enabled=false", "gsc.tle.refresh-on-startup=false",
        "gsc.fop.transmission-limit=1000"})
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

    /**
     * 장시간 고장 주입: 유실 40%, 비트 오류 10%, 교신/두절을 반복하는 환경에서 명령 30건을 여러 패스에 걸쳐
     * 전달한다. 시드를 바꿔 가며 반복하고, 매번 세 가지 불변식을 확인한다.
     * <ul>
     *   <li>모든 명령이 정확히 한 번 실행된다</li>
     *   <li>실행 순서가 등록 순서와 같다</li>
     *   <li>마지막에는 모든 명령이 수신 확인된다</li>
     * </ul>
     */
    @Test
    void survivesLongRandomisedFaultInjection() {
        int commandCount = 30;
        List<Long> expectedOrder = LongStream.rangeClosed(1, commandCount).boxed().toList();
        int worstCaseTicks = 0;

        for (long seed : List.of(1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L)) {
            reset();
            for (int i = 0; i < commandCount; i++) {
                issue(CommandType.PING, Map.of());
            }

            Random channel = new Random(seed);
            Farm farm = new Farm(1);
            List<Long> executedOnboard = new ArrayList<>();
            int ticks = 0;
            while (ticks < 2000 && executedOnboard.size() < commandCount) {
                // 패스 모의: 120초 교신 후 60초 두절
                link.visible = ticks % 180 < 120;
                dispatcher.tick();

                for (TcFrame frame : uplink.drain()) {
                    byte[] onAir = TcFrameCodec.encode(frame);
                    if (channel.nextDouble() < 0.4) {
                        continue; // 프레임 유실
                    }
                    if (channel.nextDouble() < 0.1) {
                        int bit = channel.nextInt(onAir.length * 8);
                        onAir = onAir.clone();
                        onAir[bit / 8] ^= (byte) (1 << (bit % 8)); // 비트 오류 -> CRC가 검출해야 함
                    }
                    TcFrameCodec.decode(onAir).ifPresent(decoded -> {
                        if (farm.receive(decoded.seq()) == Farm.Verdict.ACCEPTED) {
                            executedOnboard.add(decoded.seq());
                        }
                    });
                }
                if (link.visible && channel.nextDouble() >= 0.3) {
                    commands.onClcw(farm.clcw()); // 수신 확인(일부는 유실)
                }
                clock.advance(Duration.ofSeconds(1));
                ticks++;
            }

            // 링크가 정상으로 돌아온 뒤, 마지막 수신 확인이 내려올 시간을 준다.
            link.visible = true;
            for (int settle = 0; settle < 20; settle++) {
                dispatcher.tick();
                uplink.drain().forEach(frame -> farm.receive(frame.seq()));
                commands.onClcw(farm.clcw());
                clock.advance(Duration.ofSeconds(1));
            }

            assertThat(executedOnboard).as("시드 %d: 순서대로 정확히 한 번씩 실행", seed)
                    .containsExactlyElementsOf(expectedOrder);
            assertThat(repo.findRecent(commandCount)).as("시드 %d: 모두 수신 확인", seed)
                    .extracting(Command::status).containsOnly(CommandStatus.ACKED);
            worstCaseTicks = Math.max(worstCaseTicks, ticks);
        }

        assertThat(worstCaseTicks).as("명령 30건 전달에 걸린 최악의 시간(초)").isLessThan(900);
    }

    private Command issue(CommandType type, Map<String, Object> args) {
        return commands.create(new CreateCommandRequest(null, type, args, null));
    }

    private CommandStatus status(Command command) {
        return repo.findById(command.id()).orElseThrow().status();
    }
}
