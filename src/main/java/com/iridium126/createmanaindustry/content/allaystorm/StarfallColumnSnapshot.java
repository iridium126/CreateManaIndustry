package com.iridium126.createmanaindustry.content.allaystorm;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record StarfallColumnSnapshot(float[] values) {
    public static final int STRIDE = 30, MEMBERS = 64, FLOATS = STRIDE * MEMBERS, BYTES = FLOATS * 4;

    public StarfallColumnSnapshot {
        if (values.length != FLOATS) throw new IllegalArgumentException("Starfall snapshot size");
        values = values.clone();
        for (int rank = 0; rank < MEMBERS; rank++) {
            int offset = rank * STRIDE, phase = (int) values[offset];
            if (values[offset] != phase || phase != 0 && phase != 2 && phase != 4 && phase != 5 && phase != 6)
                throw new IllegalArgumentException("Starfall snapshot phase");
            for (int component = 0; component < STRIDE; component++)
                if (!Float.isFinite(values[offset + component]) || Math.abs(values[offset + component]) > 1049600)
                    throw new IllegalArgumentException("Starfall snapshot value");
            for (int component = 1; component <= 3; component++)
                if (Math.abs(values[offset + component]) > 2048 || Math.abs(values[offset + component + 3]) > 32)
                    throw new IllegalArgumentException("Starfall snapshot motion");
            if (values[offset + 10] < 0 || values[offset + 10] > 4096 || values[offset + 26] < 0 || values[offset + 26] > 4.001)
                throw new IllegalArgumentException("Starfall snapshot progress");
            if (phase == 2 || phase == 5) {
                int direction = offset + (phase == 2 ? 20 : 23);
                float length = 0;
                for (int axis = 0; axis < 3; axis++) length += values[direction + axis] * values[direction + axis];
                if (length < .9f || length > 1.1f) throw new IllegalArgumentException("Starfall snapshot direction");
            }
        }
    }

    @Override public float[] values() { return values.clone(); }

    public StarfallColumnSnapshot completed(java.util.BitSet ranks) {
        float[] updated = values.clone();
        for (int rank = ranks.nextSetBit(0); rank >= 0 && rank < MEMBERS; rank = ranks.nextSetBit(rank + 1)) updated[rank * STRIDE] = 6;
        return new StarfallColumnSnapshot(updated);
    }

    public void write(RegistryFriendlyByteBuf buffer) { for (float value : values) buffer.writeFloat(value); }

    public static StarfallColumnSnapshot read(RegistryFriendlyByteBuf buffer) {
        float[] values = new float[FLOATS];
        for (int index = 0; index < FLOATS; index++) values[index] = buffer.readFloat();
        try { return new StarfallColumnSnapshot(values); }
        catch (IllegalArgumentException exception) { throw new DecoderException(exception); }
    }
}
