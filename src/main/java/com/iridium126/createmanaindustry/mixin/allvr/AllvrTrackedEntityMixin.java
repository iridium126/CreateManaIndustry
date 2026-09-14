package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps entity packets flowing to players in the cube shell while native
 * column packets remain suppressed there.  The chunk packet bridge uses an
 * empty {@code ChunkTrackingView}; vanilla's {@code TrackedEntity} also uses
 * that view as an entity-pairing guard, so cube entities would otherwise
 * never be added to the player's client entity list.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class AllvrTrackedEntityMixin {

    @Shadow @Final Entity entity;

    @WrapOperation(
        method = "updatePlayer",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;isChunkTracked(Lnet/minecraft/server/level/ServerPlayer;II)Z"))
    private boolean allvr$trackCubeEntity(ChunkMap chunkMap, ServerPlayer player,
                                          int chunkX, int chunkZ,
                                          Operation<Boolean> original) {
        if (AllvrDimensions.isAllay(player.level())
            && AllvrDimensions.isAllay(this.entity.level())
            && !AllvrDimensionLimits.isVanillaY(player.blockPosition().getY())
            && !AllvrDimensionLimits.isVanillaY(this.entity.blockPosition().getY())) {
            // Cube entities are stored outside vanilla's column band.  Their
            // XZ tracking/range checks have already passed at this point;
            // only the intentionally empty native column view must be ignored.
            return true;
        }
        return original.call(chunkMap, player, chunkX, chunkZ);
    }
}
