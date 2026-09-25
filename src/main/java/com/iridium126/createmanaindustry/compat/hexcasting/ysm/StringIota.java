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

    private StringIota(String string) {
        super(() -> TYPE);
        this.value = Objects.requireNonNull(string, "string");
    }

    public static StringIota make(String value) { return new StringIota(value); }
    public static StringIota makeUnchecked(String value) { return new StringIota(value); }

    public String getString() { return value; }

    @Override public boolean isTruthy() { return !getString().isEmpty(); }
    @Override protected boolean toleratesOther(Iota other) {
        return typesMatch(this, other) && other instanceof StringIota string && getString().equals(string.getString());
    }
    @Override public int hashCode() { return getString().hashCode(); }
    @Override public Component display() {
        return Component.translatable("createmanaindustry.tooltip.string", getString())
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    public static final IotaType<StringIota> TYPE = new IotaType<>() {
        private final MapCodec<StringIota> codec = Codec.STRING.xmap(StringIota::make, StringIota::getString).fieldOf("value");
        private final StreamCodec<RegistryFriendlyByteBuf, StringIota> streamCodec = ByteBufCodecs.STRING_UTF8
                .map(StringIota::makeUnchecked, StringIota::getString)
                .mapStream(buffer -> buffer);

        @Override public MapCodec<StringIota> codec() { return codec; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, StringIota> streamCodec() { return streamCodec; }
        @Override public int color() { return 0xffff55ff; }
    };
}
