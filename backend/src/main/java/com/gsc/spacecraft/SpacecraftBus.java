package com.gsc.spacecraft;

import com.gsc.protocol.ExecutionReport;
import com.gsc.protocol.TcFrame;
import com.gsc.protocol.TmSample;
import java.time.Instant;
import java.util.Random;

/**
 * Toy physical model of the satellite bus: a power budget (solar array vs loads), a first-order
 * thermal response and attitude jitter per mode. Values are illustrative, not from a real spacecraft.
 */
class SpacecraftBus {

    static final double BATTERY_CAPACITY_WH = 150.0;
    static final double SOLAR_ARRAY_W = 60.0;
    static final double BUS_LOAD_W = 20.0;
    static final double HEATER_LOAD_W = 15.0;
    static final double PAYLOAD_LOAD_W = 40.0;
    static final double SAFE_MODE_THRESHOLD_PCT = 15.0;
    static final double MISSION_MIN_BATTERY_PCT = 40.0;
    private static final double THERMAL_TIME_CONSTANT_SEC = 900.0;
    private static final double ATTITUDE_TIME_CONSTANT_SEC = 300.0;

    private final Random rng;
    double batteryPct = 80.0;
    double tempC = 15.0;
    double rollDeg;
    double pitchDeg;
    double yawDeg;
    SpacecraftMode mode = SpacecraftMode.NOMINAL;
    boolean heaterOn;
    boolean payloadOn;

    SpacecraftBus(long seed) {
        this.rng = new Random(seed);
    }

    /** Advances the model by {@code dtSec}; on-board FDIR forces SAFE mode on low battery. */
    void step(double dtSec, boolean sunlit) {
        double generated = sunlit ? SOLAR_ARRAY_W : 0.0;
        double load = BUS_LOAD_W + (heaterOn ? HEATER_LOAD_W : 0.0) + (payloadOn ? PAYLOAD_LOAD_W : 0.0);
        batteryPct += (generated - load) * dtSec / 3600.0 / BATTERY_CAPACITY_WH * 100.0;
        batteryPct = Math.max(0.0, Math.min(100.0, batteryPct));

        double targetTemp = (sunlit ? 20.0 : -8.0) + (heaterOn ? 12.0 : 0.0) + (payloadOn ? 8.0 : 0.0);
        tempC += (targetTemp - tempC) * (1.0 - Math.exp(-dtSec / THERMAL_TIME_CONSTANT_SEC));

        // Ornstein-Uhlenbeck jitter: pointing noise grows as control authority drops.
        double sigma = switch (mode) {
            case MISSION -> 0.05;
            case NOMINAL -> 0.4;
            case SAFE -> 3.0;
        };
        double a = Math.exp(-dtSec / ATTITUDE_TIME_CONSTANT_SEC);
        double noise = sigma * Math.sqrt(1.0 - a * a);
        rollDeg = rollDeg * a + noise * rng.nextGaussian();
        pitchDeg = pitchDeg * a + noise * rng.nextGaussian();
        yawDeg = yawDeg * a + noise * rng.nextGaussian();

        if (batteryPct < SAFE_MODE_THRESHOLD_PCT && mode != SpacecraftMode.SAFE) {
            mode = SpacecraftMode.SAFE;
            payloadOn = false;
        }
    }

    ExecutionReport execute(TcFrame frame, Instant now) {
        try {
            String result = switch (frame.type()) {
                case PING -> "PONG (링크 정상)";
                case SET_MODE -> setMode(SpacecraftMode.valueOf(String.valueOf(frame.args().get("mode"))));
                case HEATER -> {
                    heaterOn = (Boolean) frame.args().get("on");
                    yield "히터 " + (heaterOn ? "켜짐" : "꺼짐");
                }
                case PAYLOAD_POWER -> setPayload((Boolean) frame.args().get("on"));
            };
            return new ExecutionReport(frame.seq(), true, result, now);
        } catch (RejectedException e) {
            return new ExecutionReport(frame.seq(), false, e.getMessage(), now);
        } catch (RuntimeException e) {
            return new ExecutionReport(frame.seq(), false, "잘못된 인자", now);
        }
    }

    TmSample sample(long seqCount, Instant t, boolean sunlit) {
        return new TmSample(seqCount, t, batteryPct, tempC, rollDeg, pitchDeg, yawDeg, mode, heaterOn,
                payloadOn, sunlit);
    }

    private String setMode(SpacecraftMode target) {
        if (target == SpacecraftMode.MISSION && batteryPct < MISSION_MIN_BATTERY_PCT) {
            throw new RejectedException(String.format("배터리 %.0f%%: MISSION 모드에는 %.0f%% 이상 필요",
                    batteryPct, MISSION_MIN_BATTERY_PCT));
        }
        if (target != SpacecraftMode.MISSION) {
            payloadOn = false;
        }
        mode = target;
        return target + " 모드로 전환";
    }

    private String setPayload(boolean on) {
        if (on && mode != SpacecraftMode.MISSION) {
            throw new RejectedException("탑재체는 MISSION 모드에서만 켤 수 있음 (현재 " + mode + ")");
        }
        payloadOn = on;
        return "탑재체 " + (on ? "켜짐" : "꺼짐");
    }

    private static final class RejectedException extends RuntimeException {
        RejectedException(String message) {
            super(message);
        }
    }
}
