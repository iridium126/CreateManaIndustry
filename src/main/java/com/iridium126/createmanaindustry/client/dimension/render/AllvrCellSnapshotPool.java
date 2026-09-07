package com.iridium126.createmanaindustry.client.dimension.render;

import net.minecraft.world.level.block.state.BlockState;

/** Per-worker reusable 18³ snapshot storage; no array is shared across jobs. */
public final class AllvrCellSnapshotPool {

    public record Snapshot(BlockState[] states, byte[] occludes) {}

    private static final ThreadLocal<Snapshot> LOCAL = ThreadLocal.withInitial(() ->
        new Snapshot(new BlockState[AllvrCellMesher.PADDED * AllvrCellMesher.PADDED
            * AllvrCellMesher.PADDED],
            new byte[AllvrCellMesher.PADDED * AllvrCellMesher.PADDED * AllvrCellMesher.PADDED]));

    public static Snapshot acquire() {
        return LOCAL.get();
    }

    private AllvrCellSnapshotPool() {}
}
