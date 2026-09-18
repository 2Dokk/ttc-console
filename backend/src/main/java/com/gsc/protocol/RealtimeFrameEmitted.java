package com.gsc.protocol;

/** Published by the spacecraft when it transmits a real-time frame; the RF link routes it. */
public record RealtimeFrameEmitted(TmFrame frame) {
}
