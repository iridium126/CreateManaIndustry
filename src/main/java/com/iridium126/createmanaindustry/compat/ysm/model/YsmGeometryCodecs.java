package com.iridium126.createmanaindustry.compat.ysm.model;

import java.util.Base64;
import java.util.function.Function;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;

/** Both paths reject malformed or oversized data; neither substitutes another model. */
public final class YsmGeometryCodecs {
    public static final Codec<Cube> CUBE = codec(YsmGeometryIO::decodeCube, YsmGeometryIO::encodeCube);
    public static final Codec<Group> GROUP = codec(YsmGeometryIO::decodeGroup, YsmGeometryIO::encodeGroup);
    public static final StreamCodec<RegistryFriendlyByteBuf, Cube> CUBE_STREAM = stream(YsmGeometryIO::decodeCube, YsmGeometryIO::encodeCube);
    public static final StreamCodec<RegistryFriendlyByteBuf, Group> GROUP_STREAM = stream(YsmGeometryIO::decodeGroup, YsmGeometryIO::encodeGroup);

    private static <T> Codec<T> codec(Function<byte[], T> decode, Function<T, byte[]> encode) {
        return Codec.STRING.flatXmap(value -> {
            if (value.length() > ((YsmGeometryIO.MAX_BYTES + 2) / 3) * 4)
                return DataResult.error(() -> "YSM geometry exceeds byte limit");
            try { return DataResult.success(decode.apply(Base64.getDecoder().decode(value))); }
            catch (IllegalArgumentException failure) { return DataResult.error(() -> "Invalid YSM geometry: " + failure.getMessage()); }
        }, value -> {
            try { return DataResult.success(Base64.getEncoder().encodeToString(encode.apply(value))); }
            catch (IllegalArgumentException failure) { return DataResult.error(() -> "Invalid YSM geometry: " + failure.getMessage()); }
        });
    }
    private static <T> StreamCodec<RegistryFriendlyByteBuf, T> stream(Function<byte[], T> decode, Function<T, byte[]> encode) {
        return new StreamCodec<>() {
            @Override public T decode(RegistryFriendlyByteBuf buffer) {
                return decode.apply(buffer.readByteArray(YsmGeometryIO.MAX_BYTES));
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, T value) {
                buffer.writeByteArray(encode.apply(value));
            }
        };
    }
    private YsmGeometryCodecs() {}
}
