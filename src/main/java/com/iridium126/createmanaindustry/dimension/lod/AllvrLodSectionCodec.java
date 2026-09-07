package com.iridium126.createmanaindustry.dimension.lod;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wire codec for {@link AllvrLodSectionData} (voxy integration plan §6.1) —
 * palette-compressed 32³ voxel payload. Encoded once server-side and cached
 * as raw bytes, so repeated serves skip both the bit-packing and the palette
 * rebuild.
 * <p>
 * Payload layout (all varints, LSB-first bit packing):
 * <pre>
 *   format (varint)  — 0 = all air, 1 = single material, 2 = palette
 *   palette          — format 1: one vanilla state id; format 2: (n-1) ids,
 *                      palette[0] = air is implicit and not written
 *   bitWidth         — format 2 only: 1/2/4/8/16 (0 never occurs with a payload)
 *   packedIndices    — byte array, LSB-first bit packing, format 2 only
 *   light            — uniform flag (varint): 0 = one byte follows, 1 = 32768 bytes
 * </pre>
 * Every decode validates the fixed cell count, palette cap and array sizes;
 * a malformed payload throws so the packet layer rejects the whole packet
 * (plan §6.1: no memory-amplification).
 */
public final class AllvrLodSectionCodec {

    private static final int FORMAT_ALL_AIR = 0;
    private static final int FORMAT_SINGLE = 1;
    private static final int FORMAT_PALETTE = 2;
    private static final int LIGHT_UNIFORM = 0;
    private static final int LIGHT_FULL = 1;

    private AllvrLodSectionCodec() {}

    /** Encodes a non-air section into its cached wire payload. */
    public static byte[] encode(AllvrLodSectionData data) {
        ByteSink out = new ByteSink(4096);
        boolean uniformLight = true;
        for (int i = 1; i < data.light().length; i++) {
            if (data.light()[i] != data.light()[0]) {
                uniformLight = false;
                break;
            }
        }
        if (data.palette().length == 2) {
            out.varint(FORMAT_SINGLE);
            out.varint(Block.getId(data.palette()[1]));
        } else {
            out.varint(FORMAT_PALETTE);
            out.varint(data.palette().length - 1);
            for (int i = 1; i < data.palette().length; i++) {
                out.varint(Block.getId(data.palette()[i]));
            }
            int bitWidth = bitWidth(data.palette().length);
            out.varint(bitWidth);
            out.bytes(pack(data.indices(), bitWidth));
        }
        if (uniformLight) {
            out.varint(LIGHT_UNIFORM);
            out.byteValue(data.light()[0]);
        } else {
            out.varint(LIGHT_FULL);
            out.bytes(data.light());
        }
        return out.toArray();
    }

    /**
     * Decodes a payload back into a section. {@code payload} is the cached
     * wire bytes (never null); vanilla state ids resolve through
     * {@link Block#stateById} exactly like the legacy quad remap.
     */
    public static AllvrLodSectionData decode(int level, long cellLong, long generation, byte[] payload) {
        ByteReader in = new ByteReader(payload);
        int format = in.varint();
        BlockState[] palette;
        int[] indices;
        switch (format) {
            case FORMAT_ALL_AIR -> {
                return null;
            }
            case FORMAT_SINGLE -> {
                BlockState material = stateById(in.varint());
                palette = new BlockState[] {airState(), material};
                indices = null; // every cell is palette index 1
            }
            case FORMAT_PALETTE -> {
                int count = in.varint();
                if (count < 1 || count > AllvrLodSectionData.MAX_PALETTE) {
                    throw new IllegalArgumentException("bad palette size " + count);
                }
                palette = new BlockState[count + 1];
                palette[0] = airState();
                for (int i = 1; i <= count; i++) {
                    palette[i] = stateById(in.varint());
                }
                int width = in.varint();
                if (width != 1 && width != 2 && width != 4 && width != 8 && width != 16) {
                    throw new IllegalArgumentException("bad index bit width " + width);
                }
                byte[] packed = in.bytes((AllvrLodSectionData.CELLS * width + 7) >> 3);
                indices = unpack(packed, width);
            }
            default -> throw new IllegalArgumentException("bad section format " + format);
        }
        byte uniform = (byte) in.varint();
        byte[] light;
        if (uniform == LIGHT_UNIFORM) {
            byte value = (byte) in.byteValue();
            light = new byte[AllvrLodSectionData.CELLS];
            java.util.Arrays.fill(light, value);
        } else {
            light = in.bytes(AllvrLodSectionData.CELLS);
        }
        int[] effective = indices;
        if (effective == null) {
            effective = new int[AllvrLodSectionData.CELLS];
            java.util.Arrays.fill(effective, 1);
        }
        return new AllvrLodSectionData(level, cellLong, generation, palette, effective, light);
    }

