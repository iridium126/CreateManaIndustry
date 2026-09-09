package com.iridium126.createmanaindustry.dimension.lod;

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
 *   format (varint)  — 0 = all air, 1 = single uniform non-air material, 2 = palette
 *   palette          — format 1: one vanilla state id; format 2: (n-1) ids,
 *                      palette[0] = air is implicit and not written
 *   bitWidth         — format 2 only: the canonical width for the palette
 *                      size (1/2/4/8/16) — decode rejects anything else
 *   packedIndices    — byte array, LSB-first bit packing, format 2 only
 *   light            — uniform flag (varint): exactly 0 = one byte follows,
 *                      exactly 1 = 32768 bytes follow
 *   biomes           — 0 = absent, 1 = one uniform registry id,
 *                      2 = 32768 registry-id varints (each bounded to 20 bits)
 * </pre>
 * Protocol notes (review F01–F03):
 * <ul>
 *   <li>format 1 is emitted ONLY when every cell holds the same non-air
 *       palette index — an air+stone mix still encodes the full index stream,
 *       so a half-solid island can never flatten into a solid node;</li>
 *   <li>the index bit width is a single canonical function of the palette
 *       size on BOTH sides — an encoder can never produce a width its own
 *       decoder rejects, and the shift math is done in {@code long} so a
 *       future width beyond 32 cannot truncate;</li>
 *   <li>decode validates format, flag, width-vs-palette, every state id
 *       against the live block-state registry (a plain id lookup silently
 *       maps unknown ids to AIR, which would smuggle bad ids through), the
 *       index range, and the exact payload length — no trailing bytes.</li>
 * </ul>
 * Every violation throws so the packet layer rejects the whole packet
 * (plan §6.1: no memory-amplification).
 */
public final class AllvrLodSectionCodec {

    private static final int FORMAT_ALL_AIR = 0;
    private static final int FORMAT_SINGLE = 1;
    private static final int FORMAT_PALETTE = 2;
    private static final int LIGHT_UNIFORM = 0;
    private static final int LIGHT_FULL = 1;

    /**
     * Transport cap for one section payload (F03): derived from the real
     * legal domain, not a guess — worst legitimate encoding is
     * palette (≤ CELLS−1 non-air ids, 3-byte varints each, plus the count
     * varint) + indices (32768 × 16 bit) + light (32768 bytes + flag):
     * ≈ 98.3 KB + 64 KB + 32.8 KB ≈ 195 KB. 384 KB includes up to 96 KB of per-cell biome ids and carries headroom without
     * admitting anything the codec itself would reject; the encoder
     * additionally refuses to emit an oversized payload so a server can never
     * produce a packet the client is required to drop.
     */
    public static final int MAX_PAYLOAD_BYTES = 384 * 1024;

    private AllvrLodSectionCodec() {}

    /** Encodes a non-air section into its cached wire payload. */
    public static byte[] encode(AllvrLodSectionData data) {
        int[] indices = data.indices();
        // F01: "single material" requires a genuinely uniform NON-AIR index
        // stream — a 2-entry palette with mixed air/material must still carry
        // per-cell occupancy
        int uniformIndex = uniformNonAirIndex(indices);
        ByteSink out = new ByteSink(4096);
        BlockState[] palette = data.palette();
        if (uniformIndex > 0) {
            out.varint(FORMAT_SINGLE);
            out.varint(Block.getId(palette[uniformIndex]));
        } else {
            out.varint(FORMAT_PALETTE);
            int nonAir = palette.length - 1;
            out.varint(nonAir);
            for (int i = 1; i < palette.length; i++) {
                out.varint(Block.getId(palette[i]));
            }
            int bitWidth = bitWidth(palette.length);
            out.varint(bitWidth);
            out.bytes(pack(indices, bitWidth));
        }
        byte[] light = data.light();
        boolean uniformLight = true;
        for (int i = 1; i < light.length; i++) {
            if (light[i] != light[0]) {
                uniformLight = false;
                break;
            }
        }
        if (uniformLight) {
            out.varint(LIGHT_UNIFORM);
            out.byteValue(light[0]);
        } else {
            out.varint(LIGHT_FULL);
            out.bytes(light);
        }
        int[] biomes = data.biomeIds();
        if (biomes == null) {
            out.varint(0);
        } else {
            boolean uniform = true;
            for (int id : biomes) if (id != biomes[0]) { uniform = false; break; }
            out.varint(uniform ? 1 : 2);
            if (uniform) out.varint(biomes[0]);
            else for (int id : biomes) out.varint(id);
        }
        byte[] encoded = out.toArray();
        if (encoded.length > MAX_PAYLOAD_BYTES) {
            // the palette cap keeps every legitimate encoding far below the
            // cap; hitting this means the domain assumptions broke — refuse
            // to send rather than emit a packet clients must reject
            throw new IllegalStateException("encoded LOD section exceeds the transport cap: "
                + encoded.length + " > " + MAX_PAYLOAD_BYTES);
        }
        return encoded;
    }

    /** The one uniform non-air index, or 0 when the stream is mixed/air. */
    private static int uniformNonAirIndex(int[] indices) {
        int first = indices[0];
        if (first == 0) {
            return 0;
        }
        for (int i = 1; i < indices.length; i++) {
            if (indices[i] != first) {
                return 0;
            }
        }
        return first;
    }

