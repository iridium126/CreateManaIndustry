package com.iridium126.createmanaindustry.dimension.cube;

import java.util.*;
import java.util.function.Function;
import java.util.stream.*;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.core.*;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.phys.AABB;

/** Full-height spatial index. Handles are private to one manager, never packed coordinates. */
public final class AllvrEntitySectionStorage<T extends EntityAccess> extends EntitySectionStorage<T> {
    private final Class<T> type;
    private final Long2ObjectFunction<Visibility> nativeVisibility;
    private Function<AllvrSectionCoord, Visibility> cubeVisibility;
    private final Map<AllvrSectionCoord, Long> identities = new HashMap<>();
    private final Long2ObjectOpenHashMap<AllvrSectionCoord> coordinates = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<EntitySection<T>> sections = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<NavigableMap<Integer, Long>> columns = new Long2ObjectOpenHashMap<>();
    private final NavigableMap<Integer, NavigableMap<Integer, NavigableMap<Integer, Long>>> spatial = new TreeMap<>();
    private long nextHandle, revision;
    public long revision() { return revision; }
    public AllvrEntitySectionStorage(Class<T> type, Long2ObjectFunction<Visibility> visibility) {
        super(type, visibility); this.type = type; nativeVisibility = visibility;
    }
    public boolean enabled() { return cubeVisibility != null; }
    public void enable(Function<AllvrSectionCoord, Visibility> visibility) {
        if (!enabled() && super.count() != 0) throw new IllegalStateException("Entity index enabled after entities were added");
        cubeVisibility = visibility;
    }
    public long key(BlockPos pos) {
        if (!enabled()) return SectionPos.asLong(pos);
        var coord = AllvrSectionCoord.of(pos);
        return identities.computeIfAbsent(coord, value -> { long id = ++nextHandle; coordinates.put(id, value); return id; });
    }
    public AllvrSectionCoord coordinate(long handle) {
        if (!enabled()) return new AllvrSectionCoord(SectionPos.x(handle), SectionPos.y(handle), SectionPos.z(handle));
        var coord = coordinates.get(handle);
        if (coord == null) throw new IllegalStateException("Unknown entity section handle " + handle);
        return coord;
    }
    @Override public EntitySection<T> getOrCreateSection(long handle) {
        if (!enabled()) return super.getOrCreateSection(handle);
        return sections.computeIfAbsent(handle, id -> {
            revision++;
            var coord = coordinate(id);
            var ys = columns.computeIfAbsent(coord.columnKey(), key -> new TreeMap<>());
            ys.put(coord.y(), id);
            spatial.computeIfAbsent(coord.x(), x -> new TreeMap<>()).put(coord.z(), ys);
            var visibility = com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isVanillaSection(coord.y())
                ? nativeVisibility.get(coord.columnKey()) : cubeVisibility.apply(coord);
            return new EntitySection<>(type, visibility);
        });
    }
    @Override public EntitySection<T> getSection(long key) { return enabled() ? sections.get(key) : super.getSection(key); }
    @Override public void remove(long key) {
        if (!enabled()) { super.remove(key); return; }
        if (sections.remove(key) != null) revision++;
        var coord = coordinates.remove(key);
        if (coord == null) return;
        identities.remove(coord);
        var ys = columns.get(coord.columnKey());
        if (ys != null) {
            ys.remove(coord.y());
            if (ys.isEmpty()) {
                columns.remove(coord.columnKey());
                var zs = spatial.get(coord.x()); zs.remove(coord.z()); if (zs.isEmpty()) spatial.remove(coord.x());
            }
        }
    }
    @Override public LongStream getExistingSectionPositionsInChunk(long key) {
        if (!enabled()) return super.getExistingSectionPositionsInChunk(key);
        var ys = columns.get(key);
        return ys == null ? LongStream.empty() : LongStream.of(ys.values().stream().mapToLong(Long::longValue).toArray());
    }
    @Override public Stream<EntitySection<T>> getExistingSectionsInChunk(long key) {
        return enabled() ? getExistingSectionPositionsInChunk(key).mapToObj(sections::get).filter(Objects::nonNull) : super.getExistingSectionsInChunk(key);
    }
    @Override public LongSet getAllChunksWithExistingSections() { return enabled() ? new LongOpenHashSet(columns.keySet()) : super.getAllChunksWithExistingSections(); }
    @Override public int count() { return enabled() ? sections.size() : super.count(); }
    public int identityCount() { return identities.size(); }
    @Override public void forEachAccessibleNonEmptySection(AABB box, AbortableIterationConsumer<EntitySection<T>> consumer) {
        if (!enabled()) { super.forEachAccessibleNonEmptySection(box, consumer); return; }
        int minX = SectionPos.posToSectionCoord(box.minX - 2), maxX = SectionPos.posToSectionCoord(box.maxX + 2);
        int minZ = SectionPos.posToSectionCoord(box.minZ - 2), maxZ = SectionPos.posToSectionCoord(box.maxZ + 2);
        int minY = SectionPos.posToSectionCoord(box.minY - 4), maxY = SectionPos.posToSectionCoord(box.maxY);
        for (var zs : spatial.subMap(minX, true, maxX, true).values())
            for (var ys : zs.subMap(minZ, true, maxZ, true).values())
                for (long id : ys.subMap(minY, true, maxY, true).values()) {
                    var section = sections.get(id);
                    if (section != null && !section.isEmpty() && section.getStatus().isAccessible() && consumer.accept(section).shouldAbort()) return;
                }
    }
}
