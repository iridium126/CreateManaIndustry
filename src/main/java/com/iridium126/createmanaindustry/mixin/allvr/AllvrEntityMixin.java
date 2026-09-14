package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Server-side entity gates for the allay dimension:
 * <ul>
 *   <li>Clamps entities to the ±30,000,000 Y software boundary
 *       (AllvrDimensionLimits) — a soft wall, not a teleport, to avoid the
 *       compensation jitter of cross-dimension repositioning mid-tick.</li>
 *   <li>Freezes server entities whose cube is not loaded ({@code Entity#tick}
 *       cancelled) — vanilla parity: entities in unloaded chunks don't tick.
 *       Without this, an entity on a far island whose cube was unloaded reads
 *       air for collision and falls into the void. Players are exempt (vanilla
 *       players tick regardless of chunk load).</li>
 * </ul>
 * Both run before the entity's own tick so movement, physics and block
 * queries this tick observe the clamped/frozen decision.
 */
@Mixin(Entity.class)
public abstract class AllvrEntityMixin {

    /**
     * Vanilla uses a conservative AABB-wide loaded-chunk check before
     * calculating fluid height and current vectors. A sparse cube shell can
     * legitimately have void neighbours that are not resident, even while
     * the entity's own cube is loaded; treating those neighbours as an
     * unloaded entity area suppresses lava damage and all fluid motion. The
     * cube cache already returns void air for such misses, so an entity in a
     * loaded cube can safely run the normal fluid calculation.
     */
    @Inject(method = "touchingUnloadedChunk", at = @At("HEAD"), cancellable = true)
    private void allvr$allowFluidQueriesInLoadedCube(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        Level level = self.level();
        if (level.dimension() != AllvrDimensions.ALLAY_LEVEL
            || AllvrDimensionLimits.isVanillaY(self.blockPosition().getY())) {
            return;
        }
        boolean loaded = false;
        if (level.isClientSide) {
            loaded = Boolean.TRUE.equals(
                com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook
                    .isLoaded(self.blockPosition()));
        } else if (level instanceof AllvrServerLevelDuck duck) {
            AllvrCubeMap map = duck.allvr$getCubeMap();
            loaded = map != null && map.isLoaded(self.blockPosition());
        }
        if (loaded) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void allvr$clampToBounds(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        Level level = self.level();
        if (level.isClientSide || level.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        double y = self.getY();
        if (y > AllvrDimensionLimits.Y_BOUND) {
            self.setPos(self.getX(), AllvrDimensionLimits.Y_BOUND, self.getZ());
            if (self.getDeltaMovement().y > 0) {
                self.setDeltaMovement(self.getDeltaMovement().x, 0, self.getDeltaMovement().z);
            }
        } else if (y < -AllvrDimensionLimits.Y_BOUND) {
            self.setPos(self.getX(), -AllvrDimensionLimits.Y_BOUND, self.getZ());
            if (self.getDeltaMovement().y < 0) {
                self.setDeltaMovement(self.getDeltaMovement().x, 0, self.getDeltaMovement().z);
            }
        }
        if (self instanceof ServerPlayer) {
            return;
        }
        // The central band is owned by vanilla LevelChunk instances.  It is
        // intentionally absent from AllvrCubeMap, so a null cube lookup there
        // must never be interpreted as an unloaded entity area.
        if (AllvrDimensionLimits.isVanillaY(self.blockPosition().getY())) {
            return;
        }
        if (level instanceof AllvrServerLevelDuck duck) {
            AllvrCubeMap map = duck.allvr$getCubeMap();
            if (map != null && !map.isLoaded(self.blockPosition())) {
                ci.cancel();
            }
        }
    }
}
