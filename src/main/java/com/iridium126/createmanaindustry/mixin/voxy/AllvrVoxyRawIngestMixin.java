package com.iridium126.createmanaindustry.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Stops Voxy's vanilla ClientLevel block-change hook from ingesting the
 * empty column shells used by Allay. The Allay bridge calls the separate
 * WorldEngine overload directly, so this gate does not affect cube ingest.
 */
@Mixin(value = me.cortex.voxy.common.world.service.VoxelIngestService.class, remap = false)
public abstract class AllvrVoxyRawIngestMixin {

    @Inject(method = "rawIngest(Lme/cortex/voxy/commonImpl/WorldIdentifier;"
        + "Lnet/minecraft/world/level/chunk/LevelChunkSection;IIIL"
        + "net/minecraft/world/level/chunk/DataLayer;"
        + "Lnet/minecraft/world/level/chunk/DataLayer;)Z",
        at = @At("HEAD"), cancellable = true, remap = false)
    private static void allvr$blockAutomaticAllayRawIngest(
        me.cortex.voxy.commonImpl.WorldIdentifier identifier,
        LevelChunkSection section, int sectionX, int sectionY, int sectionZ,
        DataLayer blockLight, DataLayer skyLight,
        CallbackInfoReturnable<Boolean> cir) {
        if (identifier != null && AllvrDimensions.ALLAY_LEVEL.equals(identifier.key)) {
            cir.setReturnValue(false);
        }
    }
}
