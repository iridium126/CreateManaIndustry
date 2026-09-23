package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometryCodecs;
import com.mojang.serialization.MapCodec;
import at.petrak.hexcasting.api.casting.iota.GarbageIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;

public final class GroupIota extends Iota {
    private final Group value;
    private final List<Iota> children;
    private final int size, depth;
    public GroupIota(Group value) {
        super(() -> TYPE);
        this.value = Objects.requireNonNull(value);
        var children = new ArrayList<Iota>();
        value.cubes().forEach(cube -> children.add(new CubeIota(cube)));
        value.children().forEach(group -> children.add(new GroupIota(group)));
        this.children = List.copyOf(children);
        int metadata = value.name().length() + value.extraJson().length();
        if (value.root() != null) metadata += value.root().descriptionJson().length() + value.root().part().length() + 64;
        this.size = 12 + (metadata + 7) / 8 + children.stream().mapToInt(Iota::size).sum();
        this.depth = 1 + children.stream().mapToInt(Iota::depth).max().orElse(0);
    }
    public Group value() { return value; }
    @Override public boolean isTruthy() { return !children.isEmpty(); }
    @Override protected boolean toleratesOther(Iota other) { return other instanceof GroupIota group && value.equals(group.value); }
    @Override public int hashCode() { return value.hashCode(); }
    @Override public int size() { return size; }
    @Override public int depth() { return depth; }
    @Override public Iterable<Iota> subIotas() { return children; }
    @Override public Component display() { return Component.translatable("createmanaindustry.iota.group", value.name(), value.cubes().size(), value.children().size()); }
    @Override protected Iota visitChildren(UnaryOperator<Iota> visitor) {
        boolean changed = false;
        var cubes = new ArrayList<Cube>(); var groups = new ArrayList<Group>();
        for (int index = 0; index < children.size(); index++) {
            Iota original = children.get(index), replacement = original.visit(visitor);
            changed |= original != replacement;
            if (index < value.cubes().size() && replacement instanceof CubeIota cube) cubes.add(cube.value());
            else if (index >= value.cubes().size() && replacement instanceof GroupIota group) groups.add(group.value());
            else return new GarbageIota();
        }
        if (!changed) return this;
        try {
            return new GroupIota(new Group(value.name(), value.pivot(), value.rotation(), value.scale(), value.visible(), cubes, groups, value.root(), value.extraJson()));
        } catch (IllegalArgumentException failure) { return new GarbageIota(); }
    }
    public static final IotaType<GroupIota> TYPE = new IotaType<>() {
        private final MapCodec<GroupIota> codec = YsmGeometryCodecs.GROUP.xmap(GroupIota::new, GroupIota::value)
                .validate(iota -> IotaType.isTooLargeToSerialize(List.of(iota))
                        ? com.mojang.serialization.DataResult.error(() -> "YSM group exceeds Hexcasting serialization limits")
                        : com.mojang.serialization.DataResult.success(iota)).fieldOf("value");
        private final StreamCodec<RegistryFriendlyByteBuf, GroupIota> stream = YsmGeometryCodecs.GROUP_STREAM.map(GroupIota::new, GroupIota::value);
        @Override public MapCodec<GroupIota> codec() { return codec; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, GroupIota> streamCodec() { return stream; }
        @Override public int color() { return 0xffa9d68b; }
        @Override public boolean usesListCommas() { return true; }
    };
}
