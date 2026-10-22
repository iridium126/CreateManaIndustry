package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Integer mB accounting over immutable geometry. No world, networking or animation state. */
public final class FuelTankFluidState {
    private final FuelTankGeometry geometry;
    private final long[] volumes;
    private final double[] levels;
    private final BitSet changed = new BitSet();
    private final BitSet syncChanged = new BitSet();
    private long total;

    public FuelTankFluidState(FuelTankGeometry geometry) {
        this.geometry = geometry;
        volumes = new long[geometry.basins.size()];
        levels = new double[volumes.length];
        for (int i = 0; i < levels.length; i++) levels[i] = geometry.basins.get(i).floor();
    }

    public FuelTankGeometry geometry() { return geometry; }
    public long total() { return total; }
    public long volume(int basin) { return volumes[basin]; }
    public double surface(int basin) { return geometry.origin.getY() + levels[basin]; }
    public double relativeSurface(int basin) { return levels[basin]; }
    public BitSet takeChanged() { BitSet result = (BitSet) changed.clone(); changed.clear(); return result; }

    private void set(int basin, long amount) {
        if (amount < 0 || amount > geometry.basins.get(basin).capacity())
            throw new IllegalArgumentException("Fuel basin capacity exceeded");
        if (volumes[basin] == amount) return;
        total += amount - volumes[basin];
        volumes[basin] = amount;
        levels[basin] = geometry.basins.get(basin).heightFor(amount);
        changed.set(basin);
        syncChanged.set(basin);
    }

    public void settle(long amount) {
        if (amount < 0 || amount > geometry.capacity()) throw new IllegalArgumentException("Fuel group capacity exceeded");
        for (int i = 0; i < volumes.length; i++) set(i, 0);
        if (fill(geometry.lowestBasin(), amount) != 0) throw new IllegalStateException("Disconnected fuel geometry");
    }

    public long fill(BlockPos seed, long amount) {
        return fill(geometry.basinByCell.getOrDefault(seed, geometry.lowestBasin()), amount);
    }

    public long drain(BlockPos seed, long amount) {
        return drain(geometry.basinByCell.getOrDefault(seed, geometry.lowestBasin()), amount);
    }

    private long fill(int seed, long amount) {
        if (volumes.length == 1) {
            long accepted = Math.min(Math.max(0, amount), geometry.capacity() - total);
            set(0, total + accepted); return amount - accepted;
        }
        long remaining = Math.min(Math.max(0, amount), geometry.capacity() - total);
        long rejected = amount - remaining;
        boolean[] region = new boolean[volumes.length]; region[seed] = true;
        expand(region, Double.NEGATIVE_INFINITY);
        double level = maxLevel(region);
        remaining -= raise(region, level, remaining);
        while (remaining > 0) {
            expand(region, level);
            remaining -= raise(region, level, remaining);
            if (remaining == 0) break;
            double next = Double.POSITIVE_INFINITY;
            for (int b = 0; b < volumes.length; b++) if (region[b]) {
                double top = geometry.basins.get(b).ceiling();
                if (top > level) next = Math.min(next, top);
                for (int e = 0; e < geometry.neighborCount(b); e++)
                    if (!region[geometry.neighbor(b, e)] && geometry.spill(b, e) > level)
                        next = Math.min(next, geometry.spill(b, e));
            }
            if (!Double.isFinite(next)) break;
            remaining -= raise(region, next, remaining);
            level = next;
        }
        rebalance();
        return rejected + remaining;
    }

