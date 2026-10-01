package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageRenderOwnership;
import com.iridium126.createmanaindustry.client.particles.packages.PackageWorldRuntime;
import com.simibubi.create.content.logistics.box.PackageEntity;
import java.util.function.Predicate;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Prevent vanilla picking the stale retained authority AABB instead of its GPU rendered box.
 * Other entities and Create-owned/native-observer packages keep the original predicate. */
@Mixin(GameRenderer.class)
public abstract class PackageFreeGpuPickFilterMixin {
    @ModifyArg(method="pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/projectile/ProjectileUtil;getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;"),index=4)
    private Predicate<Entity> cmi$excludeRetainedAuthority(Predicate<Entity> nativePredicate) {
        if(!PackageWorldRuntime.freeInputReady())return nativePredicate;
        return entity->nativePredicate.test(entity)&&!(entity instanceof PackageEntity box&&PackageRenderOwnership.authorityOwned(box));
    }
}
