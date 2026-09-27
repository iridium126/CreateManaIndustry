package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.BooleanIota;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.EntityIota;
import at.petrak.hexcasting.api.casting.iota.GarbageIota;
import at.petrak.hexcasting.api.casting.iota.NullIota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import java.util.List;

/** Exact indexed equivalent of the upstream serialization-bound scan for immutable VM collections. */
public final class IotaStackValidation {
    private static final Class<?> EMPTY_IMMUTABLE_LIST = List.of().getClass();
    private static final Class<?> ONE_IMMUTABLE_LIST = List.of(Boolean.TRUE).getClass();
    private static final Class<?> TWO_IMMUTABLE_LIST = List.of(Boolean.TRUE, Boolean.FALSE).getClass();
    private static final Class<?> MANY_IMMUTABLE_LIST = List.of(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE).getClass();
    private IotaStackValidation() {}

    /** A small cache whose lifetime is restricted to one CastingVM queue execution. */
    public static final class MetricCache {
        private Object[][] cachedSegments;
        // Positive values are known-unit element counts; -1 marks an immutable segment
        // containing an extension Iota whose metrics must still be called in order.
        private int[] cachedUnitCounts;
        private Object[][] currentRoot;
        private Object[][] currentSegments;
        private int[] currentUnitCounts;
        private Object[] cachedPrefix;
        private int cachedPrefixUnits;
        private int cachedSize;
        private int currentSize;

        private void begin(Object[][] root) {
            if (currentRoot != root) {
                currentRoot = root;
                currentSize = 0;
            }
        }

        private int get(int index, Object[] segment) {
            if (index < currentSize && currentSegments[index] == segment) return currentUnitCounts[index];
            for (int i = 0; i < cachedSize; i++) {
                if (cachedSegments[i] == segment) {
                    rememberCurrent(index, segment, cachedUnitCounts[i]);
                    return cachedUnitCounts[i];
                }
            }
            return 0;
        }

        private void put(int index, Object[] segment, int unitCount) {
            if (cachedSegments == null) {
                cachedSegments = new Object[16][];
                cachedUnitCounts = new int[16];
                currentSegments = new Object[16][];
                currentUnitCounts = new int[16];
            }
            if (cachedSize < cachedSegments.length) {
                cachedSegments[cachedSize] = segment;
                cachedUnitCounts[cachedSize] = unitCount;
                cachedSize++;
            }
            rememberCurrent(index, segment, unitCount);
        }

        private int cachePrefix(Object[] prefix) {
            if (cachedPrefix == prefix) return cachedPrefixUnits;
            int unitCount = prefix.length;
            for (Object value : prefix) {
                if (!hasDefaultUnitMetrics(value.getClass())) {
                    unitCount = -1;
                    break;
                }
            }
            cachedPrefix = prefix;
            cachedPrefixUnits = unitCount;
            return unitCount;
        }

        private void rememberCurrent(int index, Object[] segment, int unitCount) {
            if (index < currentSegments.length) {
                currentSegments[index] = segment;
                currentUnitCounts[index] = unitCount;
                currentSize = Math.max(currentSize, index + 1);
            }
        }
    }

    private static boolean hasDefaultUnitMetrics(Class<?> type) {
        return type == DoubleIota.class || type == PatternIota.class || type == BooleanIota.class
                || type == NullIota.class || type == EntityIota.class || type == Vec3Iota.class
                || type == GarbageIota.class;
    }

    public static boolean isTooLarge(Iterable<Iota> stack) {
        if (stack instanceof TreeList<?> tree) return isTooLarge(tree);
        if (stack instanceof List<?> list && isJdkImmutableList(list)) return isTooLarge(list);
        return IotaType.isTooLargeToSerialize(stack);
    }

    public static boolean isTooLarge(Iterable<Iota> stack, MetricCache cache) {
        if (cache != null && stack instanceof TreeList<?> tree
                && (Object) tree instanceof TreeList2Access segments) return isTooLarge(segments, cache);
        return isTooLarge(stack);
    }

    private static boolean isJdkImmutableList(List<?> list) {
        Class<?> type = list.getClass();
        return type == EMPTY_IMMUTABLE_LIST || type == ONE_IMMUTABLE_LIST
                || type == TWO_IMMUTABLE_LIST || type == MANY_IMMUTABLE_LIST;
    }

