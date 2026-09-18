package com.gsc.protocol;

import java.time.Instant;

/** Onboard result of executing an accepted telecommand. */
public record ExecutionReport(long seq, boolean success, String message, Instant executedAt) {
}
