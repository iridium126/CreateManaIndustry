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
 * All non-air states are preserved, including fluids and foliage. Occlusion
 * affects light independently of material representation. Optional per-cell
 * biome registry ids carry datapack grass, foliage and water tint into Voxy.
 * <p>
 * Instances are <b>validated, read-only values</b> (plan F03): the
 * constructor structurally validates every field and defensively copies the
 * caller's arrays, so a decoded section can never be mutated into an
 * out-of-contract state after the wire check passed, and a caller-side alias
 * can never reach into a published section.
 */
public final class AllvrLodSectionData {

    public static final int CELLS = 32 * 32 * 32;
    /**
     * Palette size cap for the wire format (F03): a node has only
     * {@link #CELLS} voxels, so a palette can never legitimately exceed
     * CELLS entries (air + at most one distinct state per voxel). The old
     * {@code 1<<15} cap admitted encodings the transport then had to refuse.
     */
    public static final int MAX_PALETTE = CELLS;

    private final int level;
    /** Absolute {@code (level, cell)} key — the server never writes virtual Y. */
    private final long cellLong;
    /** Server-side node generation at build time (edit counter). */
    private final long generation;
    private final BlockState[] palette;
    private final int[] indices;
    private final byte[] light;
    private final int[] biomeIds;

    /**
     * Validated construction — the ONLY way a section comes into existence.
     * Every invariant the codec and the injector rely on is checked here, so
     * callers can index {@code palette[indices[i]]} and the light nibbles
     * without re-validating.
     */
    public AllvrLodSectionData(int level, long cellLong, long generation,
                               BlockState[] palette, int[] indices, byte[] light) {
        this(level, cellLong, generation, palette, indices, light, null);
    }

    public AllvrLodSectionData(int level, long cellLong, long generation,
                               BlockState[] palette, int[] indices, byte[] light, int[] biomeIds) {
        if (level < 0 || level > AllvrLodPos.MAX_LEVEL) {
            throw new IllegalArgumentException("LOD level out of range: " + level);
        }
        AllvrLodPos pos = AllvrLodPos.fromCellLong(level, cellLong);
        for (int axis = 0; axis < 3; axis++) {
            int c = new int[] {pos.cellX(), pos.cellY(), pos.cellZ()}[axis];
            if (c < -com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.MAX_COORDINATE_VALUE
                || c > com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.MAX_COORDINATE_VALUE) {
                throw new IllegalArgumentException("LOD cell coordinate out of range: " + pos);
            }
        }
        if (palette == null || palette.length < 2 || palette.length > MAX_PALETTE) {
            throw new IllegalArgumentException("bad LOD palette size "
                + (palette == null ? -1 : palette.length));
        }
        if (palette[0] == null || !palette[0].isAir()) {
            throw new IllegalArgumentException("LOD palette[0] must be air");
        }
        for (int i = 1; i < palette.length; i++) {
            if (palette[i] == null || palette[i].isAir()) {
                throw new IllegalArgumentException("LOD palette[" + i + "] must be a non-air state");
            }
        }
        if (indices == null || indices.length != CELLS) {
            throw new IllegalArgumentException("LOD indices must hold exactly " + CELLS + " cells");
        }
        for (int index : indices) {
            if (index < 0 || index >= palette.length) {
                throw new IllegalArgumentException("LOD cell index " + index
                    + " outside palette of size " + palette.length);
            }
        }
        if (light == null || light.length != CELLS) {
            throw new IllegalArgumentException("LOD light must hold exactly " + CELLS + " bytes");
        }
        this.level = level;
        this.cellLong = cellLong;
        this.generation = generation;
        this.palette = palette.clone();
        this.indices = indices.clone();
        this.light = light.clone();
        if (biomeIds != null) {
            if (biomeIds.length != CELLS) throw new IllegalArgumentException("Bad biome cell count");
            for (int id : biomeIds) if (id < 0 || id >= (1 << 20)) throw new IllegalArgumentException("Bad biome id " + id);
        }
        this.biomeIds = biomeIds == null ? null : biomeIds.clone();
    }

    public int[] biomeIds() { return biomeIds == null ? null : biomeIds.clone(); }

    public AllvrLodSectionData withBiomes(int[] ids) {
        return new AllvrLodSectionData(level, cellLong, generation, palette, indices, light, ids);
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

    /** Defensive copy — the palette of a published section is never aliased. */
    public BlockState[] palette() {
        return this.palette.clone();
    }

    /** Defensive copy — the index stream of a published section is never aliased. */
    public int[] indices() {
        return this.indices.clone();
    }

    /** Defensive copy — the light stream of a published section is never aliased. */
    public byte[] light() {
        return this.light.clone();
    }

    /** Read-only view of the palette for hot loops that only read it. */
    public void copyPaletteInto(BlockState[] target) {
        System.arraycopy(this.palette, 0, target, 0, this.palette.length);
    }

    public boolean isAllAir() {
        for (int index : this.indices) {
            if (index != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the section from a filled 34³ padded snapshot (plan §6.2): the
     * 32³ core cells are lifted out with fluids and foliage preserved, the palette
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
                    if (paddedStates[padded].isAir()) {
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
