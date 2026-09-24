package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.Objects;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** A persistent, network-synchronized string value used by YSM geometry properties. */
public final class StringIota extends Iota {
    private final String value;

    public StringIota(String value) {
        super(() -> TYPE);
        this.value = Objects.requireNonNull(value, "value");
    }

    public String value() { return value; }

    @Override public boolean isTruthy() { return !value.isEmpty(); }
    @Override protected boolean toleratesOther(Iota other) {
        return other instanceof StringIota string && value.equals(string.value);
    }
    @Override public int hashCode() { return value.hashCode(); }
    @Override public int size() { return 1; }
    @Override public int depth() { return 1; }
    @Override public Component display() {
        return Component.literal(quoted(value)).withStyle(ChatFormatting.GREEN);
    }

    private static String quoted(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (Character.isISOControl(codePoint)) result.append(String.format("\\u%04x", codePoint));
                    else result.appendCodePoint(codePoint);
                }
            }
        });
        return result.append('"').toString();
    }

    public static final IotaType<StringIota> TYPE = new IotaType<>() {
        private final MapCodec<StringIota> codec = Codec.STRING.xmap(StringIota::new, StringIota::value).fieldOf("value");
        private final StreamCodec<RegistryFriendlyByteBuf, StringIota> streamCodec = ByteBufCodecs.STRING_UTF8
                .map(StringIota::new, StringIota::value)
                .mapStream(buffer -> buffer);

        @Override public MapCodec<StringIota> codec() { return codec; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, StringIota> streamCodec() { return streamCodec; }
        @Override public int color() { return 0xff_9fda7c; }
        @Override public boolean usesListCommas() { return false; }
    };
}
