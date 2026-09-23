package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
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
    public GroupIota(Group value) {
        this(buildTree(Objects.requireNonNull(value)));
    }
    private GroupIota(GroupIota built) {
        super(() -> TYPE);
        this.value = built.value;
        this.children = built.children;
    }
    private GroupIota(Group value, List<Iota> children) {
        super(() -> TYPE);
        this.value = value;
        this.children = List.copyOf(children);
    }
    private static GroupIota buildTree(Group root) {
        record Frame(Group group, boolean expanded) {}
        var built = new IdentityHashMap<Group, GroupIota>();
        var pending = new ArrayDeque<Frame>();
        pending.push(new Frame(root, false));
        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            if (!frame.expanded()) {
                pending.push(new Frame(frame.group(), true));
                List<Group> groups = frame.group().children();
                for (int index = groups.size() - 1; index >= 0; index--)
                    pending.push(new Frame(groups.get(index), false));
                continue;
            }
            var children = new ArrayList<Iota>(frame.group().cubes().size() + frame.group().children().size());
            frame.group().cubes().forEach(cube -> children.add(new CubeIota(cube)));
            for (Group child : frame.group().children()) children.add(built.get(child));
            built.put(frame.group(), new GroupIota(frame.group(), children));
        }
        return built.get(root);
    }
    public Group value() { return value; }
    @Override public boolean isTruthy() { return !children.isEmpty(); }
    @Override protected boolean toleratesOther(Iota other) { return other instanceof GroupIota group && value.equals(group.value); }
    @Override public int hashCode() { return value.hashCode(); }
    @Override public int size() { return 1; }
    @Override public int depth() { return 1; }
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
        private final MapCodec<GroupIota> codec = YsmGeometryCodecs.GROUP.xmap(GroupIota::new, GroupIota::value).fieldOf("value");
        private final StreamCodec<RegistryFriendlyByteBuf, GroupIota> stream = YsmGeometryCodecs.GROUP_STREAM.map(GroupIota::new, GroupIota::value);
        @Override public MapCodec<GroupIota> codec() { return codec; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, GroupIota> streamCodec() { return stream; }
        @Override public int color() { return 0xffa9d68b; }
        @Override public boolean usesListCommas() { return true; }
    };
}
