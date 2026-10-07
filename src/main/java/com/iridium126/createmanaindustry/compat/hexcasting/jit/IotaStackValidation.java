package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.BooleanIota;
import at.petrak.hexcasting.api.casting.iota.ContinuationIota;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.EntityIota;
import at.petrak.hexcasting.api.casting.iota.GarbageIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.iota.NullIota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import java.util.List;

/** Exact indexed equivalent of the upstream serialization-bound scan for immutable VM collections. */
public final class IotaStackValidation {
    private static final int NO_CACHED_SEGMENT = Integer.MIN_VALUE;
    private static final Class<?> EMPTY_IMMUTABLE_LIST = List.of().getClass();
    private static final Class<?> ONE_IMMUTABLE_LIST = List.of(Boolean.TRUE).getClass();
    private static final Class<?> TWO_IMMUTABLE_LIST = List.of(Boolean.TRUE, Boolean.FALSE).getClass();
    private static final Class<?> MANY_IMMUTABLE_LIST = List.of(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE).getClass();
    private IotaStackValidation() {}

    /** A small cache whose lifetime is restricted to one CastingVM queue execution. */
    public static final class MetricCache {
        private final boolean cacheSharedSegments;
        private final boolean cacheSuccessfulStackResults;
        private Object[][] cachedSegments;
        // Nonnegative values are exact serialized sizes; -1 marks a segment with extension
        // Iotas whose metrics must still be called for every validation.
        private int[] cachedSerializedSizes;
        private int[] cachedMaxDepths;
        private Object[][] currentRoot;
        private Object[][] currentSegments;
        private int[] currentSerializedSizes;
        private int[] currentMaxDepths;
        private Object[] cachedPrefix;
        private int cachedPrefixUnits;
        private int cachedSize;
        private int currentSize;
        private int lastMaxDepth;
        private final Object[] validatedStacks = new Object[8];
        private int nextValidatedStack;
        private Object lastValidatedStack;

        public MetricCache() {
            this(true, false);
        }

        public MetricCache(boolean cacheSharedSegments, boolean cacheSuccessfulStackResults) {
            this.cacheSharedSegments = cacheSharedSegments;
            this.cacheSuccessfulStackResults = cacheSuccessfulStackResults;
        }

        private boolean containsValidatedStack(Object stack) {
            if (!cacheSuccessfulStackResults) return false;
            if (lastValidatedStack == stack) return true;
            for (Object validated : validatedStacks) {
                if (validated == stack) return true;
            }
            return false;
        }

        /** Only successful scans of exact built-in, stable metrics enter this cache. */
        public boolean hasValidatedStableStack(Object stack) { return containsValidatedStack(stack); }

        private void rememberValidatedStack(Object stack) {
            if (!cacheSuccessfulStackResults) return;
            validatedStacks[nextValidatedStack] = stack;
            lastValidatedStack = stack;
            nextValidatedStack = (nextValidatedStack + 1) % validatedStacks.length;
        }

        private void begin(Object[][] root) {
            if (currentRoot != root) {
                currentRoot = root;
                currentSize = 0;
            }
        }

        private int get(int index, Object[] segment) {
            if (index < currentSize && currentSegments[index] == segment) {
                lastMaxDepth = currentMaxDepths[index];
                return currentSerializedSizes[index];
            }
            for (int i = 0; i < cachedSize; i++) {
                if (cachedSegments[i] == segment) {
                    rememberCurrent(index, segment, cachedSerializedSizes[i], cachedMaxDepths[i]);
                    lastMaxDepth = cachedMaxDepths[i];
                    return cachedSerializedSizes[i];
                }
            }
            return NO_CACHED_SEGMENT;
        }

        private int lastMaxDepth() { return lastMaxDepth; }

