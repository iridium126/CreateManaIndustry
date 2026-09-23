package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HexFormat;
import java.util.function.Function;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Compact codecs shared by the world-backed group and cube handles. */
final class YsmReferenceIotaCodecs {
    private static final Codec<Integer> VERSION = Codec.INT.validate(value -> value == 1
            ? DataResult.success(value) : DataResult.error(() -> "Unsupported YSM reference version"));
    private static final Codec<String> KEY = Codec.STRING.validate(value -> validKey(value)
            ? DataResult.success(value) : DataResult.error(() -> "Invalid YSM reference key"));

    static <T> MapCodec<T> persistent(Function<String, T> create, Function<T, String> key) {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                VERSION.fieldOf("version").forGetter(value -> 1),
                KEY.fieldOf("key").forGetter(key))
                .apply(instance, (version, value) -> create.apply(value)));
    }

    static <T> StreamCodec<RegistryFriendlyByteBuf, T> network(Function<String, T> create, Function<T, String> key) {
        return StreamCodec.of((buffer, value) -> buffer.writeBytes(HexFormat.of().parseHex(key.apply(value))), buffer -> {
            byte[] bytes = new byte[32];
            buffer.readBytes(bytes);
            return create.apply(HexFormat.of().formatHex(bytes));
        });
    }

    static boolean validKey(String key) { return key != null && key.matches("[0-9a-f]{64}"); }
    private YsmReferenceIotaCodecs() {}
}
