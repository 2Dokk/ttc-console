package com.gsc.protocol;

import java.util.List;

/** A chunk of recorder data dumped during a pass. {@code remaining} is what is still stored onboard. */
public record PlaybackBatch(List<TmSample> samples, List<ExecutionReport> recentExecutions, int remaining) {
}
