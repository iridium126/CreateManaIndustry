package com.iridium126.createmanaindustry.client.dimension.render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import com.iridium126.createmanaindustry.dimension.mesh.AllvrMeshCodec;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMeshLight;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMesher;

/**
 * Greedy descriptor mesher for one 16³ render cell and its 1-block border.
 *
 * <p>This is intentionally a separate implementation from the 32³ server
 * mesher: the descriptor's five-bit position/extent fields are naturally
 * bounded by a cell, and a cell can therefore be rebuilt without rescanning
 * the other seven render cells owned by the same network cube.
 */
public final class AllvrCellMesher {

    public static final int CELL = 16;
    public static final int PADDED = 18;
    /**
     * Bounds nonlinear shadow-projection error without the old fragment-exact
     * pass, whose gl_FragDepth write disabled early-Z on every island layer.
     */
    public static final int MAX_GREEDY_EXTENT = 4;
    private static final Direction[] FACES = AllvrMesher.FACES;
    private static final byte[][] UV_AXES = {
        {1, 2}, {2, 1}, {2, 0}, {0, 2}, {0, 1}, {1, 0}
    };

    private final BlockState[] states;
    private final byte[] occludes;
    private final int[] mask = new int[CELL * CELL];
    private final AllvrMeshCodec codec;
    private final AllvrMeshLight light;
    private long[] output = new long[256];
    private int outputCount;

    private AllvrCellMesher(BlockState[] states, byte[] occludes,
                            AllvrMeshLight light, AllvrMeshCodec codec) {
        if (states.length != PADDED * PADDED * PADDED || occludes.length != states.length) {
            throw new IllegalArgumentException("cell snapshot must be 18³");
        }
        this.states = states;
        this.occludes = occludes;
        this.light = light;
        this.codec = codec;
    }

    public static long[] build(BlockState[] states, byte[] occludes,
                               AllvrMeshLight light, AllvrMeshCodec codec) {
        AllvrCellMesher mesher = new AllvrCellMesher(states, occludes, light, codec);
        for (int axis = 0; axis < 3; axis++) {
            for (int dir = 0; dir < 2; dir++) {
                mesher.sweep(axis, dir);
            }
        }
        return java.util.Arrays.copyOf(mesher.output, mesher.outputCount);
    }

    public static int paddedIndex(int x, int y, int z) {
        return (y + 1) * PADDED * PADDED + (z + 1) * PADDED + (x + 1);
    }

    private BlockState stateAt(int uAxis, int vAxis, int wAxis, int u, int v, int w) {
        int x = uAxis == 0 ? u : vAxis == 0 ? v : w;
        int y = uAxis == 1 ? u : vAxis == 1 ? v : w;
        int z = uAxis == 2 ? u : vAxis == 2 ? v : w;
        return this.states[paddedIndex(x, y, z)];
    }

    private int indexAt(int uAxis, int vAxis, int wAxis, int u, int v, int w) {
        int x = uAxis == 0 ? u : vAxis == 0 ? v : w;
        int y = uAxis == 1 ? u : vAxis == 1 ? v : w;
        int z = uAxis == 2 ? u : vAxis == 2 ? v : w;
        return paddedIndex(x, y, z);
    }

    private void sweep(int axis, int dir) {
        int faceIndex = axis * 2 + dir;
        Direction face = FACES[faceIndex];
        int uAxis = UV_AXES[faceIndex][0];
        int vAxis = UV_AXES[faceIndex][1];
        for (int w = 0; w < CELL; w++) {
            boolean any = false;
            for (int v = 0; v < CELL; v++) {
                for (int u = 0; u < CELL; u++) {
                    int id = maskId(axis, dir, uAxis, vAxis, face, u, v, w);
                    this.mask[v * CELL + u] = id;
                    any |= id != 0;
                }
            }
            if (!any) {
                continue;
            }
            for (int v = 0; v < CELL; v++) {
                for (int u = 0; u < CELL; u++) {
                    int id = this.mask[v * CELL + u];
                    if (id == 0) {
                        continue;
                    }
                    int width = 1;
                    while (width < MAX_GREEDY_EXTENT && u + width < CELL
                            && this.mask[v * CELL + u + width] == id) {
                        width++;
                    }
                    int height = 1;
                    outer:
                    while (height < MAX_GREEDY_EXTENT && v + height < CELL) {
                        for (int du = 0; du < width; du++) {
                            if (this.mask[(v + height) * CELL + u + du] != id) {
                                break outer;
                            }
                        }
                        height++;
                    }
                    for (int dv = 0; dv < height; dv++) {
                        for (int du = 0; du < width; du++) {
                            this.mask[(v + dv) * CELL + u + du] = 0;
                        }
                    }
                    int sky = 0;
                    int block = 0;
                    if (this.light != null) {
                        int su = u + (width >> 1);
                        int sv = v + (height >> 1);
                        int wn = dir == 0 ? w + 1 : w - 1;
                        int sx = uAxis == 0 ? su : vAxis == 0 ? sv : wn;
                        int sy = uAxis == 1 ? su : vAxis == 1 ? sv : wn;
                        int sz = uAxis == 2 ? su : vAxis == 2 ? sv : wn;
                        long sampleY = this.light.originY() + sy;
                        sky = this.light.sky(sx, sz, sampleY);
                        block = this.light.block(sx, sz, sampleY);
                    }
                    ensureCapacity();
                    this.output[this.outputCount++] = (long) axis
                        | ((long) dir << 2)
                        | ((long) (width - 1) << 3)
                        | ((long) (height - 1) << 8)
                        | ((long) u << 13)
                        | ((long) v << 18)
                        | ((long) w << 23)
                        | ((long) id << 28)
                        | ((long) sky << 44)
                        | ((long) block << 48);
                }
            }
        }
    }

    private int maskId(int axis, int dir, int uAxis, int vAxis, Direction face, int u, int v, int w) {
        BlockState state = stateAt(uAxis, vAxis, axis, u, v, w);
        if (state.isAir()) {
            return 0;
        }
        int neighborW = dir == 0 ? w + 1 : w - 1;
        if (this.occludes[indexAt(uAxis, vAxis, axis, u, v, neighborW)] != 0) {
            return 0;
        }
        BlockState neighbor = stateAt(uAxis, vAxis, axis, u, v, neighborW);
        if (state.skipRendering(neighbor, face)) {
            return 0;
        }
        return this.codec.packId(state);
    }

    private void ensureCapacity() {
        if (this.outputCount < this.output.length) {
            return;
        }
        this.output = java.util.Arrays.copyOf(this.output, this.output.length * 2);
    }

    public static byte occludesAt(BlockState state) {
        if (!state.canOcclude()) {
            return 0;
        }
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) ? (byte) 1 : 0;
    }
}
