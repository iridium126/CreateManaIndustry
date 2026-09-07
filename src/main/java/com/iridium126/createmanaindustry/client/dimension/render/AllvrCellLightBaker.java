package com.iridium126.createmanaindustry.client.dimension.render;

import java.util.Arrays;

import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMeshLight;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMesher;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import net.minecraft.core.BlockPos;

/**
 * Immutable lighting context for one render cell.
 *
 * <p>The gather is shared by all quads in the cell: sky exposure uses the
 * padded 18×18 columns plus the configured vertical window, and block light
 * uses the emitter indices of the surrounding cubes.  The worker then samples
 * this context without touching the live client cache.
 */
public final class AllvrCellLightBaker implements AllvrMeshLight {

    private static final int SKY_WINDOW = 128;
    private static final int MAX_EMITTERS = 4096;
    private static final int EMITTER_RANGE = 15;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final long[][] columns;
    private final long[] emitters;
    private final int emitterCount;

    private AllvrCellLightBaker(int minX, int minY, int minZ, long[][] columns,
                                long[] emitters, int emitterCount) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.columns = columns;
        this.emitters = emitters;
        this.emitterCount = emitterCount;
    }

    public static AllvrCellLightBaker capture(long cellKey, byte[] occludes) {
        int minX = AllvrRenderCellKey.minBlockX(cellKey);
        int minY = AllvrRenderCellKey.minBlockY(cellKey);
        int minZ = AllvrRenderCellKey.minBlockZ(cellKey);
        long[][] columns = new long[18 * 18][];
        long[] emitters = new long[MAX_EMITTERS * 4];
        int emitterCount = 0;
        synchronized (AllvrClientCubeCache.LOCK) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = -1; x <= 16; x++) {
                for (int z = -1; z <= 16; z++) {
                    long[] values = new long[64];
                    int count = 0;
                    for (int y = -1; y <= 16 + SKY_WINDOW; y++) {
                        boolean occluder;
                        if (y <= 16) {
                            occluder = occludes[AllvrCellMesher.paddedIndex(x, y, z)] != 0;
                        } else {
                            BlockPos sample = pos.set(minX + x, minY + y, minZ + z);
                            occluder = AllvrMesher.occludesAt(AllvrClientCubeCache.getBlockState(sample)) != 0;
                        }
                        if (occluder) {
                            if (count == values.length) {
                                values = Arrays.copyOf(values, values.length * 2);
                            }
                            values[count++] = (long) minY + y;
                        }
                    }
                    columns[(x + 1) + (z + 1) * 18] = Arrays.copyOf(values, count);
                }
            }

            int cubeX = minX >> 5;
            int cubeY = minY >> 5;
            int cubeZ = minZ >> 5;
            for (int dy = -1; dy <= 1 && emitterCount < MAX_EMITTERS; dy++) {
                for (int dz = -1; dz <= 1 && emitterCount < MAX_EMITTERS; dz++) {
                    for (int dx = -1; dx <= 1 && emitterCount < MAX_EMITTERS; dx++) {
                        AllvrCube cube = AllvrClientCubeCache.peekCube(
                            AllvrCubePos.asLong(cubeX + dx, cubeY + dy, cubeZ + dz));
                        if (cube == null) {
                            continue;
                        }
                        int baseX = (cubeX + dx) << 5;
                        int baseY = (cubeY + dy) << 5;
                        int baseZ = (cubeZ + dz) << 5;
                        for (Int2IntMap.Entry entry : cube.getEmitters().int2IntEntrySet()) {
                            if (emitterCount >= MAX_EMITTERS) {
                                break;
                            }
                            int index = entry.getIntKey();
                            int offset = emitterCount++ * 4;
                            emitters[offset] = baseX + (index & 31);
                            emitters[offset + 1] = baseY + ((index >> 10) & 31);
                            emitters[offset + 2] = baseZ + ((index >> 5) & 31);
                            emitters[offset + 3] = entry.getIntValue();
                        }
                    }
                }
            }
        }
        return new AllvrCellLightBaker(minX, minY, minZ, columns, emitters, emitterCount);
    }

    @Override
    public long originY() {
        return this.minY;
    }

    @Override
    public int sky(int lx, int lz, long y) {
        long[] column = this.columns[(lx + 1) + (lz + 1) * 18];
        for (long occluder : column) {
            if (occluder > y && occluder - y <= SKY_WINDOW) {
                return 0;
            }
        }
        return 15;
    }

    @Override
    public int block(int lx, int lz, long y) {
        int best = 0;
        long x = (long) this.minX + lx;
        long z = (long) this.minZ + lz;
        for (int i = 0; i < this.emitterCount; i++) {
            int offset = i * 4;
            long dx = Math.abs(this.emitters[offset] - x);
            long dy = Math.abs(this.emitters[offset + 1] - y);
            long dz = Math.abs(this.emitters[offset + 2] - z);
            if (dx + dy + dz <= EMITTER_RANGE) {
                best = Math.max(best, (int) this.emitters[offset + 3] - (int) (dx + dy + dz));
            }
        }
        return Math.max(0, Math.min(15, best));
    }
}
