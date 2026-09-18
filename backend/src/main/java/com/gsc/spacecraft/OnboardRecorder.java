package com.gsc.spacecraft;

import com.gsc.protocol.TmSample;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Solid-state recorder: keeps housekeeping taken out of contact, oldest dropped when full. */
public class OnboardRecorder {

    private final int capacity;
    private final Deque<TmSample> stored = new ArrayDeque<>();
    private long overwritten;

    public OnboardRecorder(int capacity) {
        this.capacity = capacity;
    }

    public void record(TmSample sample) {
        if (stored.size() == capacity) {
            stored.removeFirst();
            overwritten++;
        }
        stored.addLast(sample);
    }

    /** Removes and returns up to {@code max} of the oldest samples. */
    public List<TmSample> drain(int max) {
        List<TmSample> out = new ArrayList<>(Math.min(max, stored.size()));
        while (out.size() < max && !stored.isEmpty()) {
            out.add(stored.removeFirst());
        }
        return out;
    }

    public int size() {
        return stored.size();
    }

    public boolean isEmpty() {
        return stored.isEmpty();
    }

    public long overwritten() {
        return overwritten;
    }
}
