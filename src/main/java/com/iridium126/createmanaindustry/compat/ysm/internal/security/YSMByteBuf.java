package com.iridium126.createmanaindustry.compat.ysm.internal.security;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/** Bounded little-endian reader for the OpenYSM-derived resource parser. */
public final class YSMByteBuf implements AutoCloseable {
    private final ByteBuffer data;
    public YSMByteBuf(byte[] bytes) {
        data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
    public int getOffset() { return data.position(); }
    public int remaining() { return data.remaining(); }
    public byte readByte() { return data.get(); }
    public long readDword() { return Integer.toUnsignedLong(data.getInt()); }
    public float readFloat() {
        // Resource fields include animation sentinels. Geometry values are validated
        // when constructing editable vectors; do not alter unrelated animation bits.
        return data.getFloat();
    }
    public int readVarInt() {
        int value = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            int next = Byte.toUnsignedInt(data.get());
            if (shift == 28 && (next & 0xf0) != 0) throw new IllegalArgumentException("YSM VarInt overflow");
            value |= (next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new IllegalArgumentException("Oversized YSM VarInt");
    }
    public long readVarLong() {
        long value = 0;
        for (int shift = 0; shift <= 63; shift += 7) {
            int next = Byte.toUnsignedInt(data.get());
            if (shift == 63 && (next & 0xfe) != 0) throw new IllegalArgumentException("YSM VarLong overflow");
            value |= (long)(next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new IllegalArgumentException("Oversized YSM VarLong");
    }
    public int readCount() {
        int count = readVarInt();
        if (count < 0 || count > data.remaining()) throw new IllegalArgumentException("Invalid YSM collection length");
        return count;
    }
    public byte[] readByteArray() {
        int length = readVarInt();
        if (length < 0 || length > data.remaining()) throw new IllegalArgumentException("Invalid YSM byte array length");
        byte[] bytes = new byte[length]; data.get(bytes); return bytes;
    }
    public String readString() {
        int length = readVarInt();
        if (length < 0 || length > data.remaining()) throw new IllegalArgumentException("Invalid YSM string length");
        ByteBuffer slice = data.slice(); slice.limit(length);
        try {
            String value = StandardCharsets.UTF_8.newDecoder().decode(slice).toString();
            data.position(data.position() + length);
            return value;
        } catch (CharacterCodingException failure) { throw new IllegalArgumentException("Invalid YSM UTF-8", failure); }
    }
    public void skipBytes(int count) {
        if (count < 0 || count > data.remaining()) throw new IllegalArgumentException("Invalid YSM skip length");
        data.position(data.position() + count);
    }
    @Override public void close() { }
}
