package com.gsc.protocol;

import java.util.List;

/**
 * Real-time downlink frame. The CLCW and the last few execution reports ride along in every
 * frame, so losing one frame never loses an acknowledgement for good.
 */
public record TmFrame(TmSample sample, Clcw clcw, List<ExecutionReport> recentExecutions) {
}
