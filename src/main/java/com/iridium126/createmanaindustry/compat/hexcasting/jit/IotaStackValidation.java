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
        int totalSize = 1;
        Object[] prefix = tree.cmi$getPrefix1();
        for (int index = 0, count = tree.cmi$getLen1(); index < count; index++) {
            Iota iota = (Iota) prefix[index];
            Class<?> type = iota.getClass();
            boolean defaultMetrics = hasDefaultUnitMetrics(type);
            int depth = defaultMetrics ? 1 : iota.depth();
            if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
            totalSize += defaultMetrics ? 1 : iota.size();
        }
        for (Object[] segment : tree.cmi$getData2()) {
            for (Object value : segment) {
                Iota iota = (Iota) value;
                Class<?> type = iota.getClass();
                boolean defaultMetrics = hasDefaultUnitMetrics(type);
                int depth = defaultMetrics ? 1 : iota.depth();
                if (depth >= HexIotaTypes.MAX_SERIALIZATION_DEPTH) return true;
                totalSize += defaultMetrics ? 1 : iota.size();
            }
        }
        for (Object value : tree.cmi$getSuffix1()) {
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
