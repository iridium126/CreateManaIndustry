package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.client.dimension.AllvrLightSampler;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;

/** Cube entity lighting outside the native chunk band's light engine. */
@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public abstract class AllvrGetLightColorMixin {

    @Inject(method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
        at = @At("HEAD"), cancellable = true)
    private static void allvr$syntheticLight(BlockAndTintGetter getter, BlockState state, BlockPos pos,
                                             CallbackInfoReturnable<Integer> cir) {
        if (!AllvrDimensionLimits.isVanillaY(pos.getY()) && getter instanceof Level level && level.isClientSide
            && level.dimension() == AllvrDimensions.ALLAY_LEVEL && level instanceof ClientLevel clientLevel) {
            cir.setReturnValue(AllvrLightSampler.sample(clientLevel, pos));
        }
    }
}
