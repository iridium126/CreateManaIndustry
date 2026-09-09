package com.iridium126.createmanaindustry.dimension.gen;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandLayout.Island;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Datapack terrain in local island coordinates, shared by cubes and LOD. */
public final class AllvrIslandFieldGenerator {
    private final ServerLevel level;
    private final AllvrTerrainSource terrain;
    private final AllvrIslandLayout layout;

    public AllvrIslandFieldGenerator(ServerLevel level) {
        this.level = level;
        this.terrain = new AllvrTerrainSource(level);
        var settings = terrain.settings;
        this.layout = new AllvrIslandLayout(level.getSeed(), settings.noiseSettings().minY(),
            settings.noiseSettings().height(), settings.seaLevel());
    }

    public Island[] islandsForBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        return layout.islandsForBox(x0, y0, z0, x1, y1, z1);
    }

    /** Cheap geometry-only ticket filter used before queuing background work. */
    public boolean intersectsIsland(int cubeX, int cubeY, int cubeZ) {
        int x = com.iridium126.createmanaindustry.dimension.cube.AllvrCoords.cubeToMinBlock(cubeX);
        int y = com.iridium126.createmanaindustry.dimension.cube.AllvrCoords.cubeToMinBlock(cubeY);
        int z = com.iridium126.createmanaindustry.dimension.cube.AllvrCoords.cubeToMinBlock(cubeZ);
        return islandsForBox(x, y, z, x + 32, y + 32, z + 32).length != 0;
    }

    public Holder<Biome> biome(int qx, int qy, int qz) {
        int x = qx * 4, y = qy * 4, z = qz * 4;
        Island[] candidates = islandsForBox(x, y, z, x + 1, y + 1, z + 1);
        Island island = candidates.length == 0 ? layout.nearest(x, y, z) : candidates[0];
        return terrain.biome(x + island.sourceOffsetX(), y - island.offsetY(), z + island.sourceOffsetZ());
    }

    public void generate(AllvrCube cube) {
        int x0 = cube.getPos().minBlockX(), y0 = cube.getPos().minBlockY(), z0 = cube.getPos().minBlockZ();
        // Island cells are sparse (the XZ spacing is several chunks). Resolve
        // the bounds before touching the 512 biome cells: the vast majority of
        // transport cubes are void and can stay at their default palette.
        Island[] islands = islandsForBox(x0, y0, z0, x0 + 32, y0 + 32, z0 + 32);
        if (islands.length == 0) return;
        for (int sy = 0; sy < 2; sy++) for (int sz = 0; sz < 2; sz++) for (int sx = 0; sx < 2; sx++) {
            int sectionX = x0 + sx * 16, sectionY = y0 + sy * 16, sectionZ = z0 + sz * 16;
            boolean intersects = false;
            for (Island island : islands) {
                if (island.intersects(sectionX, sectionY, sectionZ, sectionX + 16, sectionY + 16, sectionZ + 16)) {
                    intersects = true;
                    break;
                }
            }
            if (!intersects) continue;
            cube.getSections()[AllvrCube.sliceIndex(sx, sy, sz)].fillBiomesFromNoise(
                (qx, qy, qz, sampler) -> biome(qx, qy, qz), terrain.random.sampler(),
                (x0 >> 2) + sx * 4, (y0 >> 2) + sy * 4, (z0 >> 2) + sz * 4);
        }
        BlockPos.MutableBlockPos world = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos source = new BlockPos.MutableBlockPos();
        // A 32x32 cube touches at most four source chunks per island. Keep the
        // resolved columns local to this pass so the shared terrain cache is
        // not queried once for every block column.
        java.util.Map<Long, AllvrTerrainSource.Column> sourceColumns = new java.util.HashMap<>();
        for (Island island : islands) for (int z = z0; z < z0 + 32; z++) for (int x = x0; x < x0 + 32; x++) {
            double bottom = island.bottom(x, z);
            if (bottom >= y0 + 32) continue;
            int sx = x + island.sourceOffsetX(), sz = z + island.sourceOffsetZ();
            long sourceKey = net.minecraft.world.level.ChunkPos.asLong(sx >> 4, sz >> 4);
            var column = sourceColumns.computeIfAbsent(sourceKey, key -> terrain.column(sx >> 4, sz >> 4));
            int from = Math.max(y0, Math.max(island.minY(), (int) Math.ceil(bottom)));
            int to = Math.min(y0 + 32, island.maxY());
            for (int y = from; y < to; y++) {
                int sourceY = y - island.offsetY();
                BlockState state = column.block(sx, sourceY, sz);
                if (state.isAir()) continue;
                var section = cube.getSections()[AllvrCube.sliceIndex((x - x0) >> 4, (y - y0) >> 4, (z - z0) >> 4)];
                section.setBlockState(x & 15, y & 15, z & 15, state, false);
                if (state.hasBlockEntity()) {
                    world.set(x, y, z);
                    CompoundTag nbt = column.blockEntities().get(source.set(sx, sourceY, sz));
                    BlockEntity entity = null;
                    if (nbt != null && !"DUMMY".equals(nbt.getString("id"))) {
                        nbt = nbt.copy();
                        nbt.putInt("x", x); nbt.putInt("y", y); nbt.putInt("z", z);
                        entity = BlockEntity.loadStatic(world.immutable(), state, nbt, level.registryAccess());
                    }
                    if (entity != null) cube.installBlockEntity(entity);
                    else cube.updateBlockEntity(level, world.immutable(), state);
                }
            }
        }
    }

    /** LOD shares the same cached terrain columns and deterministic feature budget. */
    public BlockState evaluate(int x, int y, int z, Island[] islands) {
        for (Island island : islands) {
            if (!island.contains(x, y, z)) continue;
            int sx = x + island.sourceOffsetX(), sz = z + island.sourceOffsetZ();
            BlockState state = terrain.column(sx >> 4, sz >> 4).block(sx, y - island.offsetY(), sz);
            if (!state.isAir()) return state;
        }
        return null;
    }

    /** Resolve caches once per XZ sample, not once per voxel in the LOD hot loop. */
    public java.util.function.IntFunction<BlockState> columnSampler(int x, int z, int minY, int maxY, Island[] islands) {
        record Slice(Island island, AllvrTerrainSource.Column column, double bottom, int x, int z) {}
        java.util.List<Slice> slices = new java.util.ArrayList<>();
        for (Island island : islands) {
            double bottom = island.bottom(x, z);
            if (bottom >= maxY || island.maxY() <= minY || island.minY() >= maxY) continue;
            int sx = x + island.sourceOffsetX(), sz = z + island.sourceOffsetZ();
            slices.add(new Slice(island, terrain.column(sx >> 4, sz >> 4), bottom, sx, sz));
        }
        return y -> {
            for (Slice slice : slices) {
                if (y < slice.bottom || y < slice.island.minY() || y >= slice.island.maxY()) continue;
                BlockState state = slice.column.block(slice.x, y - slice.island.offsetY(), slice.z);
                if (!state.isAir()) return state;
            }
            return null;
        };
    }
}
