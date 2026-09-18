package com.gsc.orbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gsc.catalog.GroundStation;
import com.gsc.catalog.Satellite;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OrbitServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-18T06:00:00Z");
    private static final Satellite ISS = new Satellite(1, "ISS (ZARYA)", 25544,
            "1 25544U 98067A   26261.14280998  .00005718  00000+0  11125-3 0  9991",
            "2 25544  51.6307 200.0361 0004823 152.4527 207.6718 15.49160218586163");
    private static final GroundStation DAEJEON = new GroundStation(1, "Daejeon GS", 36.3725, 127.36, 100, 5);

    private static OrbitService orbit;

    @BeforeAll
    static void setUp() {
        orbit = new OrbitService(ISS, DAEJEON, "orekit-data");
    }

    @Test
    void issFliesInLowEarthOrbitWithinItsInclination() {
        for (int minute = 0; minute < 95; minute += 5) {
            SubPoint p = orbit.subPoint(T0.plus(Duration.ofMinutes(minute)));
            assertThat(p.altKm()).isBetween(380.0, 450.0);
            assertThat(Math.abs(p.latDeg())).isLessThanOrEqualTo(52.0);
        }
    }

    @Test
    void predictedPassesStartAndEndAtTheElevationMask() {
        List<Pass> passes = orbit.predictPasses(T0, Duration.ofHours(48), 20);

        assertThat(passes).isNotEmpty();
        for (Pass pass : passes) {
            Duration length = Duration.between(pass.aos(), pass.los());
            assertThat(length).isPositive().isLessThan(Duration.ofMinutes(15));
            assertThat(orbit.look(pass.aos()).elevationDeg()).isCloseTo(5.0, within(0.05));
            assertThat(orbit.look(pass.los()).elevationDeg()).isCloseTo(5.0, within(0.05));
            assertThat(pass.maxElevationDeg()).isBetween(5.0, 90.0);
            assertThat(orbit.isVisible(pass.tca())).isTrue();
            assertThat(orbit.isVisible(pass.aos().minusSeconds(30))).isFalse();
        }
    }

    @Test
    void passesAreOrderedAndDoNotOverlap() {
        List<Pass> passes = orbit.predictPasses(T0, Duration.ofHours(48), 20);

        for (int i = 1; i < passes.size(); i++) {
            assertThat(passes.get(i).aos()).isAfter(passes.get(i - 1).los());
        }
    }

    @Test
    void passInProgressKeepsItsRealAos() {
        Pass pass = orbit.predictPasses(T0, Duration.ofHours(48), 1).get(0);
        Instant midPass = pass.tca();

        Pass seenMidPass = orbit.predictPasses(midPass, Duration.ofHours(6), 1).get(0);

        assertThat(Duration.between(pass.aos(), seenMidPass.aos()).abs()).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void spendsMostOfTheOrbitInSunlight() {
        int sunlit = 0;
        int samples = 0;
        for (int minute = 0; minute < 93; minute++) {
            samples++;
            if (orbit.isSunlit(T0.plus(Duration.ofMinutes(minute)))) {
                sunlit++;
            }
        }
        assertThat((double) sunlit / samples).isBetween(0.5, 1.0);
    }
}
