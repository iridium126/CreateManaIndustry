package com.iridium126.createmanaindustry.dimension.gen;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandLayout.Island;
import com.iridium126.createmanaindustry.dimension.gen.worldtree.EpicRedwoodGenerator;
import net.minecraft.core.BlockPos;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Datapack terrain in local island coordinates used by cube generation. */
public final class AllvrIslandFieldGenerator {
    private final ServerLevel level;
    private final AllvrTerrainSource terrain;
    private final AllvrIslandLayout layout;
    private final EpicRedwoodGenerator epicRedwood;

    public AllvrIslandFieldGenerator(ServerLevel level) {
        this.level = level;
        this.epicRedwood = new EpicRedwoodGenerator(level.getSeed());
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
        if (AllvrDimensionLimits.isVanillaCube(cubeY)) return false;
        if (cubeY < (AllvrDimensionLimits.VANILLA_MIN_Y >> 5)) return true;
        int x = com.iridium126.createmanaindustry.dimension.cube.AllvrCoords.cubeToMinBlock(cubeX);
        int y = com.iridium126.createmanaindustry.dimension.cube.AllvrCoords.cubeToMinBlock(cubeY);
        int z = com.iridium126.createmanaindustry.dimension.cube.AllvrCoords.cubeToMinBlock(cubeZ);
        return epicRedwood.intersectsCell(x, y, z)
            || islandsForBox(x, y, z, x + 32, y + 32, z + 32).length != 0;
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
        if (generateLowerBand(cube)) return;
        Island[] islands = islandsForBox(x0, y0, z0, x0 + 32, y0 + 32, z0 + 32);
        if (islands.length == 0) {
            epicRedwood.generate(cube);
            return;
        }
        Map<Long, AllvrTerrainSource.Column> sourceColumns = new HashMap<>();
        collectSourceColumns(islands, x0, z0, (sourceChunkX, sourceChunkZ) ->
            sourceColumns.computeIfAbsent(ChunkPos.asLong(sourceChunkX, sourceChunkZ),
                ignored -> terrain.column(sourceChunkX, sourceChunkZ)));
        fillCube(cube, islands, sourceColumns);
        epicRedwood.generate(cube);
    }

    /**
     * Background variant used by the cube ticket pipeline.  Each vanilla
     * source chunk owns its own asynchronous noise/surface/features chain;
     * only the final palette copy is performed after all source columns are
     * complete.  This mirrors ChunkStatus' fan-out/fan-in shape and avoids a
     * worker thread waiting on nested {@code fillFromNoise().join()} calls.
     */
    public CompletableFuture<Void> generateAsync(AllvrCube cube) {
        int x0 = cube.getPos().minBlockX(), y0 = cube.getPos().minBlockY(), z0 = cube.getPos().minBlockZ();
        if (generateLowerBand(cube)) return CompletableFuture.completedFuture(null);
        Island[] islands = islandsForBox(x0, y0, z0, x0 + 32, y0 + 32, z0 + 32);
        if (islands.length == 0) {
            return epicRedwood.intersectsCell(x0, y0, z0)
                ? CompletableFuture.runAsync(() -> epicRedwood.generate(cube), net.minecraft.Util.backgroundExecutor())
                : CompletableFuture.completedFuture(null);
        }
        Map<Long, CompletableFuture<AllvrTerrainSource.Column>> sourceFutures = new HashMap<>();
        collectSourceColumns(islands, x0, z0, (sourceChunkX, sourceChunkZ) ->
            sourceFutures.computeIfAbsent(ChunkPos.asLong(sourceChunkX, sourceChunkZ),
                ignored -> terrain.columnAsync(sourceChunkX, sourceChunkZ)));
        CompletableFuture<?>[] dependencies = sourceFutures.values().toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(dependencies).thenRun(() -> {
            Map<Long, AllvrTerrainSource.Column> sourceColumns = new HashMap<>(sourceFutures.size());
            sourceFutures.forEach((key, future) -> sourceColumns.put(key, future.join()));
            fillCube(cube, islands, sourceColumns);
            epicRedwood.generate(cube);
        });
    }

    private boolean generateLowerBand(AllvrCube cube) {
        int y = cube.getPos().minBlockY();
        if (AllvrDimensionLimits.isVanillaY(y)) {
            throw new IllegalArgumentException("Central band belongs to vanilla chunks: " + cube.getPos());
        }
        if (y >= AllvrDimensionLimits.VANILLA_MIN_Y) return false;
        // A single-value vanilla palette per section: no noise, features or terrain cache work.
        for (int i = 0; i < cube.getSections().length; i++) {
            var old = cube.getSections()[i];
            cube.getSections()[i] = new net.minecraft.world.level.chunk.LevelChunkSection(
                new net.minecraft.world.level.chunk.PalettedContainer<>(
                    net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY, Blocks.DEEPSLATE.defaultBlockState(),
                    net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES), old.getBiomes());
        }
        return true;
    }

    @FunctionalInterface
    private interface SourceColumnConsumer {
        void accept(int sourceChunkX, int sourceChunkZ);
    }

    private void collectSourceColumns(Island[] islands, int x0, int z0, SourceColumnConsumer consumer) {
        for (Island island : islands) {
            int minChunkX = Math.floorDiv(x0 + island.sourceOffsetX(), 16);
            int maxChunkX = Math.floorDiv(x0 + 31 + island.sourceOffsetX(), 16);
            int minChunkZ = Math.floorDiv(z0 + island.sourceOffsetZ(), 16);
            int maxChunkZ = Math.floorDiv(z0 + 31 + island.sourceOffsetZ(), 16);
            for (int sourceChunkZ = minChunkZ; sourceChunkZ <= maxChunkZ; sourceChunkZ++)
            for (int sourceChunkX = minChunkX; sourceChunkX <= maxChunkX; sourceChunkX++) {
                consumer.accept(sourceChunkX, sourceChunkZ);
            }
        }
    }

    private void fillCube(AllvrCube cube, Island[] islands, Map<Long, AllvrTerrainSource.Column> sourceColumns) {
        int x0 = cube.getPos().minBlockX(), y0 = cube.getPos().minBlockY(), z0 = cube.getPos().minBlockZ();
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
        for (Island island : islands) for (int z = z0; z < z0 + 32; z++) for (int x = x0; x < x0 + 32; x++) {
            double bottom = island.bottom(x, z);
            if (bottom >= y0 + 32) continue;
            int sx = x + island.sourceOffsetX(), sz = z + island.sourceOffsetZ();
            long sourceKey = net.minecraft.world.level.ChunkPos.asLong(sx >> 4, sz >> 4);
            var column = sourceColumns.get(sourceKey);
            if (column == null) continue;
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

}
