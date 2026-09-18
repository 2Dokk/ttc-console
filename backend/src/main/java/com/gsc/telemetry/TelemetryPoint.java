package com.gsc.telemetry;

import java.time.Instant;

/**
 * Stored/broadcast telemetry. {@code recordedAt} is when the spacecraft took the sample,
 * {@code receivedAt} when the ground got it; for PLAYBACK the two can be hours apart.
 */
public record TelemetryPoint(
        long seqCount,
        String source,
        Instant recordedAt,
        Instant receivedAt,
        double batteryPct,
        double tempC,
        double rollDeg,
        double pitchDeg,
        double yawDeg,
        String mode,
        boolean heaterOn,
        boolean payloadOn,
        boolean sunlit) {

    public static final String REALTIME = "REALTIME";
    public static final String PLAYBACK = "PLAYBACK";
}
