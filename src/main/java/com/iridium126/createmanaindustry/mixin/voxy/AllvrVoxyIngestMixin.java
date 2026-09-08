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
        // The method is called for the identifier being considered, which is
        // not necessarily the level currently displayed by Minecraft.  Using
        // mc.level here could disable ingest for an unrelated world during a
        // dimension transition and could allow a late allay ingest through.
        // Voxy's raw Sodium/ClientLevel ingest path unfortunately calls this
        // method with a null identifier.  In that case the only safe scope is
        // the level currently being rendered; otherwise the ALLAY virtual
        // tree can be polluted by ordinary LevelChunk sections.
        boolean allayIdentifier = identifier != null
            && AllvrDimensions.ALLAY_LEVEL.equals(identifier.key);
        boolean allayCurrentLevel = identifier == null
            && Minecraft.getInstance().level != null
            && AllvrDimensions.isAllay(Minecraft.getInstance().level);
        if (allayIdentifier || allayCurrentLevel) {
            cir.setReturnValue(false);
        }
    }
}