        private void put(int index, Object[] segment, int serializedSize, int maxDepth, boolean stable) {
            if (cachedSegments == null) {
                cachedSegments = new Object[64][];
                cachedSerializedSizes = new int[64];
                cachedMaxDepths = new int[64];
                currentSegments = new Object[64][];
                currentSerializedSizes = new int[64];
                currentMaxDepths = new int[64];
            }
            if (cachedSize < cachedSegments.length) {
                cachedSegments[cachedSize] = segment;
                cachedSerializedSizes[cachedSize] = stable ? serializedSize : -1;
                cachedMaxDepths[cachedSize] = stable ? maxDepth : -1;
                cachedSize++;
            }
            rememberCurrent(index, segment, stable ? serializedSize : -1, stable ? maxDepth : -1);
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

        private void rememberCurrent(int index, Object[] segment, int serializedSize, int maxDepth) {
            if (index < currentSegments.length) {
                currentSegments[index] = segment;
                currentSerializedSizes[index] = serializedSize;
                currentMaxDepths[index] = maxDepth;
                currentSize = Math.max(currentSize, index + 1);
            }
        }
    }

    private static boolean hasDefaultUnitMetrics(Class<?> type) {
        return type == DoubleIota.class || type == PatternIota.class || type == BooleanIota.class
                || type == NullIota.class || type == EntityIota.class || type == Vec3Iota.class
                || type == GarbageIota.class;
    }

    private static boolean hasStableMetrics(Class<?> type) {
        // ListIota snapshots depth and size in final fields at construction time.
        return hasDefaultUnitMetrics(type) || type == ContinuationIota.class || type == ListIota.class;
    }

    private static int continuationSerializationSize(Iota iota) {
        // ContinuationIota.size() walks every frame, then clamps the result to 0 or 1.
        return ((ContinuationIota) iota).getContinuation() instanceof SpellContinuation.NotDone ? 1 : 0;
    }

    public static boolean isTooLarge(Iterable<Iota> stack) {
        if (stack instanceof TreeList<?> tree) return isTooLarge(tree);
        if (stack instanceof List<?> list && isJdkImmutableList(list)) return isTooLarge(list);
        return IotaType.isTooLargeToSerialize(stack);
    }

    public static boolean isTooLarge(Iterable<Iota> stack, MetricCache cache) {
        if (cache != null && stack instanceof TreeList<?> tree) {
            if (cache.containsValidatedStack(tree)) return false;
            if ((Object) tree instanceof TreeList2Access segments) return isTooLarge(segments, cache);
            return isTooLarge(tree, cache);
        }
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
            boolean unitMetrics = hasDefaultUnitMetrics(type);
            boolean continuation = type == ContinuationIota.class;
            int depth = unitMetrics || continuation ? 1 : iota.depth();
            if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
            totalSize += unitMetrics ? 1 : continuation ? continuationSerializationSize(iota) : iota.size();
        }
        return totalSize >= HexIotaTypes.MAX_SERIALIZATION_TOTAL;
    }

    private static boolean isTooLarge(TreeList<?> tree, MetricCache cache) {
        int totalSize = 1;
        int count = tree.size();
        boolean cacheable = true;
        for (int index = 0; index < count; index++) {
            Iota iota = (Iota) tree.get(index);
            Class<?> type = iota.getClass();
            boolean unitMetrics = hasDefaultUnitMetrics(type);
            boolean continuation = type == ContinuationIota.class;
            int depth = unitMetrics || continuation ? 1 : iota.depth();
            if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
            totalSize += unitMetrics ? 1 : continuation ? continuationSerializationSize(iota) : iota.size();
            if (!hasStableMetrics(type)) cacheable = false;
        }
        boolean tooLarge = totalSize >= HexIotaTypes.MAX_SERIALIZATION_TOTAL;
        if (!tooLarge && cacheable) cache.rememberValidatedStack(tree);
        return tooLarge;
    }

    private static boolean isTooLarge(TreeList2Access tree) {
        return isTooLarge(tree, null);
    }