    private long drain(int seed, long amount) {
        if (volumes.length == 1) {
            long taken = Math.min(Math.max(0, amount), total);
            set(0, total - taken); return amount - taken;
        }
        long remaining = Math.min(Math.max(0, amount), total);
        long rejected = amount - remaining;
        boolean[] region = new boolean[volumes.length]; region[seed] = true;
        expand(region, Double.NEGATIVE_INFINITY);
        double level = maxLevel(region);
        while (remaining > 0) {
            double next = Double.NEGATIVE_INFINITY;
            for (int b = 0; b < volumes.length; b++) if (region[b]) {
                if (volumes[b] > 0) next = Math.max(next, geometry.basins.get(b).floor());
                for (int e = 0; e < geometry.neighborCount(b); e++) {
                    int nb = geometry.neighbor(b, e), spill = geometry.spill(b, e);
                    if (!region[nb] && levels[nb] > spill && spill < level) next = Math.max(next, spill);
                }
            }
            if (!Double.isFinite(next) || next >= level) break;
            remaining -= lower(region, next, remaining);
            level = next;
            Arrays.fill(region, false); region[seed] = true;
            expand(region, Double.NEGATIVE_INFINITY);
        }
        // Any port can drain trapped liquid below an exposed saddle, as in the existing tank.
        if (remaining > 0) {
            var order = new ArrayList<Integer>();
            for (int i = 0; i < volumes.length; i++) if (volumes[i] > 0) order.add(i);
            order.sort((a, b) -> Double.compare(levels[b], levels[a]));
            for (int b : order) {
                long take = Math.min(remaining, volumes[b]); set(b, volumes[b] - take); remaining -= take;
                if (remaining == 0) break;
            }
        }
        rebalance();
        return rejected + remaining;
    }

    private void expand(boolean[] region, double reached) {
        boolean added;
        do {
            added = false;
            for (int b = 0; b < volumes.length; b++) if (region[b])
                for (int e = 0; e < geometry.neighborCount(b); e++) {
                    int nb = geometry.neighbor(b, e), spill = geometry.spill(b, e);
                    boolean submerged = reached == Double.NEGATIVE_INFINITY
                        ? spill <= Math.min(levels[b], levels[nb])
                        : volumes[b] > 0 && spill <= Math.min(levels[b], reached);
                    if (!region[nb] && submerged) { region[nb] = true; added = true; }
                }
        } while (added);
    }

