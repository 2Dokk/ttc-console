package com.gsc.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.gsc.MutableClock;
import com.gsc.link.Uplink;
import com.gsc.protocol.Clcw;
import com.gsc.protocol.TcFrame;
import com.gsc.tracking.LinkStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 링크가 완전히 죽은 상황: 응답이 끝내 오지 않으면 무한히 재시도하지 않고 송신을 멈추고 경보해야 한다.
 */
@SpringBootTest(properties = {"gsc.scheduling.enabled=false", "gsc.tle.refresh-on-startup=false",
        "gsc.fop.transmission-limit=3"})
@Testcontainers
class FopSuspensionIT {

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
        AlwaysVisibleLink alwaysVisibleLink() {
            return new AlwaysVisibleLink();
        }

        @Bean
        @Primary
        DeadUplink deadUplink() {
            return new DeadUplink();
        }
    }

    static class AlwaysVisibleLink implements LinkStatus {
        @Override
        public boolean isVisible() {
            return true;
        }
    }

    /** 송신은 받아 적기만 하고 위성에는 아무것도 도달하지 않는, 끊긴 링크. */
    static class DeadUplink implements Uplink {
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
    @Autowired DeadUplink uplink;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("truncate command restart identity");
        uplink.drain();
        dispatcher.initialize(1);
    }

    @Test
    void stopsTransmittingAndAlertsAfterTheRetransmissionLimit() {
        commands.create(new CreateCommandRequest(null, CommandType.PING, Map.of(), null));

        for (int i = 0; i < 10; i++) {
            dispatcher.tick();
            clock.advance(Duration.ofSeconds(4)); // 매번 타임아웃 -> 재전송
        }

        assertThat(dispatcher.isSuspended()).isTrue();
        assertThat(repo.findRecent(1).get(0).attempts()).isEqualTo(3);

        uplink.drain();
        dispatcher.tick();
        assertThat(uplink.drain()).as("중지 상태에서는 더 보내지 않는다").isEmpty();
    }

    @Test
    void operatorCanResumeAndTheCommandStillCompletes() {
        commands.create(new CreateCommandRequest(null, CommandType.PING, Map.of(), null));
        for (int i = 0; i < 10; i++) {
            dispatcher.tick();
            clock.advance(Duration.ofSeconds(4));
        }
        assertThat(dispatcher.isSuspended()).isTrue();

        dispatcher.resume();
        uplink.drain();
        dispatcher.tick();

        assertThat(uplink.drain()).as("재개하면 다시 송신한다").isNotEmpty();
        commands.onClcw(new Clcw(2, false));
        assertThat(repo.findRecent(1).get(0).status()).isEqualTo(CommandStatus.ACKED);
    }
}
