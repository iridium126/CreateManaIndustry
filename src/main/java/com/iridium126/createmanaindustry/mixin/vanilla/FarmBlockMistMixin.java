package com.iridium126.createmanaindustry.mixin.vanilla;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.material.Fluids;

/** Allows dominant water mist above 10% to hydrate farmland on its normal random tick. */
@Mixin(FarmBlock.class)
public abstract class FarmBlockMistMixin {

    @Inject(method = "isNearWater", at = @At("RETURN"), cancellable = true)
    private static void cmi$hydrateFromWaterMist(LevelReader level, BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || !(level instanceof ServerLevel serverLevel))
            return;

        var fluidId = MistFieldStore.getFluidType(serverLevel, pos);
        if (fluidId != null && BuiltInRegistries.FLUID.get(fluidId).isSame(Fluids.WATER)
                && MistFieldStore.getConcentration(serverLevel, pos) > 0.1f)
            cir.setReturnValue(true);
    }
}
