package com.gsc.tracking;

import com.gsc.orbit.LookAngles;
import com.gsc.orbit.Pass;
import com.gsc.orbit.SubPoint;
import java.time.Instant;

public record TrackingSnapshot(
        Instant missionTime,
        double speed,
        SubPoint position,
        LookAngles look,
        boolean visible,
        boolean sunlit,
        Pass currentPass,
        Pass nextPass) {
}
