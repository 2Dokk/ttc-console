package com.gsc.clock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Mission time. Runs at {@code speed} x wall time from an anchor, so a 10-minute pass can be
 * demoed in seconds. Only moves forward: telemetry and command history are keyed by mission time.
 */
@Component
public class SimulationClock {

    public static final double MAX_SPEED = 1000.0;

    private final Clock wall;
    private Instant simAnchor;
    private Instant wallAnchor;
    private double speed = 1.0;

    public SimulationClock(Clock wallClock) {
        this.wall = wallClock;
        this.wallAnchor = wall.instant();
        this.simAnchor = wallAnchor;
    }

    public synchronized Instant now() {
        long wallNanos = Duration.between(wallAnchor, wall.instant()).toNanos();
        return simAnchor.plusNanos((long) (wallNanos * speed));
    }

    public synchronized double speed() {
        return speed;
    }

    public synchronized void setSpeed(double newSpeed) {
        if (newSpeed < 0 || newSpeed > MAX_SPEED) {
            throw new IllegalArgumentException("배속은 0~" + (int) MAX_SPEED + " 사이여야 합니다");
        }
        rebase();
        speed = newSpeed;
    }

    public synchronized void jumpTo(Instant target) {
        if (target.isBefore(now())) {
            throw new IllegalArgumentException("미션 시각은 앞으로만 이동할 수 있습니다");
        }
        simAnchor = target;
        wallAnchor = wall.instant();
    }

    private void rebase() {
        simAnchor = now();
        wallAnchor = wall.instant();
    }
}