    private static boolean isTooLarge(TreeList<?> tree) {
        if ((Object) tree instanceof TreeList2Access segments) return isTooLarge(segments);
        int totalSize = 1;
        int count = tree.size();
        for (int index = 0; index < count; index++) {
            Iota iota = (Iota) tree.get(index);
            Class<?> type = iota.getClass();
            boolean defaultMetrics = hasDefaultUnitMetrics(type);
            int depth = defaultMetrics ? 1 : iota.depth();
            if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
            totalSize += defaultMetrics ? 1 : iota.size();
        }
        return totalSize >= HexIotaTypes.MAX_SERIALIZATION_TOTAL;
    }

    private static boolean isTooLarge(TreeList2Access tree) {
        return isTooLarge(tree, null);
    }

    private static boolean isTooLarge(TreeList2Access tree, MetricCache cache) {
        int totalSize = 1;
        Object[] prefix = tree.cmi$getPrefix1();
        if (cache != null && prefix.length == tree.cmi$getLen1()) {
            int cachedUnits = cache.cachePrefix(prefix);
            if (cachedUnits > 0) {
                if (1 >= HexIotaTypes.MAX_SERIALIZATION_DEPTH && cachedUnits > 0) return true;
                totalSize += cachedUnits;
            } else {
                for (Object value : prefix) {
                    Iota iota = (Iota) value;
                    Class<?> type = iota.getClass();
                    boolean defaultMetrics = hasDefaultUnitMetrics(type);
                    int depth = defaultMetrics ? 1 : iota.depth();
                    if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
                    totalSize += defaultMetrics ? 1 : iota.size();
                }
            }
        } else {
            for (int index = 0, count = tree.cmi$getLen1(); index < count; index++) {
                Iota iota = (Iota) prefix[index];
                Class<?> type = iota.getClass();
                boolean defaultMetrics = hasDefaultUnitMetrics(type);
                int depth = defaultMetrics ? 1 : iota.depth();
                if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
                totalSize += defaultMetrics ? 1 : iota.size();
            }
        }
        Object[][] middle = tree.cmi$getData2();
        if (cache != null) cache.begin(middle);
        for (int index = 0; index < middle.length; index++) {
            Object[] segment = middle[index];
            int cachedUnits = cache == null ? 0 : cache.get(index, segment);
            if (cachedUnits > 0) {
                if (1 >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
                totalSize += cachedUnits;
                continue;
            }
            boolean unitMetrics = cachedUnits != -1;
            if (cache != null && cachedUnits == 0) {
                for (Object value : segment) {
                    if (!hasDefaultUnitMetrics(value.getClass())) {
                        unitMetrics = false;
                        break;
                    }
                }
                cache.put(index, segment, unitMetrics ? segment.length : -1);
            }
            if (unitMetrics && cache != null) {
                if (1 >= HexIotaTypes.MAX_SERIALIZATION_DEPTH && segment.length > 0) return true;
                totalSize += segment.length;
                continue;
            }
            for (Object value : segment) {
                Iota iota = (Iota) value;
                Class<?> type = iota.getClass();
                boolean defaultMetrics = hasDefaultUnitMetrics(type);
                int depth = defaultMetrics ? 1 : iota.depth();
                if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
                totalSize += defaultMetrics ? 1 : iota.size();
            }
        }
        Object[] suffix = tree.cmi$getSuffix1();
        for (Object value : suffix) {
            Iota iota = (Iota) value;
            Class<?> type = iota.getClass();
            boolean defaultMetrics = hasDefaultUnitMetrics(type);
            int depth = defaultMetrics ? 1 : iota.depth();
            if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
            totalSize += defaultMetrics ? 1 : iota.size();
        }
        return totalSize >= HexIotaTypes.MAX_SERIALIZATION_TOTAL;
    }

    private static boolean isTooLarge(List<?> list) {
        int totalSize = 1;
        int count = list.size();
        for (int index = 0; index < count; index++) {
            Iota iota = (Iota) list.get(index);
            boolean defaultMetrics = hasDefaultUnitMetrics(iota.getClass());
            int depth = defaultMetrics ? 1 : iota.depth();
            if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
            totalSize += defaultMetrics ? 1 : iota.size();
        }
        return totalSize >= HexIotaTypes.MAX_SERIALIZATION_TOTAL;
    }
}
