package com.iridium126.createmanaindustry.dimension.light;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import com.iridium126.createmanaindustry.dimension.cube.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.lighting.*;

/** Immutable input published on the game thread; native light workers never read a mutable cube map. */
public final class AllvrNativeBlockHalo {
    private static final ConcurrentHashMap<Level, ConcurrentHashMap<AllvrSectionCoord, LevelChunkSection>> INPUTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Level, java.util.Map<Long, AllvrCube>> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Level, ConcurrentHashMap<AllvrSectionCoord, LevelChunkSection>> CONSUMED = new ConcurrentHashMap<>();
    private AllvrNativeBlockHalo() {}
    public static void flush(Level level) {
        var pending = PENDING.remove(level);
        if (pending != null) pending.values().forEach(cube -> publish(level, cube, false));
    }
    public static void publish(Level level, AllvrCube cube, boolean remove) {
        if (!com.iridium126.createmanaindustry.dimension.AllvrDimensions.isAllay(level)) return;
        int y = cube.getPos().minBlockY();
        if (y != 384 && y != -160) return;
        if (remove) { var pending = PENDING.get(level); if (pending != null) pending.remove(cube.getPos().asLong()); }
        var inputs = INPUTS.computeIfAbsent(level, key -> new ConcurrentHashMap<>());
        int sectionY = y == 384 ? 24 : -9;
        for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            var coord = new AllvrSectionCoord((cube.getPos().getX() << 1) + x, sectionY, (cube.getPos().getZ() << 1) + z);
            if (remove) {
                inputs.remove(coord);
                if (!level.getChunkSource().hasChunk(coord.x(), coord.z())) {
                    var consumed = CONSUMED.get(level);
                    if (consumed != null) consumed.remove(coord);
                }
            } else {
                var source = cube.getSections()[AllvrCube.sliceIndex(x, sectionY & 1, z)];
                @SuppressWarnings("unchecked") var biomes = ((PalettedContainer<Holder<net.minecraft.world.level.biome.Biome>>) source.getBiomes()).copy();
                inputs.put(coord, new LevelChunkSection(source.getStates().copy(), biomes));
            }
            // ThreadedLevelLightEngine serializes this task with native chunk lighting.
            level.getLightEngine().propagateLightSources(new ChunkPos(coord.x(), coord.z()));
        }
    }
    public static void changed(Level level, AllvrCube cube, BlockPos pos) {
        if (pos.getY() >= 384 && pos.getY() < 400 || pos.getY() >= -144 && pos.getY() < -128)
            PENDING.computeIfAbsent(level, key -> new java.util.HashMap<>()).put(cube.getPos().asLong(), cube);
    }
    public static BlockState state(Level level, BlockPos pos) {
        var inputs = INPUTS.get(level);
        var section = inputs == null ? null : inputs.get(AllvrSectionCoord.of(pos));
        return section == null ? Blocks.VOID_AIR.defaultBlockState() : section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }
    public static void sources(Level level, ChunkPos column, BlockLightEngine engine, BiConsumer<BlockPos, BlockState> emit) {
        var inputs = INPUTS.get(level);
        var consumed = CONSUMED.get(level);
        if (consumed == null && inputs != null) { consumed = new ConcurrentHashMap<>(); CONSUMED.put(level, consumed); }
        for (int y : new int[]{-9, 24}) {
            var coord = new AllvrSectionCoord(column.x, y, column.z);
            var section = inputs == null ? null : inputs.get(coord);
            var previous = consumed == null ? null : section == null ? consumed.remove(coord) : consumed.put(coord, section);
            engine.updateSectionStatus(coord.sectionPos(), section == null);
            // Reseeding only adds light. Changed occluders and removed emitters must also enter the decrease queue.
            if (previous != section) {
                var changedPos = new BlockPos.MutableBlockPos();
                for (int by = 0; by < 16; by++) for (int bz = 0; bz < 16; bz++) for (int bx = 0; bx < 16; bx++) {
                    var before = previous == null ? Blocks.AIR.defaultBlockState() : previous.getBlockState(bx, by, bz);
                    var after = section == null ? Blocks.AIR.defaultBlockState() : section.getBlockState(bx, by, bz);
                    if (before != after || after.hasDynamicLightEmission()) {
                        engine.checkBlock(changedPos.set((column.x << 4) + bx, (y << 4) + by, (column.z << 4) + bz));
                    }
                }
            }
            if (section == null) continue;
            if (!section.maybeHas(state -> state.hasDynamicLightEmission() || state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) > 0)) {
                if (inputs.get(coord) != section) consumed.remove(coord, section);
                continue;
            }
            var pos = new BlockPos.MutableBlockPos();
            for (int by = 0; by < 16; by++) for (int bz = 0; bz < 16; bz++) for (int bx = 0; bx < 16; bx++) {
                var state = section.getBlockState(bx, by, bz);
                pos.set((column.x << 4) + bx, (y << 4) + by, (column.z << 4) + bz);
                if (state.getLightEmission(level, pos) != 0) emit.accept(pos, state);
            }
            if (inputs.get(coord) != section) consumed.remove(coord, section);
        }
    }
    public static void nativeColumnUnloaded(Level level, int x, int z) {
        var consumed = CONSUMED.get(level);
        if (consumed != null) {
            consumed.remove(new AllvrSectionCoord(x, -9, z));
            consumed.remove(new AllvrSectionCoord(x, 24, z));
        }
    }
    public static void clear(Level level) { INPUTS.remove(level); PENDING.remove(level); CONSUMED.remove(level); }
}
