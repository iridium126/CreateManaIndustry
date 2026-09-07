package com.iridium126.createmanaindustry.dimension.lod;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.iridium126.createmanaindustry.dimension.mesh.AllvrMeshLight;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMesher;

/**
 * One LOD node's immutable 32³ voxel section (voxy integration plan §6.1) —
 * the new stable network boundary replacing the meshed quad stream for
 * section-capable clients: palette + per-cell palette index + per-cell
 * light, all in ABSOLUTE coordinates.
 * <p>
 * Layout contract (deliberately shared with Voxy's {@code WorldSection}):
 * <ul>
 *   <li>cell index {@code (x,y,z) → (y<<10)|(z<<5)|x} — identical to the
 *       remote section's raw array order, so the client injects without
 *       reindexing;</li>
 *   <li>{@code palette[0]} is always air — an air cell is palette index 0
 *       and only its light nibbles are meaningful;</li>
 *   <li>{@code light[i]} packs sky in the low nibble, block light in the
 *       high nibble — the same byte layout Voxy's mapper composes.</li>
 * </ul>
 * Non-occluding states are already folded to air by the server (the LOD
 * material policy is full-occluder-only in v1), so {@code palette} only
 * holds renderable states plus air.
 */
public final class AllvrLodSectionData {

    public static final int CELLS = 32 * 32 * 32;
    /** Palette size cap for the wire format (16-bit indices max). */
    public static final int MAX_PALETTE = 1 << 15;

    public final int level;
    /** Absolute {@code (level, cell)} key — the server never writes virtual Y. */
    public final long cellLong;
    /** Server-side node generation at build time (edit counter). */
    public final long generation;
    public final BlockState[] palette;
    public final int[] indices;
    public final byte[] light;

    AllvrLodSectionData(int level, long cellLong, long generation,
                        BlockState[] palette, int[] indices, byte[] light) {
        this.level = level;
        this.cellLong = cellLong;
        this.generation = generation;
        this.palette = palette;
        this.indices = indices;
        this.light = light;
    }

    /** Cell index (0..32767) for local x/y/z in 0..31. */
    public static int cellIndex(int x, int y, int z) {
        return (y << 10) | (z << 5) | x;
    }

    public int level() {
        return this.level;
    }

    public long cellLong() {
        return this.cellLong;
    }

    public long generation() {
        return this.generation;
    }

    public BlockState[] palette() {
        return this.palette;
    }

    public int[] indices() {
        return this.indices;
    }

    public byte[] light() {
        return this.light;
    }

    /**
     * Builds the section from a filled 34³ padded snapshot (plan §6.2): the
     * 32³ core cells are lifted out, non-occluders folded to air, the palette
     * built with air first, and light sampled per cell from the snapshot's
     * own light sampler. Returns {@code null} for an all-air node — the
     * caller stores/sends a "no payload" marker instead of a full section.
     */
    public static AllvrLodSectionData build(int level, long cellLong, long generation,
                                            BlockState[] paddedStates, byte[] paddedOccludes,
                                            AllvrMeshLight light) {
        AllvrLodPos pos = AllvrLodPos.fromCellLong(level, cellLong);
        long originY = pos.minBlockY();
        int[] indices = new int[CELLS];
        List<BlockState> paletteList = new ArrayList<>(16);
        paletteList.add(Blocks.AIR.defaultBlockState()); // fixed palette slot 0
        Map<BlockState, Integer> paletteIds = new IdentityHashMap<>();
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int padded = AllvrMesher.paddedIndex(x, y, z);
                    if (paddedOccludes[padded] == 0) {
                        continue; // air — palette index 0 is implicit
                    }
                    BlockState state = paddedStates[padded];
                    int id = paletteIds.computeIfAbsent(state, s -> {
                        if (paletteList.size() >= MAX_PALETTE) {
                            throw new IllegalStateException("LOD palette overflow at " + pos);
                        }
                        paletteList.add(s);
                        return paletteList.size() - 1;
                    });
                    indices[cellIndex(x, y, z)] = id;
                }
            }
        }
        if (paletteList.size() == 1) {
            return null; // all air
        }
        byte[] lightBytes = new byte[CELLS];
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int sky = light.sky(x, z, originY + y) & 0xF;
                    int block = light.block(x, z, originY + y) & 0xF;
                    lightBytes[cellIndex(x, y, z)] = (byte) (sky | (block << 4));
                }
            }
        }
        return new AllvrLodSectionData(level, cellLong, generation,
            paletteList.toArray(new BlockState[0]), indices, lightBytes);
    }
}
