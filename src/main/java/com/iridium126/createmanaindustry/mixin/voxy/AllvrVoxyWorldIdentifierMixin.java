package com.iridium126.createmanaindustry.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.client.dimension.lod.voxy.AllvrVoxyClientIngest;
import com.iridium126.createmanaindustry.client.dimension.lod.voxy.AllvrVoxyYSlab;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.level.Level;

/** Gives Voxy a stable persistence namespace for the current Allay Y slab. */
@Mixin(value = WorldIdentifier.class, remap = false)
public abstract class AllvrVoxyWorldIdentifierMixin {

    @Inject(method = "of(Lnet/minecraft/world/level/Level;)Lme/cortex/voxy/commonImpl/WorldIdentifier;",
        at = @At("RETURN"), cancellable = true, remap = false)
    private static void allvr$addYSlab(Level level,
                                       CallbackInfoReturnable<WorldIdentifier> cir) {
        WorldIdentifier base = cir.getReturnValue();
        if (base != null && AllvrDimensions.isAllay(level)) {
            cir.setReturnValue(AllvrVoxyYSlab.withSlab(base, AllvrVoxyClientIngest.activeSlabId(level)));
        }
    }
}
