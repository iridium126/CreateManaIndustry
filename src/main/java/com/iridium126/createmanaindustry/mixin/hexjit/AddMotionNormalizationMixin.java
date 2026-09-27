package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.common.casting.actions.spells.OpAddMotion;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.AddMotionNormalizationCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Reuses only bit-identical normalization results inside the exact stock Add Motion action. */
@Mixin(value = OpAddMotion.class, remap = false)
public abstract class AddMotionNormalizationMixin {
    @WrapOperation(method = "execute", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/phys/Vec3;normalize()Lnet/minecraft/world/phys/Vec3;", remap = true))
    private Vec3 cmi$reuseNormalizedMotion(Vec3 motion, Operation<Vec3> original) {
        AddMotionNormalizationCache.Cache cache = AddMotionNormalizationCache.activeCache();
        if (cache == null) return original.call(motion);
        Vec3 cached = cache.cached(motion);
        if (cached != null) return cached;
        Vec3 normalized = original.call(motion);
        cache.remember(motion, normalized);
        return normalized;
    }
}
