package com.iridium126.createmanaindustry.client.dimension.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragmentation-resistant region allocator for the descriptor arena.
 * Allocations never cross a region boundary; a cell mesh is bounded well
 * below the region size. Handles carry a generation so future GPU metadata
 * can reject an ABA reuse instead of drawing a recycled range.
 */
public final class AllvrRegionArena {

    public static final int DEFAULT_REGION_QUADS = 1 << 14;

    public record Handle(int offset, int length, int region, int generation) {}

    private static final class Region {
        final int base;
        final int capacity;
        final ArrayList<long[]> free = new ArrayList<>();
        int used;
        int generation;

        Region(int base, int capacity) {
            this.base = base;
            this.capacity = capacity;
        }
    }

    private final int regionSize;
    private final List<Region> regions = new ArrayList<>();

    public AllvrRegionArena() {
        this(DEFAULT_REGION_QUADS);
    }

    public AllvrRegionArena(int regionSize) {
        if (regionSize <= 0) {
            throw new IllegalArgumentException("region size must be positive");
        }
        this.regionSize = regionSize;
    }

    public int regionCount() {
        return this.regions.size();
    }

    public long capacity() {
        long total = 0;
        for (Region region : this.regions) {
            total += region.capacity;
        }
        return total;
    }

    /** Adds enough independent regions to cover {@code totalQuads}. */
    public void ensureCapacity(long totalQuads) {
        if (totalQuads < 0) {
            throw new IllegalArgumentException("negative arena capacity");
        }
        long capacity = capacity();
        while (capacity < totalQuads) {
            int next = (int) Math.min(regionSize, totalQuads - capacity);
            int base = Math.toIntExact(capacity);
            this.regions.add(new Region(base, next));
            capacity += next;
        }
    }

    public boolean canFit(int length) {
        if (length <= 0 || length > regionSize) {
            return false;
        }
        for (Region region : this.regions) {
            if (region.capacity - region.used >= length) {
                return true;
            }
            for (long[] range : region.free) {
                if (range[1] >= length) {
                    return true;
                }
            }
        }
        return false;
    }

    public Handle allocate(int length) {
        if (length <= 0 || length > regionSize) {
            return null;
        }
        for (int index = 0; index < this.regions.size(); index++) {
            Region region = this.regions.get(index);
            for (int i = 0; i < region.free.size(); i++) {
                long[] range = region.free.get(i);
                if (range[1] < length) {
                    continue;
                }
                int offset = (int) range[0];
                if (range[1] == length) {
                    region.free.remove(i);
                } else {
                    range[0] += length;
                    range[1] -= length;
                }
                return new Handle(region.base + offset, length, index, nextGeneration(region));
            }
            if (region.used + length <= region.capacity) {
                int offset = region.used;
                region.used += length;
                return new Handle(region.base + offset, length, index, nextGeneration(region));
            }
        }
        return null;
    }

    public void free(int offset, int length) {
        if (length <= 0) {
            return;
        }
        for (Region region : this.regions) {
            int local = offset - region.base;
            if (local < 0 || local + length > region.capacity) {
                continue;
            }
            long end = (long) local + length;
            for (int i = 0; i < region.free.size();) {
                long[] range = region.free.get(i);
                if (range[0] + range[1] == local) {
                    local = (int) range[0];
                    length += (int) range[1];
                    region.free.remove(i);
                } else if (end == range[0]) {
                    end = range[0] + range[1];
                    length += (int) range[1];
                    region.free.remove(i);
                } else {
                    i++;
                }
            }
            if (end == region.used) {
                region.used = local;
            } else {
                region.free.add(new long[] {local, length});
            }
            return;
        }
        throw new IllegalArgumentException("range outside region arena: " + offset + "+" + length);
    }

    /** Clears live allocations while retaining already-created region capacity. */
    public void reset() {
        for (Region region : this.regions) {
            region.used = 0;
            region.free.clear();
            region.generation++;
        }
    }

    private static int nextGeneration(Region region) {
        int generation = ++region.generation;
        return generation == 0 ? ++region.generation : generation;
    }
}
