package com.iridium126.createmanaindustry.mixin.allvr;

import java.util.function.BiConsumer;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.light.AllvrNativeBlockHalo;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.lighting.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightEngine.class)
public abstract class AllvrNativeBlockLightMixin {
    @Shadow @Final protected LightChunkGetter chunkSource;
    @Inject(method = "getState", at = @At("HEAD"), cancellable = true)
    private void allvr$haloState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if ((Object) this instanceof BlockLightEngine && chunkSource.getLevel() instanceof Level level && AllvrDimensions.isAllay(level)
            && (pos.getY() >= 384 && pos.getY() < 400 || pos.getY() >= -144 && pos.getY() < -128))
            cir.setReturnValue(AllvrNativeBlockHalo.state(level, pos));
    }
}
