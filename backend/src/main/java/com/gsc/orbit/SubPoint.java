package com.gsc.orbit;

import java.time.Instant;

/** Sub-satellite point on the WGS84 ellipsoid. */
public record SubPoint(Instant time, double latDeg, double lonDeg, double altKm) {
}
