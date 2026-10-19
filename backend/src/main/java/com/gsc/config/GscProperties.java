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

    /**
     * Simulated RF channel. Rates are probabilities in [0, 1].
     * {@code seed} fixes the random sequence so a run can be reproduced; 0 means a fresh sequence each run.
     */
    public record Link(double uplinkLoss, double downlinkLoss, double bitErrorRate, long oneWayDelayMs,
                       long jitterMs, long seed, Burst burst) {

        /** Gilbert-Elliott burst loss: the channel walks between a good and a bad state. */
        public record Burst(boolean enabled, double goodToBad, double badToGood, double lossInBadState) {}
    }

    /** Ground-side frame operation procedure (simplified CCSDS COP-1 FOP). */
    public record Fop(int windowSize, long ackTimeoutMs, int transmissionLimit) {}

    public record Spacecraft(int recorderCapacity, long recorderIntervalSec, int playbackChunk) {}
}
