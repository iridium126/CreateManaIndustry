package com.iridium126.createmanaindustry.dimension.storage;

import net.minecraft.nbt.CompoundTag;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * Immutable hand-off from the server thread to the IO worker (plan §7.1):
 * the cube was serialized into {@link #tag} at mutation version
 * {@link #version}; the worker may only ever see this — never the live
 * {@code AllvrCube}, its sections or its block entities (plan §8.2).
 *
 * @param pos     cube identity (also the storage routing key)
 * @param version {@code AllvrCube#mutationVersion()} captured at snapshot time
 * @param tag     complete, self-contained cube NBT (schema in plan §6)
 */
public record AllvrCubeSnapshot(AllvrCubePos pos, long version, CompoundTag tag) {
}
