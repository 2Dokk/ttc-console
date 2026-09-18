package com.gsc.orbit;

import java.time.Instant;

/** One visibility window: AOS (rise above the elevation mask) to LOS (set below it). */
public record Pass(Instant aos, Instant los, Instant tca, double maxElevationDeg,
                   double aosAzimuthDeg, double losAzimuthDeg) {

    public boolean contains(Instant t) {
        return !t.isBefore(aos) && t.isBefore(los);
    }
}
