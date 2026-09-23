package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.Objects;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Cube;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometryCodecs;
import com.mojang.serialization.MapCodec;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;

public final class CubeIota extends Iota {
    private final Cube value;
    public CubeIota(Cube value) { super(() -> TYPE); this.value = Objects.requireNonNull(value); }
    public Cube value() { return value; }
    @Override public boolean isTruthy() { return value.visible(); }
    @Override protected boolean toleratesOther(Iota other) { return other instanceof CubeIota cube && value.equals(cube.value); }
    @Override public int hashCode() { return value.hashCode(); }
    @Override public int size() { return 1; }
    @Override public int depth() { return 1; }
    @Override public Component display() { return Component.translatable("createmanaindustry.iota.cube", value.size().x(), value.size().y(), value.size().z()); }
    public static final IotaType<CubeIota> TYPE = new IotaType<>() {
        private final MapCodec<CubeIota> codec = YsmGeometryCodecs.CUBE.xmap(CubeIota::new, CubeIota::value)
                .validate(iota -> IotaType.isTooLargeToSerialize(java.util.List.of(iota))
                        ? com.mojang.serialization.DataResult.error(() -> "YSM cube exceeds Hexcasting serialization limits")
                        : com.mojang.serialization.DataResult.success(iota)).fieldOf("value");
        private final StreamCodec<RegistryFriendlyByteBuf, CubeIota> stream = YsmGeometryCodecs.CUBE_STREAM.map(CubeIota::new, CubeIota::value);
        @Override public MapCodec<CubeIota> codec() { return codec; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, CubeIota> streamCodec() { return stream; }
        @Override public int color() { return 0xff71c8d6; }
        @Override public boolean usesListCommas() { return true; }
    };
}
