package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.Objects;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;

/** A compact handle to an immutable cube node in the current world's YSM store. */
public final class CubeIota extends Iota {
    private final String key;

    public CubeIota(String key) {
        super(() -> TYPE);
        if (!YsmReferenceIotaCodecs.validKey(key)) throw new IllegalArgumentException("Invalid YSM cube reference");
        this.key = Objects.requireNonNull(key);
    }

    public String key() { return key; }
    @Override public boolean isTruthy() { return true; }
    @Override protected boolean toleratesOther(Iota other) { return other instanceof CubeIota cube && key.equals(cube.key); }
    @Override public int hashCode() { return key.hashCode(); }
    @Override public Component display() { return YsmIotaDisplay.cube(key,
            Component.translatable("createmanaindustry.iota.cube", key.substring(0, 12))); }

    public static final IotaType<CubeIota> TYPE = new IotaType<>() {
        private final MapCodec<CubeIota> codec = YsmReferenceIotaCodecs.persistent(CubeIota::new, CubeIota::key).fieldOf("value");
        private final StreamCodec<RegistryFriendlyByteBuf, CubeIota> stream = YsmReferenceIotaCodecs.network(CubeIota::new, CubeIota::key);
        @Override public MapCodec<CubeIota> codec() { return codec; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, CubeIota> streamCodec() { return stream; }
        @Override public int color() { return 0xff_88cdf6; }
    };
}
