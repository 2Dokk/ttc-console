package com.gsc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gsc")
public record GscProperties(
        String orekitDataPath,
        long satelliteId,
        long stationId,
        Tle tle,
        Link link,
        Fop fop,
        Spacecraft spacecraft) {

    /** TLE refresh from Celestrak; the DB copy is used as a fallback when offline. */
    public record Tle(boolean refreshOnStartup, String sourceUrl) {}

    /** Simulated RF channel. Loss rates are probabilities in [0, 1]. */
    public record Link(double uplinkLoss, double downlinkLoss, long oneWayDelayMs) {}

    /** Ground-side frame operation procedure (simplified CCSDS COP-1 FOP). */
    public record Fop(int windowSize, long ackTimeoutMs) {}

    public record Spacecraft(int recorderCapacity, long recorderIntervalSec, int playbackChunk) {}
}
