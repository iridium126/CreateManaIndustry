package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Reuses immutable keys for Hexcasting's repeated registry tag checks. */
public final class ActionResourceKeyCache {
    private static final Map<ResourceKey<?>, Map<ResourceLocation, ResourceKey<?>>> KEYS = new HashMap<>();
    private static ResourceKey<?> lastRegistryKey;
    private static ResourceLocation lastLocation;
    private static ResourceKey<?> lastResult;

    private ActionResourceKeyCache() {}

    @SuppressWarnings("unchecked")
    public static <T> ResourceKey<T> get(ResourceKey<? extends Registry<T>> registryKey, ResourceLocation location) {
        if (lastResult != null && registryKey == lastRegistryKey && location == lastLocation)
            return (ResourceKey<T>) lastResult;
        Map<ResourceLocation, ResourceKey<?>> byLocation = KEYS.computeIfAbsent(registryKey, ignored -> new HashMap<>());
        ResourceKey<T> result = (ResourceKey<T>) byLocation.computeIfAbsent(
                location, id -> ResourceKey.create(registryKey, id));
        lastRegistryKey = registryKey;
        lastLocation = location;
        lastResult = result;
        return result;
    }
}