    private static boolean isTooLarge(TreeList2Access tree, MetricCache cache) {
        int totalSize = 1;
        boolean cacheable = true;
        Object[] prefix = tree.cmi$getPrefix1();
        if (cache != null && cache.cacheSharedSegments && prefix.length == tree.cmi$getLen1()) {
            int cachedUnits = cache.cachePrefix(prefix);
            if (cachedUnits > 0) {
                if (1 >= HexIotaTypes.MAX_SERIALIZATION_DEPTH && cachedUnits > 0) return true;
                totalSize += cachedUnits;
            } else {
                for (Object value : prefix) {
                    Iota iota = (Iota) value;
                    Class<?> type = iota.getClass();
                    boolean unitMetrics = hasDefaultUnitMetrics(type);
                    boolean continuation = type == ContinuationIota.class;
                    int depth = unitMetrics || continuation ? 1 : iota.depth();
                    if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
                    totalSize += unitMetrics ? 1 : continuation ? continuationSerializationSize(iota) : iota.size();
                    if (!hasStableMetrics(type)) cacheable = false;
                }
            }
        } else {
            for (int index = 0, count = tree.cmi$getLen1(); index < count; index++) {
                Iota iota = (Iota) prefix[index];
                Class<?> type = iota.getClass();
                boolean unitMetrics = hasDefaultUnitMetrics(type);
                boolean continuation = type == ContinuationIota.class;
                int depth = unitMetrics || continuation ? 1 : iota.depth();
                if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
                totalSize += unitMetrics ? 1 : continuation ? continuationSerializationSize(iota) : iota.size();
                if (!hasStableMetrics(type)) cacheable = false;
            }
        }
        Object[][] middle = tree.cmi$getData2();
        if (cache != null && cache.cacheSharedSegments) cache.begin(middle);
        for (int index = 0; index < middle.length; index++) {
            Object[] segment = middle[index];
            int cachedSize = cache == null || !cache.cacheSharedSegments
                    ? NO_CACHED_SEGMENT : cache.get(index, segment);
            if (cachedSize >= 0) {
                if (cache.lastMaxDepth() >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
                totalSize += cachedSize;
                continue;
            }
            boolean stable = cachedSize != -1;
            int segmentSize = 0;
            int segmentMaxDepth = 0;
            for (Object value : segment) {
                Iota iota = (Iota) value;
                Class<?> type = iota.getClass();
                boolean unitMetrics = hasDefaultUnitMetrics(type);
                boolean continuation = type == ContinuationIota.class;
                int depth = unitMetrics || continuation ? 1 : iota.depth();
                if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
                segmentSize += unitMetrics ? 1 : continuation ? continuationSerializationSize(iota) : iota.size();
                segmentMaxDepth = Math.max(segmentMaxDepth, depth);
                if (!hasStableMetrics(type)) {
                    stable = false;
                    cacheable = false;
                }
            }
            if (cache != null && cache.cacheSharedSegments && cachedSize == NO_CACHED_SEGMENT)
                cache.put(index, segment, segmentSize, segmentMaxDepth, stable);
            totalSize += segmentSize;
        }
        Object[] suffix = tree.cmi$getSuffix1();
        for (Object value : suffix) {
            Iota iota = (Iota) value;
            Class<?> type = iota.getClass();
            boolean unitMetrics = hasDefaultUnitMetrics(type);
            boolean continuation = type == ContinuationIota.class;
            int depth = unitMetrics || continuation ? 1 : iota.depth();
            if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
            totalSize += unitMetrics ? 1 : continuation ? continuationSerializationSize(iota) : iota.size();
            if (!hasStableMetrics(type)) cacheable = false;
        }
        boolean tooLarge = totalSize >= HexIotaTypes.MAX_SERIALIZATION_TOTAL;
        if (!tooLarge && cacheable && cache != null) cache.rememberValidatedStack(tree);
        return tooLarge;
    }

    private static boolean isTooLarge(List<?> list) {
        int totalSize = 1;
        int count = list.size();
        for (int index = 0; index < count; index++) {
            Iota iota = (Iota) list.get(index);
            Class<?> type = iota.getClass();
            boolean unitMetrics = hasDefaultUnitMetrics(type);
            boolean continuation = type == ContinuationIota.class;
            int depth = unitMetrics || continuation ? 1 : iota.depth();
            if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
            totalSize += unitMetrics ? 1 : continuation ? continuationSerializationSize(iota) : iota.size();
        }
        return totalSize >= HexIotaTypes.MAX_SERIALIZATION_TOTAL;
    }
}
