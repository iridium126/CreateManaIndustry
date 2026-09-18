package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.gen.AllvrSanctuary;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Filters vanilla lava lakes and lava springs in the native central-chunk feature path. */
@Mixin(WorldGenRegion.class)
public abstract class AllvrWorldGenRegionMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow private Supplier<String> currentlyGenerating;

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"), cancellable = true)
    private void allvr$skipLava(BlockPos pos, BlockState state, int flags, int recursion,
                                CallbackInfoReturnable<Boolean> cir) {
        if (level.dimension() == AllvrDimensions.ALLAY_LEVEL
            && currentlyGenerating != null
            && AllvrSanctuary.isLavaFeatureLabel(currentlyGenerating.get())
            && AllvrSanctuary.lavaExcluded(pos.getX(), pos.getZ())) {
            cir.setReturnValue(false);
        }
    }
}
