package com.iridium126.createmanaindustry.content.allaystorm.network;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;

final class StormPositionsCodec {
    static final int STRIDE = 8, MAX_ENTRIES = 256;
    static final float MAX_VELOCITY = 32;

    private StormPositionsCodec() {}

    static void write(RegistryFriendlyByteBuf buffer, float[] entries) {
        if (entries.length % STRIDE != 0 || entries.length / STRIDE > MAX_ENTRIES)
            throw new EncoderException("Invalid storm snapshot size");
        buffer.writeVarInt(entries.length / STRIDE);
        for (int offset = 0; offset < entries.length; offset += STRIDE) {
            float member = entries[offset];
            if (!Float.isFinite(member) || member < 0 || member >= 131072 || member != (int) member)
                throw new EncoderException("Invalid storm member");
            buffer.writeVarInt((int) member);
            for (int component = 2; component < STRIDE; component++) {
                float value = entries[offset + component];
                if (!Float.isFinite(value) || component >= 5 && Math.abs(value) > MAX_VELOCITY)
                    throw new EncoderException("Invalid storm position or velocity");
                buffer.writeShort(Math.clamp(Math.round(value * 16), Short.MIN_VALUE, Short.MAX_VALUE));
            }
        }
    }

    static float[] read(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new DecoderException("Invalid storm snapshot size");
        float[] entries = new float[count * STRIDE];
        for (int offset = 0; offset < entries.length; offset += STRIDE) {
            int member = buffer.readVarInt();
            if (member < 0 || member >= 131072) throw new DecoderException("Invalid storm member");
            entries[offset] = member;
            for (int component = 2; component < STRIDE; component++) {
                float value = buffer.readShort() / 16f;
                if (component >= 5 && Math.abs(value) > MAX_VELOCITY) throw new DecoderException("Invalid storm velocity");
                entries[offset + component] = value;
            }
        }
        return entries;
    }
}
