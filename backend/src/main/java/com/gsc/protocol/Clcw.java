package com.gsc.protocol;

/**
 * Command Link Control Word, carried in every downlink frame. {@code vr} is V(R), the next
 * uplink sequence number the spacecraft expects, so it acknowledges every frame below it.
 * {@code retransmit} is set when the spacecraft has seen a gap in the uplink sequence.
 */
public record Clcw(long vr, boolean retransmit) {
}
