package com.iridium126.createmanaindustry.dimension.lod;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.cube.AllvrOverlaySource;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandFieldGenerator;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandLayout.Island;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMeshLight;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMesher;

import java.util.List;

/**
 * Builds the 34³ mesher snapshot for one LOD node from the island density
 * field plus player-edit overlay (doc §13 4c) — the server-side VoxelSource.
 * <p>
 * Samples the datapack's noise and surface columns. A geometric bound can
 * reject void, but must never assert solid: arbitrary density functions may
 * carve caves or overhangs anywhere inside an island. Decorated columns are
 * shared with near cubes so terrain features have the same silhouette.
 * <p>
 * The overlay (captured on the server thread at enqueue time — see
 * {@link #capture}) overrides cells with real blocks from EDITED loaded
 * cubes: unedited cubes are bitwise the density field, so only they need
 * reading, which keeps the main-thread capture near zero for natural terrain.
 * Overlay semantics per grilling Q3: any full occluder in the cell ⇒ solid,
 * represented by that state (dug tunnels read as solid far away — documented
 * known limitation).
 * <p>
 * Usage per build job: {@link #create} → {@link #fill} → {@link #light()} →
 * {@code AllvrMesher.build(..., light, SERVER_CODEC)}.
 */
public final class AllvrLodSnapshot {

    /** Server mesh codec: vanilla global state id, gated on canOcclude + full
     *  block (the 4c gating decision). Voxy consumes the decoded payload. */
    public static final com.iridium126.createmanaindustry.dimension.mesh.AllvrMeshCodec SERVER_CODEC =
        state -> AllvrMesher.occludesAt(state) != 0 ? net.minecraft.world.level.block.Block.getId(state) : 0;

    private final AllvrIslandFieldGenerator generator;
    private final AllvrLodPos pos;
    private final Island[] islands;
    private final Overlay overlay;
    /** The occluder array fill() was given — the light's column scan reads it. */
    private byte[] occludes;

    private AllvrLodSnapshot(AllvrIslandFieldGenerator generator, AllvrLodPos pos, Overlay overlay) {
        this.generator = generator;
        this.pos = pos;
        this.overlay = overlay;
        int stride = pos.stride();
        int minBx = pos.minBlockX();
        int minBy = pos.minBlockY();
        int minBz = pos.minBlockZ();
        int span = pos.sizeBlocks();
        this.islands = this.generator.islandsForBox(minBx - stride, minBy - stride, minBz - stride,
            minBx + span + stride, minBy + span + stride, minBz + span + stride);
    }

    public static AllvrLodSnapshot create(AllvrIslandFieldGenerator generator, AllvrLodPos pos, Overlay overlay) {
        return new AllvrLodSnapshot(generator, pos, overlay);
    }

