package com.iridium126.createmanaindustry.dimension.mesh;

import net.minecraft.world.level.block.state.BlockState;

/**
 * BlockState → packed quad stateId mapping (doc §13 4c). One method: the id
 * written into the quad word's bits 28..43, or 0 for "not renderable" (the
 * mask cell stays empty and no quad is emitted).
 * <p>
 * The live implementation is the server-side LOD codec: the vanilla global
 * state id ({@code Block.getId}), gated on canOcclude + full-block collision.
 * The client-side terrain path is Sodium and does not use this codec.
 */
@FunctionalInterface
public interface AllvrMeshCodec {

    /** Packed id for the quad word (bits 28..43), 0 = not renderable. */
    int packId(BlockState state);
}
