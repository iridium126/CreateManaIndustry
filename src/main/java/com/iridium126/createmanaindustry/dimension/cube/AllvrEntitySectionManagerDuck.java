package com.iridium126.createmanaindustry.dimension.cube;

/**
 * Server-side bridge for the vanilla entity section manager.  Vanilla tracks
 * entity visibility by X/Z chunk only, while Allay cubes also need their
 * independent Y residency to decide whether an entity section is accessible
 * and ticking.
 */
public interface AllvrEntitySectionManagerDuck {

    /** Synchronizes cube entity sections with the currently loaded cubes. */
    void allvr$syncCubeEntityTicking(AllvrCubeMap map);
}
