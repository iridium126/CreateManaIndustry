package com.iridium126.createmanaindustry.mixin.vanilla;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.collision.LargeSweepCollisionResolver;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Avoids the enormous cuboid scan for extreme entity movements. */
@Mixin(Entity.class)
public abstract class EntityCollisionSweepMixin {

    @Inject(method = "collideBoundingBox", at = @At("HEAD"), cancellable = true)
    private static void cmi$resolveLargeSweep(Entity entity, Vec3 movement, AABB collisionBox, Level level,
                                               List<VoxelShape> potentialHits,
                                               CallbackInfoReturnable<Vec3> cir) {
        if (LargeSweepCollisionResolver.shouldResolve(entity, movement, collisionBox)) {
            cir.setReturnValue(LargeSweepCollisionResolver.resolve(entity, movement, collisionBox, level, potentialHits));
        }
    }
}
