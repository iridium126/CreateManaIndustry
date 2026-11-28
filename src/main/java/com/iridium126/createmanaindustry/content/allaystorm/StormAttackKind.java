package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;

/** Drawable storm attacks and the config/range rules they share. */
public enum StormAttackKind {
    COLUMNS, RING, HELIX, DOME;

    private static final StormAttackKind[] ALL = values();

    public static int count() {
        return ALL.length;
    }

    public static StormAttackKind at(int slot) {
        return ALL[Math.floorMod(slot, ALL.length)];
    }

    /** Ambient attacks draw immediately; major attacks wait for one sampled interval. */
    public boolean immediateFirstDraw() {
        return this == COLUMNS || this == RING;
    }

    public int minMembers() {
        return switch (this) {
            case COLUMNS -> 64;
            case RING -> StormRingGeometry.MEMBERS;
            case HELIX -> 16384;
            case DOME -> AllayDome.MIN_MEMBERS;
        };
    }

    public double minIntervalSeconds() {
        return switch (this) {
            case COLUMNS -> ServerConfig.stormStarfallInterval;
            case RING -> ServerConfig.stormRingInterval;
            case HELIX -> ServerConfig.stormHelixIntervalMin;
            case DOME -> ServerConfig.stormDomeIntervalMin;
        };
    }

    public double maxIntervalSeconds() {
        return switch (this) {
            case COLUMNS -> ServerConfig.stormStarfallInterval;
            case RING -> ServerConfig.stormRingInterval;
            case HELIX -> ServerConfig.stormHelixIntervalMax;
            case DOME -> ServerConfig.stormDomeIntervalMax;
        };
    }

    public double chance() {
        return switch (this) {
            case COLUMNS -> ServerConfig.stormStarfallChance;
            case RING -> ServerConfig.stormRingChance;
            case HELIX -> ServerConfig.stormHelixChance;
            case DOME -> ServerConfig.stormDomeChance;
        };
    }

    /** Columns keep their wider vortex envelope; every other attack uses the wave target range. */
    public double range(int generated) {
        return this == COLUMNS ? AllayStormData.vortexRadius(generated) : ServerConfig.stormWaveRange;
    }

    public boolean withinRange(double x, double z, double cx, double cz, int generated) {
        return Math.hypot(x - cx, z - cz) <= range(generated);
    }

    public int wireKind() {
        return switch (this) {
            case HELIX -> StormAttack.HELIX;
            case DOME -> StormAttack.DOME;
            default -> StormAttack.NONE;
        };
    }

    }