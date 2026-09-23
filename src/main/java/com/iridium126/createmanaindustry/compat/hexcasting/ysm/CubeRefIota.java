package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.Objects;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;

/** A compact handle to an immutable cube node in the current world's YSM store. */
public final class CubeRefIota extends Iota {
    private final String key;

    public CubeRefIota(String key) {
        super(() -> TYPE);
        if (!YsmReferenceIotaCodecs.validKey(key)) throw new IllegalArgumentException("Invalid YSM cube reference");
        this.key = Objects.requireNonNull(key);
    }

    public String key() { return key; }
    @Override public boolean isTruthy() { return true; }
    @Override protected boolean toleratesOther(Iota other) { return other instanceof CubeRefIota cube && key.equals(cube.key); }
    @Override public int hashCode() { return key.hashCode(); }
    @Override public int size() { return 1; }
    @Override public int depth() { return 1; }
    @Override public Component display() { return Component.translatable("createmanaindustry.iota.cube_ref", key.substring(0, 12)); }

    public static final IotaType<CubeRefIota> TYPE = new IotaType<>() {
        private final MapCodec<CubeRefIota> codec = YsmReferenceIotaCodecs.persistent(CubeRefIota::new, CubeRefIota::key).fieldOf("value");
        private final StreamCodec<RegistryFriendlyByteBuf, CubeRefIota> stream = YsmReferenceIotaCodecs.network(CubeRefIota::new, CubeRefIota::key);
        @Override public MapCodec<CubeRefIota> codec() { return codec; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, CubeRefIota> streamCodec() { return stream; }
        @Override public int color() { return 0xff71c8d6; }
        @Override public boolean usesListCommas() { return true; }
    };
}
