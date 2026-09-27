package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.castables.SpecialHandler;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Registry;

/**
 * Keeps the special-handler factory registry in its original iteration order. It deliberately
 * invokes every factory for every pattern, since matching may depend on the current environment.
 */
public final class SpecialHandlerFactoryCache {
    private static volatile Entry[] entries;

    static {
        HexJitRuntime.registerCacheInvalidator(SpecialHandlerFactoryCache::clear);
    }

    private SpecialHandlerFactoryCache() {}

    public static Pair<SpecialHandler, ResourceKey<SpecialHandler.Factory<?>>> match(
            HexPattern pattern, CastingEnvironment environment) {
        Entry[] factories = entries;
        if (factories == null) {
            synchronized (SpecialHandlerFactoryCache.class) {
                factories = entries;
                if (factories == null) entries = factories = build();
            }
        }
        for (Entry entry : factories) {
            SpecialHandler handler = entry.factory.tryMatch(pattern, environment);
            if (handler != null) return Pair.of(handler, entry.key);
        }
        return null;
    }

    private static Entry[] build() {
        Registry<SpecialHandler.Factory<?>> registry = IXplatAbstractions.INSTANCE.getSpecialHandlerRegistry();
        List<Entry> ordered = new ArrayList<>();
        for (ResourceKey<SpecialHandler.Factory<?>> key : registry.registryKeySet()) {
            SpecialHandler.Factory<?> factory = registry.get(key);
            if (factory != null) ordered.add(new Entry(key, factory));
        }
        return ordered.toArray(Entry[]::new);
    }

    public static void clear() {
        entries = null;
    }

    private record Entry(ResourceKey<SpecialHandler.Factory<?>> key, SpecialHandler.Factory<?> factory) {}
}