    private static int bitWidth(int paletteSize) {
        int bits = Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1);
        return Math.max(1, bits);
    }

    /** LSB-first bit packing of {@code CELLS} values of {@code width} bits. */
    static byte[] pack(int[] values, int width) {
        byte[] out = new byte[(AllvrLodSectionData.CELLS * width + 7) >> 3];
        long acc = 0;
        int bits = 0;
        int pos = 0;
        for (int i = 0; i < values.length; i++) {
            acc |= (values[i] & ((1 << width) - 1)) << bits;
            bits += width;
            while (bits >= 8) {
                out[pos++] = (byte) acc;
                acc >>>= 8;
                bits -= 8;
            }
        }
        if (bits > 0) {
            out[pos] = (byte) acc;
        }
        return out;
    }

    static int[] unpack(byte[] packed, int width) {
        int[] out = new int[AllvrLodSectionData.CELLS];
        long acc = 0;
        int bits = 0;
        int pos = 0;
        for (int i = 0; i < out.length; i++) {
            while (bits < width) {
                acc |= (packed[pos++] & 0xFFL) << bits;
                bits += 8;
            }
            out[i] = (int) (acc & ((1 << width) - 1));
            acc >>>= width;
            bits -= width;
        }
        return out;
    }

    private static BlockState airState() {
        return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    private static BlockState stateById(int id) {
        BlockState state = Block.stateById(id);
        if (state == null) {
            throw new IllegalArgumentException("unknown vanilla state id " + id);
        }
        return state;
    }

    // ---- minimal growable byte sink / reader -------------------------------

    private static final class ByteSink {
        private byte[] data;
        private int size;

        ByteSink(int cap) {
            this.data = new byte[cap];
        }

        void varint(int value) {
            while ((value & ~0x7F) != 0) {
                this.byteValue((byte) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            this.byteValue((byte) value);
        }

        void byteValue(int value) {
            this.ensure(1);
            this.data[this.size++] = (byte) value;
        }

        void bytes(byte[] source) {
            this.ensure(source.length);
            System.arraycopy(source, 0, this.data, this.size, source.length);
            this.size += source.length;
        }

        byte[] toArray() {
            byte[] out = new byte[this.size];
            System.arraycopy(this.data, 0, out, 0, this.size);
            return out;
        }

        private void ensure(int extra) {
            if (this.size + extra > this.data.length) {
                this.data = java.util.Arrays.copyOf(this.data, Math.max(this.data.length * 2, this.size + extra));
            }
        }
    }

    private static final class ByteReader {
        private final byte[] data;
        private int pos;

        ByteReader(byte[] data) {
            this.data = data;
        }

        int varint() {
            int value = 0;
            int shift = 0;
            while (true) {
                if (this.pos >= this.data.length) {
                    throw new IllegalArgumentException("truncated varint");
                }
                int b = this.data[this.pos++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IllegalArgumentException("varint too long");
                }
            }
        }

        int byteValue() {
            if (this.pos >= this.data.length) {
                throw new IllegalArgumentException("truncated byte");
            }
            return this.data[this.pos++] & 0xFF;
        }

        byte[] bytes(int count) {
            if (count < 0 || this.pos + count > this.data.length) {
                throw new IllegalArgumentException("truncated byte array (" + count + ")");
            }
            byte[] out = new byte[count];
            System.arraycopy(this.data, this.pos, out, 0, count);
            this.pos += count;
            return out;
        }
    }
}
