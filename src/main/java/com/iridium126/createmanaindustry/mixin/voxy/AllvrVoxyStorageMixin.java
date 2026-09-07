package com.iridium126.createmanaindustry.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.client.VoxyClientInstance;

/**
 * Session memory storage for the allay dimension (voxy integration plan
 * §7.2): the allay voxy engine's {@code createStorage} is redirected to
 * {@code SectionSerializationStorage(new MemoryStorageBackend())} so virtual
 * Y keys can never persist — even across session crash or a storage-flush
 * bug, nothing virtual ever lands on disk.
 * <p>
 * Non-allay identifiers fall through to Voxy's own storage build unchanged.
 * Applied only when the voxy mod is present (the mixin plugin gate); the
 * injection is require=1 against the pinned voxy artifact.
 */
@Mixin(value = me.cortex.voxy.client.VoxyClientInstance.class, remap = false)
public abstract class AllvrVoxyStorageMixin {

    @Inject(method = "createStorage", at = @At("HEAD"), cancellable = true, remap = false)
    private void allvr$sessionMemoryStorage(WorldIdentifier identifier, CallbackInfoReturnable<me.cortex.voxy.common.config.section.SectionStorage> cir) {
        if (identifier != null && AllvrDimensions.ALLAY_LEVEL.equals(identifier.key)) {
            cir.setReturnValue(new SectionSerializationStorage(new MemoryStorageBackend()));
        }
    }
}
