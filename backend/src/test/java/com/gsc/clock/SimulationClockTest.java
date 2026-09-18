package com.gsc.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gsc.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SimulationClockTest {

    private static final Instant T0 = Instant.parse("2026-09-18T00:00:00Z");

    @Test
    void runsAtConfiguredSpeed() {
        MutableClock wall = new MutableClock(T0);
        SimulationClock clock = new SimulationClock(wall);

        clock.setSpeed(60);
        wall.advance(Duration.ofSeconds(10));

        assertThat(clock.now()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
    }

    @Test
    void changingSpeedDoesNotJumpTime() {
        MutableClock wall = new MutableClock(T0);
        SimulationClock clock = new SimulationClock(wall);
        clock.setSpeed(10);
        wall.advance(Duration.ofSeconds(6));
        Instant before = clock.now();

        clock.setSpeed(1);

        assertThat(clock.now()).isEqualTo(before);
        wall.advance(Duration.ofSeconds(1));
        assertThat(clock.now()).isEqualTo(before.plusSeconds(1));
    }

    @Test
    void onlyMovesForward() {
        SimulationClock clock = new SimulationClock(new MutableClock(T0));

        clock.jumpTo(T0.plus(Duration.ofHours(3)));

        assertThat(clock.now()).isEqualTo(T0.plus(Duration.ofHours(3)));
        assertThatThrownBy(() -> clock.jumpTo(T0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void speedZeroPauses() {
        MutableClock wall = new MutableClock(T0);
        SimulationClock clock = new SimulationClock(wall);

        clock.setSpeed(0);
        wall.advance(Duration.ofMinutes(5));

        assertThat(clock.now()).isEqualTo(T0);
    }
}
