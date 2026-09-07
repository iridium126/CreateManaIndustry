package com.iridium126.createmanaindustry.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

/**
 * Vanilla ingest gate for the allay dimension (voxy integration plan §7.5-3):
 * voxy's own chunk ingest would write real client chunks (the 384-block
 * build window) into the same engine ALLVR injects virtual sections into —
 * the two would fight over the same virtual keys. While the client level is
 * the allay dimension, {@code isIngestEnabled} returns false so no vanilla
 * ingest path can fire for it; every other dimension keeps Voxy's own value.
 * <p>
 * Applied only when the voxy mod is present (the mixin plugin gate).
 */
@Mixin(value = me.cortex.voxy.client.VoxyClientInstance.class, remap = false)
public abstract class AllvrVoxyIngestMixin {

    @Inject(method = "isIngestEnabled", at = @At("HEAD"), cancellable = true, remap = false)
    private void allvr$blockAllayIngest(me.cortex.voxy.commonImpl.WorldIdentifier identifier,
                                        CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && AllvrDimensions.isAllay(mc.level)) {
            cir.setReturnValue(false);
        }
    }
}
