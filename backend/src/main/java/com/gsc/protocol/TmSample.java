package com.gsc.protocol;

import com.gsc.spacecraft.SpacecraftMode;
import java.time.Instant;

/** One housekeeping telemetry sample, stamped with spacecraft (mission) time. */
public record TmSample(
        long seqCount,
        Instant scTime,
        double batteryPct,
        double tempC,
        double rollDeg,
        double pitchDeg,
        double yawDeg,
        SpacecraftMode mode,
        boolean heaterOn,
        boolean payloadOn,
        boolean sunlit) {
}
