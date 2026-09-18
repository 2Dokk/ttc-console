package com.gsc.command;

import java.time.Instant;
import java.util.Map;

/** All timestamps are mission time. {@code seq} is assigned on first transmission, never at creation. */
public record Command(
        long id,
        long satelliteId,
        CommandType type,
        Map<String, Object> args,
        CommandStatus status,
        Long seq,
        int attempts,
        Instant createdAt,
        Instant firstSentAt,
        Instant lastSentAt,
        Instant ackedAt,
        Instant executedAt,
        Instant expiresAt,
        String resultMessage) {
}
