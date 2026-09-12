package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;

/** Keeps the native cached-chunk collision fast path for queries contained in the chunk band. */
@Mixin(BlockCollisions.class)
public abstract class AllvrBlockCollisionsMixin {

    @Shadow @Final private net.minecraft.world.phys.AABB box;

    @Shadow
    @Final
    private CollisionGetter collisionGetter;

    /** A boundary-spanning iterator needs the Level's per-position routing for both stores. */
    @WrapOperation(method = "getChunk", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/CollisionGetter;getChunkForCollisions(II)Lnet/minecraft/world/level/BlockGetter;"))
    private BlockGetter allvr$useCubeBackedGetter(CollisionGetter getter, int chunkX, int chunkZ,
                                                   Operation<BlockGetter> original) {
        if (this.collisionGetter instanceof Level level
            && level.dimension() == AllvrDimensions.ALLAY_LEVEL
            && (net.minecraft.util.Mth.floor(box.minY - 1.0E-7) - 1
                    < com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.VANILLA_MIN_Y
                || net.minecraft.util.Mth.floor(box.maxY + 1.0E-7) + 1
                    >= com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.VANILLA_MAX_Y)) {
            return level;
        }
        return original.call(getter, chunkX, chunkZ);
    }
}
