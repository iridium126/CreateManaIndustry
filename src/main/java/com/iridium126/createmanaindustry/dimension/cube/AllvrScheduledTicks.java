package com.iridium126.createmanaindustry.dimension.cube;

import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ticks.*;

/** Cube-local simulation clock: inactive/unloaded time never consumes a delayed tick. */
public final class AllvrScheduledTicks<T> {
    private final PriorityQueue<ScheduledTick<T>> queue = new PriorityQueue<>(ScheduledTick.DRAIN_ORDER);
    private final Set<Key<T>> keys = new HashSet<>();
    private final Runnable changed;
    private long clock;
    private record Key<T>(T type, BlockPos pos) {}
    public AllvrScheduledTicks(Runnable changed) { this.changed = changed; }
    public void advance() { clock++; if (!queue.isEmpty()) changed.run(); }
    public long clock() { return clock; }
    public boolean schedule(T type, BlockPos pos, long delay, TickPriority priority, long order) {
        pos = pos.immutable();
        if (!keys.add(new Key<>(type, pos))) return false;
        queue.add(new ScheduledTick<>(type, pos, clock + Math.min(Long.MAX_VALUE - clock, Math.max(1, delay)), priority, order));
        changed.run(); return true;
    }
    public boolean has(BlockPos pos, T type) { return keys.contains(new Key<>(type, pos)); }
    public ScheduledTick<T> peekDue() { return !queue.isEmpty() && queue.peek().triggerTick() <= clock ? queue.peek() : null; }
    public ScheduledTick<T> pollDue() {
        ScheduledTick<T> tick = peekDue();
        if (tick != null) { queue.remove(); keys.remove(new Key<>(tick.type(), tick.pos())); changed.run(); }
        return tick;
    }
    public void clear(net.minecraft.world.level.levelgen.structure.BoundingBox area) {
        if (queue.removeIf(tick -> {
            if (!area.isInside(tick.pos())) return false;
            keys.remove(new Key<>(tick.type(), tick.pos())); return true;
        })) changed.run();
    }
    public int size() { return queue.size(); }
    public List<ScheduledTick<T>> snapshot() { return queue.stream().sorted(ScheduledTick.DRAIN_ORDER).toList(); }
    public long remaining(ScheduledTick<T> tick) { return Math.max(0, tick.triggerTick() - clock); }
    public ListTag save(Function<T, ResourceLocation> id) {
        var result = new ListTag();
        for (var tick : snapshot()) {
            var tag = new CompoundTag(); tag.putString("i", id.apply(tick.type()).toString());
            tag.putShort("p", (short) AllvrCube.localIndex(tick.pos()));
            tag.putLong("t", remaining(tick)); tag.putInt("priority", tick.priority().getValue());
            tag.putLong("order", tick.subTickOrder()); result.add(tag);
        }
        return result;
    }
    public void load(ListTag data, AllvrCubePos cube, Function<ResourceLocation, T> lookup) {
        for (int i = 0; i < data.size(); i++) {
            var tag = data.getCompound(i);
            int cell = Short.toUnsignedInt(tag.getShort("p"));
            var id = ResourceLocation.tryParse(tag.getString("i"));
            T type = id == null ? null : lookup.apply(id);
            if (!tag.contains("p", Tag.TAG_ANY_NUMERIC) || !tag.contains("i", Tag.TAG_STRING) || !tag.contains("t", Tag.TAG_ANY_NUMERIC)
                || !tag.contains("priority", Tag.TAG_ANY_NUMERIC) || !tag.contains("order", Tag.TAG_ANY_NUMERIC)
                || tag.getInt("priority") < -3 || tag.getInt("priority") > 3 || cell > 32767 || type == null || tag.getLong("t") < 0) throw new IllegalArgumentException("Invalid cube tick");
            BlockPos pos = new BlockPos(cube.minBlockX() + (cell & 31), cube.minBlockY() + (cell >> 10), cube.minBlockZ() + ((cell >> 5) & 31));
            if (!keys.add(new Key<>(type, pos))) throw new IllegalArgumentException("Duplicate cube tick");
            queue.add(new ScheduledTick<>(type, pos, clock + tag.getLong("t"), TickPriority.byValue(tag.getInt("priority")), tag.getLong("order")));
        }
    }
}
