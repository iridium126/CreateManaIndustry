package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.utils.HexUtils;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ActionResourceKeyCache;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.JitCompatibility;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Reuses keys only inside the verified HexUtils registry-location tag check. */
@Mixin(value = HexUtils.class, remap = false)
public abstract class HexUtilsResourceKeyMixin {
    @WrapMethod(method = "isOfTag(Lnet/minecraft/core/Registry;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/tags/TagKey;)Z")
    private static <T> boolean cmi$cacheTagMembership(Registry<T> registry, ResourceKey<T> key, TagKey<T> tag,
                                                       Operation<Boolean> original) {
        if (!ServerConfig.hexJitCacheActionTagMembership
                || ServerConfig.hexJitMode != ServerConfig.HexJitMode.AUTO
                || !HexJitRuntime.onServerThread()
                || !JitCompatibility.actionTagMembershipReady()) {
            return original.call(registry, key, tag);
        }
        ExecutionScope scope = ExecutionScope.current();
        if (scope == null) return original.call(registry, key, tag);
        ResourceKey<?> registryKey = registry.key();
        ResourceLocation location = key.location();
        Boolean cached = scope.cachedTagMembership(registryKey, location, tag);
        if (cached != null) return cached;
        boolean result = original.call(registry, key, tag);
        scope.rememberTagMembership(registryKey, location, tag, result);
        return result;
    }

    @WrapOperation(method = "isOfTag(Lnet/minecraft/core/Registry;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/tags/TagKey;)Z",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/resources/ResourceKey;create(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/resources/ResourceKey;"))
    private static <T> ResourceKey<T> cmi$reuseTagCheckKey(ResourceKey<? extends Registry<T>> registryKey,
                                                            ResourceLocation location,
                                                            Operation<ResourceKey<T>> original) {
        if (!ServerConfig.hexJitCacheActionResourceKeys
                || ServerConfig.hexJitMode != ServerConfig.HexJitMode.AUTO
                || !HexJitRuntime.onServerThread()
                || !JitCompatibility.actionResourceKeyCacheReady()) {
            return original.call(registryKey, location);
        }
        return ActionResourceKeyCache.get(registryKey, location);
    }
}
