package com.iridium126.createmanaindustry.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.client.dimension.lod.voxy.AllvrVoxyClientIngest;
import net.minecraft.client.Minecraft;
import me.cortex.voxy.common.world.WorldEngine;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Stops Voxy's vanilla ClientLevel block-change hook from ingesting the
 * empty column shells used by Allay. The Allay bridge calls the separate
 * WorldEngine overload directly, so this gate does not affect cube ingest.
 */
@Mixin(value = me.cortex.voxy.common.world.service.VoxelIngestService.class, remap = false)
public abstract class AllvrVoxyRawIngestMixin {

    /**
     * Voxy has two raw-ingest entry points.  The identifier overload is used
     * by the normal chunk path and is covered below, but Sodium's upload hook
     * calls the WorldEngine overload directly.  The Allay bridge also uses
     * that overload, so a small render-thread scope distinguishes the bridge
     * call from Voxy's automatic call without changing Voxy's public ABI.
     */
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

    /**
     * Blocks Voxy's Sodium upload hook, which invokes this overload with
     * absolute vanilla section Y.  Those coordinates would land in a second
     * copy thousands of blocks above the player after Allay's slab remap.
     * Calls made by {@code AllvrVoxyClientIngest} are explicitly scoped and
     * continue through the native Voxy ingest implementation.
     */
    @Inject(method = "rawIngest(Lme/cortex/voxy/common/world/WorldEngine;"
        + "Lnet/minecraft/world/level/chunk/LevelChunkSection;IIIL"
        + "net/minecraft/world/level/chunk/DataLayer;"
        + "Lnet/minecraft/world/level/chunk/DataLayer;)Z",
        at = @At("HEAD"), cancellable = true, remap = false)
    private static void allvr$blockAutomaticAllayEngineRawIngest(
        WorldEngine engine, LevelChunkSection section, int sectionX, int sectionY,
        int sectionZ, DataLayer blockLight, DataLayer skyLight,
        CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (!AllvrVoxyClientIngest.isBridgeIngest() && mc.level != null
            && AllvrDimensions.isAllay(mc.level)
            && AllvrDimensionLimits.isVanillaSection(sectionY)) {
            var renderer = me.cortex.voxy.client.core.IGetVoxyRenderSystem.getNullable();
            // Only suppress the current Allay renderer's upload callback.
            // During a dimension transition Voxy may still flush another
            // world's engine; that data must remain untouched.
            if (renderer != null && renderer.getEngine() == engine) {
                cir.setReturnValue(false);
            }
        }
    }
}
