package com.iridium126.createmanaindustry.dimension.cube;

/**
 * Duck interface injected onto {@link ServerLevel} by
 * {@code AllvrServerLevelMixin} — gives the Level mixins a path to the
 * dimension's {@link AllvrCubeMap} without touching the vanilla chunk
 * pipeline. The mixin-backed implementation returns {@code null} on every
 * other dimension, so callers can null-check without a dimension comparison.
 */
public interface AllvrServerLevelDuck {

    /** Lazily created cube registry for this level; null off the allay dimension. */
    AllvrCubeMap allvr$getCubeMap();

    /**
     * The cube map <b>without</b> lazily creating it — the save/close wiring
     * uses this so a never-visited allay level is not forced to build its
     * whole storage/LOD subsystem just to be saved or closed (plan §7.5).
     */
    AllvrCubeMap allvr$peekCubeMap();

    /** Lazily created LOD pipeline for this level (created with the cube map);
     *  null off the allay dimension. */
    com.iridium126.createmanaindustry.dimension.lod.AllvrLodMap allvr$getLodMap();
}