    private double maxLevel(boolean[] region) {
        double level = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < levels.length; i++) if (region[i]) level = Math.max(level, levels[i]);
        return level;
    }

    private long need(boolean[] region, double height, boolean fill) {
        long need = 0;
        for (int b = 0; b < volumes.length; b++) if (region[b]) {
            long at = geometry.basins.get(b).volumeAt(height);
            need += Math.max(0, fill ? at - volumes[b] : volumes[b] - at);
        }
        return need;
    }

    private long raise(boolean[] region, double target, long budget) {
        return moveRegion(region, target, budget, true);
    }

    private long lower(boolean[] region, double target, long budget) {
        return moveRegion(region, target, budget, false);
    }

    /** Finds a shared level, then assigns the integer remainder without losing a single mB. */
    private long moveRegion(boolean[] region, double target, long budget, boolean fill) {
        long requested = need(region, target, fill);
        long used = Math.min(budget, requested);
        double height = target;
        if (requested > budget) {
            double lo = target, hi = target;
            for (int b = 0; b < levels.length; b++) if (region[b]) {
                lo = Math.min(lo, levels[b]); hi = Math.max(hi, levels[b]);
            }
            height = fill ? lo : hi;
            for (int i = 0; i < 64; i++) {
                double mid = lo + (hi - lo) * .5;
                if (mid == lo || mid == hi) break;
                long n = need(region, mid, fill);
                if (n <= budget) { height = mid; if (fill) lo = mid; else hi = mid; }
                else if (fill) hi = mid; else lo = mid;
            }
        }
        long left = used;
        for (int b = 0; b < volumes.length; b++) if (region[b]) {
            long at = geometry.basins.get(b).volumeAt(height);
            long delta = Math.min(left, Math.max(0, fill ? at - volumes[b] : volumes[b] - at));
            set(b, volumes[b] + (fill ? delta : -delta)); left -= delta;
        }
        // At most one rounding mB per participating basin at a partial shared level.
        for (int b = 0; b < volumes.length && left > 0; b++) if (region[b]) {
            long atTarget = geometry.basins.get(b).volumeAt(target);
            long delta = Math.min(left, Math.max(0, fill ? atTarget - volumes[b] : volumes[b] - atTarget));
            delta = Math.min(delta, 1);
            set(b, volumes[b] + (fill ? delta : -delta)); left -= delta;
        }
        if (left != 0) throw new IllegalStateException("Unassigned fuel volume: " + left);
        return used;
    }

    private void rebalance() {
        boolean moved;
        int passes = 0;
        do {
            moved = false;
            for (int b = 0; b < volumes.length; b++)
                for (int e = 0; e < geometry.neighborCount(b); e++) {
                    int nb = geometry.neighbor(b, e), spill = geometry.spill(b, e);
                    long above = volumes[b] - geometry.basins.get(b).volumeAt(spill);
                    long room = geometry.basins.get(nb).volumeAt(spill) - volumes[nb];
                    long transfer = Math.min(above, room);
                    if (transfer > 0) { set(b, volumes[b] - transfer); set(nb, volumes[nb] + transfer); moved = true; }
                }
        } while (moved && ++passes < volumes.length * 4);
    }

    /** Exact per-cell shares, in stable geometry order; used only for structural changes. */
    public Map<BlockPos, Long> cellAmounts() {
        Map<BlockPos, Long> result = new HashMap<>();
        for (var basin : geometry.basins) {
            long remaining = volumes[basin.id];
            for (var entry : basin.cellsByLevel().entrySet()) {
                if (remaining == 0) break;
                var cells = entry.getValue();
                long layer = Math.min(remaining, (long) cells.size() * geometry.perBlock);
                long each = layer / cells.size(), extra = layer % cells.size();
                for (int i = 0; i < cells.size(); i++) result.put(cells.get(i), each + (i < extra ? 1 : 0));
                remaining -= layer;
            }
        }
        return result;
    }

    public static FuelTankFluidState remap(FuelTankGeometry geometry, Map<BlockPos, Long> amounts) {
        FuelTankFluidState state = new FuelTankFluidState(geometry);
        long[] raw = new long[geometry.basins.size()];
        amounts.forEach((pos, amount) -> { Integer b = geometry.basinByCell.get(pos); if (b != null) raw[b] += amount; });
        long overflow = 0;
        for (int b = 0; b < raw.length; b++) {
            long amount = Math.min(raw[b], geometry.basins.get(b).capacity());
            state.set(b, amount); overflow += raw[b] - amount;
        }
        if (overflow > 0 && state.fill(geometry.lowestBasin(), overflow) != 0)
            throw new IllegalArgumentException("Remapped fluid exceeds group capacity");
        return state;
    }

    public CompoundTag write() {
        CompoundTag tag = geometry.write(); tag.putIntArray("Volumes", compactVolumes()); return tag;
    }

    private int[] compactVolumes() {
        int[] result = new int[volumes.length];
        for (int b = 0; b < result.length; b++) result[b] = Math.toIntExact(volumes[b]);
        return result;
    }

    public void writeVolumeUpdate(CompoundTag tag) {
        if (syncChanged.cardinality() * 2 >= volumes.length) tag.putIntArray("Volumes", compactVolumes());
        else {
            int[] ids = syncChanged.stream().toArray(), amounts = new int[ids.length];
            for (int i = 0; i < ids.length; i++) amounts[i] = Math.toIntExact(volumes[ids[i]]);
            tag.putIntArray("BasinIds", ids); tag.putIntArray("Volumes", amounts);
        }
    }

    public void clearSyncChanges() { syncChanged.clear(); }

    public void readVolumeUpdate(CompoundTag tag) {
        int[] amounts = tag.getIntArray("Volumes");
        if (!tag.contains("BasinIds")) { readVolumes(Arrays.stream(amounts).asLongStream().toArray()); return; }
        int[] ids = tag.getIntArray("BasinIds");
        if (ids.length != amounts.length) throw new IllegalArgumentException("Mismatched fuel update");
        for (int i = 0; i < ids.length; i++)
            if (ids[i] < 0 || ids[i] >= volumes.length || amounts[i] < 0 || amounts[i] > geometry.basins.get(ids[i]).capacity())
                throw new IllegalArgumentException("Invalid fuel update");
        for (int i = 0; i < ids.length; i++) set(ids[i], amounts[i]);
    }

    public long[] copyVolumes() { return volumes.clone(); }

    public void readVolumes(long[] incoming) {
        if (incoming.length != volumes.length) throw new IllegalArgumentException("Mismatched fuel basin count");
        for (int i = 0; i < incoming.length; i++)
            if (incoming[i] < 0 || incoming[i] > geometry.basins.get(i).capacity())
                throw new IllegalArgumentException("Invalid fuel basin amount");
        for (int i = 0; i < incoming.length; i++) set(i, incoming[i]);
    }

    public static FuelTankFluidState read(CompoundTag tag, BlockPos origin) {
        FuelTankFluidState state = new FuelTankFluidState(FuelTankGeometry.read(tag, origin));
        state.readVolumeUpdate(tag); return state;
    }
}
