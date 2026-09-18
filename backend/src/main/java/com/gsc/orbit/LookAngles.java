package com.gsc.orbit;

/** Satellite direction as seen from the ground station antenna. */
public record LookAngles(double azimuthDeg, double elevationDeg, double rangeKm) {
}
