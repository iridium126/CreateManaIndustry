package com.iridium126.createmanaindustry.content.allaystorm;

/** Stable wire kinds, event codes and shared authority rules. */
public final class StormAttack {
    /** Placeholder for internal abort packets; never encoded as an attack kind. */
    public static final int NONE = 0, DOME = 1, HELIX = 2;
    public static final int EVENT_START = 0, EVENT_UPDATE = 1, EVENT_LOCK = 2, EVENT_ABORT = 3;

    private StormAttack() {}

    /** Helix re-aims at its target until lock; a dome footprint is already fixed. */
    public static boolean requiresTarget(int kind, boolean locked) {
        return kind == HELIX && !locked;
    }

    /** Regional attacks run the shared gather handshake before their lock. */
    public static boolean isMajor(int kind) {
        return kind == DOME || kind == HELIX;
    }
}