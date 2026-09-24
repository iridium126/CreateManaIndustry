package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.Objects;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;

/** A compact handle to an immutable group node in the current world's YSM store. */
public final class GroupIota extends Iota {
    private final String key;

    public GroupIota(String key) {
        super(() -> TYPE);
        if (!YsmReferenceIotaCodecs.validKey(key)) throw new IllegalArgumentException("Invalid YSM group reference");
        this.key = Objects.requireNonNull(key);
    }

    public String key() { return key; }
    @Override public boolean isTruthy() { return true; }
    @Override protected boolean toleratesOther(Iota other) { return other instanceof GroupIota group && key.equals(group.key); }
    @Override public int hashCode() { return key.hashCode(); }
    @Override public int size() { return 1; }
    @Override public int depth() { return 1; }
    @Override public Component display() { return YsmIotaDisplay.group(key,
            Component.translatable("createmanaindustry.iota.group", key.substring(0, 12))); }

    public static final IotaType<GroupIota> TYPE = new IotaType<>() {
        private final MapCodec<GroupIota> codec = YsmReferenceIotaCodecs.persistent(GroupIota::new, GroupIota::key).fieldOf("value");
        private final StreamCodec<RegistryFriendlyByteBuf, GroupIota> stream = YsmReferenceIotaCodecs.network(GroupIota::new, GroupIota::key);
        @Override public MapCodec<GroupIota> codec() { return codec; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, GroupIota> streamCodec() { return stream; }
        @Override public int color() { return 0xffa9d68b; }
        @Override public boolean usesListCommas() { return true; }
    };
}
