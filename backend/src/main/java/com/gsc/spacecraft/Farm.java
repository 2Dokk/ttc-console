package com.gsc.spacecraft;

import com.gsc.protocol.Clcw;

/**
 * Spacecraft-side frame acceptance (simplified CCSDS FARM-1). Accepts only the frame it expects
 * next, V(R), so commands execute exactly once and in order no matter how the ground retransmits.
 */
public class Farm {

    public enum Verdict {
        /** seq == V(R): accepted, V(R) advances. */
        ACCEPTED,
        /** seq < V(R): already accepted earlier (its ACK was lost); discard, CLCW re-acks it. */
        DUPLICATE,
        /** seq > V(R): an earlier frame was lost; discard and ask for retransmission. */
        OUT_OF_SEQUENCE
    }

    private long vr;
    private boolean retransmit;

    public Farm(long initialVr) {
        this.vr = initialVr;
    }

    public synchronized Verdict receive(long seq) {
        if (seq == vr) {
            vr++;
            retransmit = false;
            return Verdict.ACCEPTED;
        }
        if (seq < vr) {
            return Verdict.DUPLICATE;
        }
        retransmit = true;
        return Verdict.OUT_OF_SEQUENCE;
    }

    public synchronized Clcw clcw() {
        return new Clcw(vr, retransmit);
    }

    public synchronized void reset(long newVr) {
        vr = newVr;
        retransmit = false;
    }
}
