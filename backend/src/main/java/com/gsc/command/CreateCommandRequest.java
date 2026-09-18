package com.gsc.command;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record CreateCommandRequest(
        Long satelliteId,
        @NotNull CommandType type,
        Map<String, Object> args,
        @Min(1) @Max(7 * 24 * 60) Integer expiresInMinutes) {
}