    public int[] biomeIds(net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> registry) {
        int[] ids = new int[AllvrLodSectionData.CELLS];
        java.util.Map<BlockPos, Integer> sampled = new java.util.HashMap<>();
        int stride = pos.stride();
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            BlockPos quart = new BlockPos((pos.minBlockX() + x * stride + stride / 2) >> 2,
                (pos.minBlockY() + y * stride + stride / 2) >> 2, (pos.minBlockZ() + z * stride + stride / 2) >> 2);
            ids[AllvrLodSectionData.cellIndex(x, y, z)] = sampled.computeIfAbsent(quart,
                q -> registry.getId(generator.biome(q.getX(), q.getY(), q.getZ()).value()));
        }
        return ids;
    }

    /**
     * Server-thread overlay capture for a node: every overlapping
     * persisted-edited cube contributes its occluder cells (first occluder per
     * snapshot cell) and light emitters. The caller decides what a source is —
     * a loaded {@link AllvrCube} (live capture) or a decoded
     * {@code AllvrPersistedOverlay} (persisted-but-unloaded cube; plan §7.6).
     * Cells align with cube borders on every level (stride divides 32), so
     * each snapshot cell is decided by exactly one cube. Cubes without a
     * persistence record are skipped wholesale — their blocks ARE the density
     * field.
     */
    public static Overlay capture(AllvrLodPos pos, List<AllvrOverlaySource> sources) {
        Int2ObjectOpenHashMap<BlockState> cells = new Int2ObjectOpenHashMap<>();
        LongArrayList emitters = new LongArrayList();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (AllvrOverlaySource source : sources) {
            AllvrCubePos cpos = source.getPos();
            collectCubeOverlay(pos, source, cpos.getX(), cpos.getY(), cpos.getZ(), cursor, cells);
            for (Int2IntMap.Entry e : source.getEmitters().int2IntEntrySet()) {
                int cell = e.getIntKey();
                emitters.add((cpos.getX() << 5) + (cell & 31));
                emitters.add((cpos.getY() << 5) + (cell >> 10));
                emitters.add((cpos.getZ() << 5) + ((cell >> 5) & 31));
                emitters.add(e.getIntValue());
            }
        }
        return new Overlay(cells, emitters.toLongArray());
    }

    /**
     * Merges a second capture (decoded persisted overlays) into the live one.
     * Cells never overlap (one cell ⟸ one cube), but {@code putIfAbsent} keeps
     * the live capture authoritative regardless.
     */
    public static Overlay mergeInto(Overlay base, Overlay extra) {
        if (extra.cells().isEmpty() && extra.emitters().length == 0) {
            return base;
        }
        for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<BlockState> e : extra.cells().int2ObjectEntrySet()) {
            base.cells().putIfAbsent(e.getIntKey(), e.getValue());
        }
        if (extra.emitters().length == 0) {
            return base;
        }
        long[] merged = java.util.Arrays.copyOf(base.emitters(), base.emitters().length + extra.emitters().length);
        System.arraycopy(extra.emitters(), 0, merged, base.emitters().length, extra.emitters().length);
        return new Overlay(base.cells(), merged);
    }

    /** One overlay source's occluder contribution to the snapshot cells. */
    private static void collectCubeOverlay(AllvrLodPos pos, AllvrOverlaySource source, int cx, int cy, int cz,
                                           BlockPos.MutableBlockPos cursor,
                                           Int2ObjectOpenHashMap<BlockState> cells) {
        // Snapshot cells are stride-sized blocks (32, 64, 128, 256), so the
        // local cube-to-cell conversion uses only the level scale.  Adding the
        // base cube shift here collapsed every edited cube to one cell at L0.
        int shift = pos.level();
        int stride = pos.stride();
        int minBx = pos.minBlockX();
        int minBy = pos.minBlockY();
        int minBz = pos.minBlockZ();
        int bx0 = cx << 5;
        int by0 = cy << 5;
        int bz0 = cz << 5;
        // this cube's local cell range, clamped to the padded snapshot space
        int lx0 = Math.max(-1, (bx0 - minBx) >> shift);
        int lx1 = Math.min(32, (bx0 + 31 - minBx) >> shift);
        int ly0 = Math.max(-1, (by0 - minBy) >> shift);
        int ly1 = Math.min(32, (by0 + 31 - minBy) >> shift);
        int lz0 = Math.max(-1, (bz0 - minBz) >> shift);
        int lz1 = Math.min(32, (bz0 + 31 - minBz) >> shift);
        for (int ly = ly0; ly <= ly1; ly++) {
            for (int lz = lz0; lz <= lz1; lz++) {
                for (int lx = lx0; lx <= lx1; lx++) {
                    if (cells.containsKey(AllvrMesher.paddedIndex(lx, ly, lz))) {
                        continue;
                    }
                    // scan the cell's stride³ blocks for the first full occluder
                    int wx0 = minBx + (lx << pos.level());
                    int wy0 = minBy + (ly << pos.level());
                    int wz0 = minBz + (lz << pos.level());
                    BlockState found = null;
                    BlockState fallback = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                    for (int dy = 0; dy < stride && found == null; dy++) {
                        for (int dz = 0; dz < stride && found == null; dz++) {
                            for (int dx = 0; dx < stride && found == null; dx++) {
                    BlockState s = source.getBlockState(cursor.set(wx0 + dx, wy0 + dy, wz0 + dz));
                    if (fallback.isAir() && !s.isAir()) fallback = s;
                    if (AllvrMesher.occludesAt(s) != 0) {
                        found = s;
                    }
                            }
                        }
                    }
                    // Empty edits must also override procedural terrain. Preserve
                    // fluids/plants when the cell has no opaque representative.
                    cells.put(AllvrMesher.paddedIndex(lx, ly, lz), found == null ? fallback : found);
                }
            }
        }
    }

    /** Immutable overlay payload handed from the server thread to a build job. */
    public record Overlay(Int2ObjectOpenHashMap<BlockState> cells, long[] emitters) {}

    /**
     * Fills the padded snapshot arrays for one node. {@code states} /
     * {@code occludes} are caller-allocated {@code PADDED³} arrays (air /
     * zero pre-filled by the caller). Must run before {@link #light()}.
     */
    public void fill(BlockState[] states, byte[] occludes) {
        this.occludes = occludes;
        int stride = this.pos.stride();
        int minBx = this.pos.minBlockX();
        int minBy = this.pos.minBlockY();
        int minBz = this.pos.minBlockZ();
        for (int z = -1; z <= 32; z++) for (int x = -1; x <= 32; x++) {
            int wx = minBx + x * stride + stride / 2;
            int wz = minBz + z * stride + stride / 2;
            var column = generator.columnSampler(wx, wz, minBy - stride, minBy + 33 * stride, islands);
            for (int y = -1; y <= 32; y++) {
                BlockState state = column.apply(minBy + y * stride + stride / 2);
                if (state != null) {
                    int index = AllvrMesher.paddedIndex(x, y, z);
                    states[index] = state;
                    occludes[index] = AllvrMesher.occludesAt(state);
                }
            }
        }
        // overlay always wins over the density field
        for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<BlockState> e : this.overlay.cells().int2ObjectEntrySet()) {
            int idx = e.getIntKey();
            states[idx] = e.getValue();
            occludes[idx] = AllvrMesher.occludesAt(e.getValue());
        }
    }

    /**
     * The node's {@link AllvrMeshLight}: sky = snapshot column scan plus six
     * conservative island-envelope samples over the 128-block window above the
     * node (caves and thin overhangs are approximated at this distance); block
     * light = manhattan max-decay over the captured edited-cube emitters.
     * <p>
     * NB the mesher passes {@code y = originY() + local cell index}; with
     * stride > 1 this impl reinterprets it as a cell index and scales.
     */
    public AllvrMeshLight light() {
        if (this.occludes == null) {
            throw new IllegalStateException("light() before fill()");
        }
        return new LodLight();
    }

    private final class LodLight implements AllvrMeshLight {

        private final Island[] aboveIslands;

        LodLight() {
            AllvrLodSnapshot self = AllvrLodSnapshot.this;
            int margin = self.pos.sizeBlocks();
            int top = self.pos.minBlockY() + self.pos.sizeBlocks();
            this.aboveIslands = self.generator.islandsForBox(
                self.pos.minBlockX() - margin, top - 32, self.pos.minBlockZ() - margin,
                self.pos.minBlockX() + margin, top + 128 + margin, self.pos.minBlockZ() + margin);
        }

        @Override
        public long originY() {
            return AllvrLodSnapshot.this.pos.minBlockY();
        }

        @Override
        public int sky(int lx, int lz, long y) {
            AllvrLodSnapshot self = AllvrLodSnapshot.this;
            int cellLy = (int) (y - originY());
            byte[] occludes = self.occludes;
            for (int ly = cellLy + 1; ly <= 32; ly++) {
                if (occludes[AllvrMesher.paddedIndex(lx, ly, lz)] != 0) {
                    return 0;
                }
            }
            // six coarse samples over the window above the node top
            int stride = self.pos.stride();
            double sampleAbs = originY() + cellLy * (double) stride;
            double topAbs = originY() + 32.0 * stride;
            double window = 128.0 - Math.max(0.0, topAbs - sampleAbs);
            if (window <= 0) {
                return 15;
            }
            double wx = self.pos.minBlockX() + (lx + 0.5) * stride;
            double wz = self.pos.minBlockZ() + (lz + 0.5) * stride;
            for (int k = 1; k <= 6; k++) {
                double py = topAbs + window * k / 7.0;
                for (Island island : this.aboveIslands) {
                    if (island.contains((int) wx, (int) py, (int) wz)) return 0;
                }
            }
            return 15;
        }

        @Override
        public int block(int lx, int lz, long y) {
            AllvrLodSnapshot self = AllvrLodSnapshot.this;
            long[] emitters = self.overlay.emitters();
            if (emitters.length == 0) {
                return 0;
            }
            int stride = self.pos.stride();
            long cellLy = y - originY(); // mesher passes originY + local cell index
            long x = self.pos.minBlockX() + lx * (long) stride + (stride >> 1);
            long yy = originY() + cellLy * stride + (stride >> 1);
            long z = self.pos.minBlockZ() + lz * (long) stride + (stride >> 1);
            int best = 0;
            for (int i = 0; i < emitters.length; i += 4) {
                long dx = Math.abs(emitters[i] - x);
                if (dx > 15) {
                    continue;
                }
                long dy = Math.abs(emitters[i + 1] - yy);
                if (dy > 15) {
                    continue;
                }
                long dz = Math.abs(emitters[i + 2] - z);
                if (dx + dy + dz > 15) {
                    continue;
                }
                int value = (int) (emitters[i + 3] - dx - dy - dz);
                if (value > best) {
                    best = value;
                }
            }
            return Math.min(15, best);
        }
    }
}