    /**
     * Decodes a payload back into a section. {@code payload} is the cached
     * wire bytes (never null); vanilla state ids resolve through
     * {@link Block#stateById} AFTER the id is proven to exist in the block
     * state registry ({@code stateById} silently answers AIR for unknown ids,
     * which would turn a hostile payload into valid-looking air).
     */
    public static AllvrLodSectionData decode(int level, long cellLong, long generation, byte[] payload) {
        ByteReader in = new ByteReader(payload);
        int format = in.varint();
        BlockState[] palette;
        int[] indices;
        switch (format) {
            case FORMAT_ALL_AIR -> {
                requireConsumed(in);
                return null;
            }
            case FORMAT_SINGLE -> {
                BlockState material = checkedState(in.varint());
                palette = new BlockState[] {airState(), material};
                indices = null; // every cell is palette index 1
            }
            case FORMAT_PALETTE -> {
                int count = in.varint();
                if (count < 1 || count > AllvrLodSectionData.MAX_PALETTE - 1) {
                    throw new IllegalArgumentException("bad palette size " + count);
                }
                palette = new BlockState[count + 1];
                palette[0] = airState();
                for (int i = 1; i <= count; i++) {
                    palette[i] = checkedState(in.varint());
                }
                int width = in.varint();
                // F02: the width is canonical per palette size on both sides —
                // any other value is malformed, not "another encoding"
                int expected = bitWidth(palette.length);
                if (width != expected) {
                    throw new IllegalArgumentException("bad index bit width " + width
                        + " (canonical " + expected + " for palette " + palette.length + ")");
                }
                byte[] packed = in.bytes((AllvrLodSectionData.CELLS * width + 7) >> 3);
                indices = unpack(packed, width);
            }
            default -> throw new IllegalArgumentException("bad section format " + format);
        }
        int flag = in.varint();
        byte[] light;
        if (flag == LIGHT_UNIFORM) {
            byte value = (byte) in.byteValue();
            light = new byte[AllvrLodSectionData.CELLS];
            java.util.Arrays.fill(light, value);
        } else if (flag == LIGHT_FULL) {
            light = in.bytes(AllvrLodSectionData.CELLS);
        } else {
            throw new IllegalArgumentException("unknown light flag " + flag);
        }
        int biomeFlag = in.varint();
        int[] biomes = null;
        if (biomeFlag == 1 || biomeFlag == 2) {
            biomes = new int[AllvrLodSectionData.CELLS];
            if (biomeFlag == 1) java.util.Arrays.fill(biomes, in.varint());
            else for (int i = 0; i < biomes.length; i++) biomes[i] = in.varint();
        } else if (biomeFlag != 0) throw new IllegalArgumentException("Bad biome flag " + biomeFlag);
        requireConsumed(in);
        if (indices == null) {
            indices = new int[AllvrLodSectionData.CELLS];
            java.util.Arrays.fill(indices, 1);
        }
        // constructor validates level/position/index-range/light-length too
        return new AllvrLodSectionData(level, cellLong, generation, palette, indices, light, biomes);
    }

    /** The payload must end exactly at the last field — no trailing bytes. */
    private static void requireConsumed(ByteReader in) {
        if (in.remaining() != 0) {
            throw new IllegalArgumentException(in.remaining() + " trailing byte(s) after LOD section payload");
        }
    }

    private static BlockState checkedState(int id) {
        if (id < 0) {
            throw new IllegalArgumentException("unknown vanilla state id " + id);
        }
        // IdMapper#byId returns null for an absent id. Do not use
        // Block.stateById here: that API deliberately maps unknown ids to
        // air, which would turn a malformed payload into valid geometry.
        BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
        if (state == null || state.isAir()) {
            throw new IllegalArgumentException("state id " + id + " resolved to air");
        }
        return state;
    }

    /**
     * Canonical index bit width for a palette that includes air
     * (F02): {@code ceil(log2(size))} clamped to the fixed set
     * {1, 2, 4, 8, 16}. Encode and decode share this exact function, so a
     * payload can never be produced with a width its own decoder rejects.
     */
    static int bitWidth(int paletteSize) {
        if (paletteSize <= 1) {
            return 1; // degenerate: all indices are 0
        }
        int needed = Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1);
        if (needed <= 1) {
            return 1;
        }
        if (needed <= 2) {
            return 2;
        }
        if (needed <= 4) {
            return 4;
        }
        if (needed <= 8) {
            return 8;
        }
        return 16;
    }

    /** LSB-first bit packing of {@code CELLS} values of {@code width} bits. */
    static byte[] pack(int[] values, int width) {
        if (width < 1 || width > 16) {
            throw new IllegalArgumentException("unsupported bit width " + width);
        }
        byte[] out = new byte[(AllvrLodSectionData.CELLS * width + 7) >> 3];
        long acc = 0;
        int bits = 0;
        int pos = 0;
        long mask = (1L << width) - 1;
        for (int value : values) {
            // F02: explicit long math — an int shift with width == 32 would
            // truncate; keeping the accumulator long makes any future width
            // extension safe
            acc |= ((long) value & mask) << bits;
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
        if (width < 1 || width > 16) {
            throw new IllegalArgumentException("unsupported bit width " + width);
        }
        int[] out = new int[AllvrLodSectionData.CELLS];
        long acc = 0;
        int bits = 0;
        int pos = 0;
        long mask = (1L << width) - 1;
        for (int i = 0; i < out.length; i++) {
            while (bits < width) {
                acc |= (packed[pos++] & 0xFFL) << bits;
                bits += 8;
            }
            out[i] = (int) (acc & mask);
            acc >>>= width;
            bits -= width;
        }
        return out;
    }

    private static BlockState airState() {
        return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
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

        int remaining() {
            return this.data.length - this.pos;
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
