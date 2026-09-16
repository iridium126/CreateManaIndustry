package com.iridium126.createmanaindustry.dimension.gen.worldtree;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import net.minecraft.core.BlockPos;
import com.iridium126.createmanaindustry.worldgen.markov.EpicRedwoodModel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Writes only newly generated storage units; never called by chunk/cube load events. */
public final class WorldTreeGenerator {
    private final EpicRedwoodTree tree;
    private final WorldTreePalette palette;
    private static final int MIN_XZ = -EpicRedwoodModel.WIDTH / 2, BASE_Y = 64;

    public WorldTreeGenerator(long seed) {
        tree = EpicRedwoodTree.forSeed(seed);
        palette = WorldTreePalette.bundled();
    }

    public boolean intersectsCell(int x, int y, int z) {
        if (!intersects(x, y, z, 32)) return false;
        var ready = tree.readyVolume();
        return ready == null || ready.intersects(x - MIN_XZ, z - MIN_XZ, y - BASE_Y, 32);
    }

    // Ticket filtering must never trigger expensive generation on the server thread.
    private static boolean intersects(int x, int y, int z, int size) {
        return x < MIN_XZ + EpicRedwoodModel.WIDTH && (long)x + size > MIN_XZ
            && z < MIN_XZ + EpicRedwoodModel.WIDTH && (long)z + size > MIN_XZ
            && y < BASE_Y + EpicRedwoodModel.HEIGHT && (long)y + size > BASE_Y;
    }

    public BlockState sample(int x, int y, int z) {
        if (!intersects(x, y, z, 1)) return null;
        return palette.state(tree.volume().get(x - MIN_XZ, z - MIN_XZ, y - BASE_Y));
    }

    public void generate(ChunkAccess chunk) {
        int x = chunk.getPos().getMinBlockX(), z = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y += 16)
            section(x, y, z, (px, py, pz, state) -> {
                pos.set(px, py, pz);
                chunk.removeBlockEntity(pos);
                // ChunkAccess maintains the generation heightmaps through its normal write path.
                chunk.setBlockState(pos, state, false);
            });
    }

    public void generate(AllvrCube cube) {
        int x = cube.getPos().minBlockX(), y = cube.getPos().minBlockY(), z = cube.getPos().minBlockZ();
        if (!intersectsCell(x, y, z)) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int sy = 0; sy < 2; sy++) for (int sz = 0; sz < 2; sz++) for (int sx = 0; sx < 2; sx++) {
            var target = cube.getSections()[AllvrCube.sliceIndex(sx, sy, sz)];
            section(x + sx * 16, y + sy * 16, z + sz * 16, (px, py, pz, state) -> {
                BlockState old = target.setBlockState(px & 15, py & 15, pz & 15, state, false);
                if (old.hasBlockEntity()) cube.removeBlockEntity(pos.set(px, py, pz));
            });
        }
    }

    private void section(int x0, int y0, int z0, Writer writer) {
        if (!intersects(x0, y0, z0, 16)) return;
        var volume = tree.volume();
        if (!volume.intersects(x0 - MIN_XZ, z0 - MIN_XZ, y0 - BASE_Y, 16)) return;
        for (int y = y0; y < y0 + 16; y++) for (int z = z0; z < z0 + 16; z++) for (int x = x0; x < x0 + 16; x++) {
            BlockState state = palette.state(volume.get(x - MIN_XZ, z - MIN_XZ, y - BASE_Y));
            if (state != null) writer.set(x, y, z, state);
        }
    }

    @FunctionalInterface private interface Writer { void set(int x, int y, int z, BlockState state); }
}
