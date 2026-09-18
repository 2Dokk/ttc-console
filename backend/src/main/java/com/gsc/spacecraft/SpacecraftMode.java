package com.gsc.spacecraft;

public enum SpacecraftMode {
    /** Normal housekeeping, payload off. */
    NOMINAL,
    /** Payload operations allowed. */
    MISSION,
    /** Minimal power; entered on command or autonomously on low battery. */
    SAFE
}
