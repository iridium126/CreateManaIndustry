package com.iridium126.createmanaindustry.dimension.cube;

import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.ticks.ScheduledTick;

/** Gameplay only; IO/streaming continues when the server freezes simulation. */
public final class AllvrCubeTicking {
    private final AllvrCubeMap map;
    private final Set<Key> running = new HashSet<>();
    private final List<ScheduledTick<?>> selected = new ArrayList<>();
    public List<ScheduledTick<?>> copySelected(net.minecraft.world.level.levelgen.structure.BoundingBox area, boolean fluids) {
        return selected.stream().filter(tick -> (tick.type() instanceof net.minecraft.world.level.material.Fluid) == fluids && area.isInside(tick.pos())).toList();
    }
    private record Key(Object type, BlockPos pos) {}
    private record Head<T>(AllvrScheduledTicks<T> container, ScheduledTick<T> tick, long due) {}
    public AllvrCubeTicking(AllvrCubeMap map) { this.map = map; }
    public boolean willTick(BlockPos pos, Object type) { return running.contains(new Key(type, pos)); }
    public void clear(net.minecraft.world.level.levelgen.structure.BoundingBox area, boolean fluids) {
        running.removeIf(key -> (key.type instanceof net.minecraft.world.level.material.Fluid) == fluids && area.isInside(key.pos));
        selected.removeIf(tick -> (tick.type() instanceof net.minecraft.world.level.material.Fluid) == fluids && area.isInside(tick.pos()));
    }
    public void tick(long[] active) {
        var level = map.getLevel();
        if (!level.tickRateManager().runsNormally()) return;
        var cubes = new ArrayList<AllvrCube>();
        for (long key : active) {
            var cube = map.getLoadedCube(key);
            if (cube != null) { cubes.add(cube); cube.blockTicks().advance(); cube.fluidTicks().advance(); }
        }
        drain(cubes, AllvrCube::blockTicks, tick -> {
            var state = level.getBlockState(tick.pos());
            if (state.is(tick.type())) state.tick(level, tick.pos(), level.random);
        });
        drain(cubes, AllvrCube::fluidTicks, tick -> {
            var state = level.getFluidState(tick.pos());
            if (state.is(tick.type())) state.tick(level, tick.pos());
        });
        int speed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        if (speed <= 0) return;
        for (var cube : cubes) for (int i = 0; i < AllvrCube.SECTIONS_PER_CUBE; i++) {
            var section = cube.getSections()[i];
            if (!section.isRandomlyTicking()) continue;
            int x = cube.getPos().minBlockX() + ((i & 1) << 4);
            int y = cube.getPos().minBlockY() + (((i >> 2) & 1) << 4);
            int z = cube.getPos().minBlockZ() + (((i >> 1) & 1) << 4);
            for (int n = 0; n < speed; n++) {
                var pos = level.getBlockRandomPos(x, y, z, 15);
                var state = level.getBlockState(pos);
                if (state.isRandomlyTicking()) state.randomTick(level, pos, level.random);
                var fluid = state.getFluidState();
                if (fluid.isRandomlyTicking()) fluid.randomTick(level, pos, level.random);
            }
        }
    }
    private <T> void drain(List<AllvrCube> cubes, Function<AllvrCube, AllvrScheduledTicks<T>> containers,
                           java.util.function.Consumer<ScheduledTick<T>> execute) {
        var heads = new PriorityQueue<Head<T>>(Comparator.<Head<T>>comparingInt(head -> head.tick.priority().getValue())
            .thenComparingLong(head -> head.tick.subTickOrder()));
        for (var cube : cubes) addHead(heads, containers.apply(cube));
        var batch = new ArrayList<ScheduledTick<T>>();
        while (!heads.isEmpty() && batch.size() < 65_536) {
            var head = heads.remove();
            var tick = head.container.pollDue();
            if (tick != null) { batch.add(tick); running.add(new Key(tick.type(), tick.pos())); }
            addHead(heads, head.container);
        }
        selected.addAll(batch);
        try { for (var tick : batch) { if (running.remove(new Key(tick.type(), tick.pos()))) execute.accept(tick); } }
        finally { running.clear(); selected.clear(); }
    }
    private static <T> void addHead(PriorityQueue<Head<T>> heads, AllvrScheduledTicks<T> container) {
        var tick = container.peekDue();
        if (tick != null) heads.add(new Head<>(container, tick, tick.triggerTick() - container.clock()));
    }
}
