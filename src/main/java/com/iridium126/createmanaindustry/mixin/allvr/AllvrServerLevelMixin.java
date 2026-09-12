package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrEntitySectionManagerDuck;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.util.ProgressListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import java.util.function.BooleanSupplier;

/**
 * Attaches the cube map and its independent persistence to the server level.
 * Native chunk ticking, saving and closing proceed normally. Cube save hooks
 * only flush an existing map, so saving does not initialize an unused store.
 */
@Mixin(ServerLevel.class)
public abstract class AllvrServerLevelMixin implements AllvrServerLevelDuck {

    @Shadow @Final
    private PersistentEntitySectionManager<Entity> entityManager;

    @Unique
    private AllvrCubeMap allvr$cubeMap;

    @Override
    public AllvrCubeMap allvr$getCubeMap() {
        this.allvr$lazilyCreate();
        return allvr$cubeMap;
    }

    @Override
    public AllvrCubeMap allvr$peekCubeMap() {
        return allvr$cubeMap;
    }

    @Override
    public void allvr$syncEntityTicking() {
        AllvrCubeMap map = this.allvr$peekCubeMap();
        if (map != null) {
            ((AllvrEntitySectionManagerDuck) (Object) this.entityManager)
                .allvr$syncCubeEntityTicking(map);
        }
    }

    /** Run after native chunk status updates and immediately before entities tick. */
    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void allvr$syncCubeEntityTicking(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            this.allvr$syncEntityTicking();
        }
    }

    /**
     * Vanilla's entity pass has a second X/Z-only gate after the entity list
     * has been populated.  A loaded cube is the authoritative simulation
     * ticket for its Y range, so let its entities pass that gate even when no
     * native column ticket happens to cover the same X/Z position.
     */
    @WrapOperation(method = "lambda$tick$2", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/DistanceManager;inEntityTickingRange(J)Z"))
    private boolean allvr$allowLoadedCubeEntityTicking(DistanceManager distanceManager, long chunkPos,
                                                         Operation<Boolean> original,
                                                         @Local(argsOnly = true) Entity entity) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.dimension() == AllvrDimensions.ALLAY_LEVEL
            && !AllvrDimensionLimits.isVanillaY(entity.blockPosition().getY())
            && self instanceof AllvrServerLevelDuck duck) {
            AllvrCubeMap map = duck.allvr$peekCubeMap();
            if (map != null && map.isLoaded(entity.blockPosition())) {
                return true;
            }
        }
        return original.call(distanceManager, chunkPos);
    }

    @Unique
    private void allvr$lazilyCreate() {
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        if (allvr$cubeMap == null) {
            allvr$cubeMap = new AllvrCubeMap(self);
        }
    }

    /**
     * HEAD of {@code save(progress, flush, skipSave)} — with saving enabled,
     * every loaded dirty cube joins the ALLVR snapshot queue before the
     * level-save event runs. Native column chunks remain in the vanilla save
     * pipeline; this hook only adds the independent cube store.
     */
    @Inject(method = "save(Lnet/minecraft/util/ProgressListener;ZZ)V",
        at = @At("HEAD"))
    private void allvr$saveQueue(ProgressListener progress, boolean flush, boolean skipSave, CallbackInfo ci) {
        if (skipSave) {
            return;
        }
        AllvrCubeMap map = this.allvr$peekCubeMap();
        if (map != null) {
            try {
                map.saveAll(false);
            } catch (Exception e) {
                CreateManaIndustry.LOGGER.error("[Allvr] queueing allvr cube save failed", e);
            }
        }
    }

    /**
     * RETURN of {@code save(progress, flush, skipSave)} — the {@code flush}
     * variant blocks until every ALLVR record is durable (after the vanilla
     * worker flush), mirroring {@code /save-all flush}.
     */
    @Inject(method = "save(Lnet/minecraft/util/ProgressListener;ZZ)V",
        at = @At("RETURN"))
    private void allvr$saveFlush(ProgressListener progress, boolean flush, boolean skipSave, CallbackInfo ci) {
        if (skipSave || !flush) {
            return;
        }
        AllvrCubeMap map = this.allvr$peekCubeMap();
        if (map != null) {
            map.saveAll(true); // throws on failure so /save-all cannot pretend success
        }
    }

    /**
     * HEAD of {@code close()} — idempotent allvr shutdown before the vanilla
     * level resources close. Exceptions are caught: the vanilla close
     * sequence must continue.
     */
    @Inject(method = "close()V", at = @At("HEAD"))
    private void allvr$close(CallbackInfo ci) {
        if (((ServerLevel) (Object) this).dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        if (allvr$cubeMap != null) {
            try {
                allvr$cubeMap.close();
            } catch (Throwable t) {
                CreateManaIndustry.LOGGER.error("[Allvr] closing allvr cube persistence failed", t);
            }
        }
    }

}
