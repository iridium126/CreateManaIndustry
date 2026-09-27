package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.castables.SpecialHandler;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.common.casting.PatternRegistryManifest;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.JitCompatibility;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.SpecialHandlerFactoryCache;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;

/** Reuses only the ordered factory table; all environment-sensitive tryMatch calls still run. */
@Mixin(value = PatternRegistryManifest.class, remap = false)
public abstract class SpecialHandlerLookupMixin {
    @WrapMethod(method = "matchPatternToSpecialHandler")
    private static Pair<SpecialHandler, ResourceKey<SpecialHandler.Factory<?>>> cmi$lookupFactoryTable(
            HexPattern pattern, CastingEnvironment environment,
            Operation<Pair<SpecialHandler, ResourceKey<SpecialHandler.Factory<?>>>> original) {
        if (ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && ServerConfig.hexJitFastSpecialHandlerLookup
                && HexJitRuntime.enabled() && JitCompatibility.specialHandlerLookupReady()) {
            return SpecialHandlerFactoryCache.match(pattern, environment);
        }
        return original.call(pattern, environment);
    }
}
