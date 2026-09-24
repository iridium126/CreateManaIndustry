package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.lang.reflect.InvocationTargetException;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

/** Keeps optional Inline classes out of the YSM Iota's common linkage path. */
public final class YsmIotaDisplay {
    private static final String INLINE_DATA = "com.iridium126.createmanaindustry.compat.hexcasting.ysm.InlineYsmGeometryData";

    public static Component group(String key, Component fallback) {
        return inline("group", key, fallback);
    }
    public static Component cube(String key, Component fallback) {
        return inline("cube", key, fallback);
    }

    private static Component inline(String factory, String key, Component fallback) {
        if (!ModList.get().isLoaded("inline")) return fallback;
        try {
            Class<?> type = Class.forName(INLINE_DATA, true, YsmIotaDisplay.class.getClassLoader());
            Object inlineData = type.getMethod(factory, String.class).invoke(null, key);
            return (Component) type.getMethod("asText", boolean.class).invoke(inlineData, false);
        } catch (ReflectiveOperationException | LinkageError failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : failure;
            com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.debug("Unable to create YSM inline display", cause);
            return fallback;
        }
    }

    private YsmIotaDisplay() {}
}
