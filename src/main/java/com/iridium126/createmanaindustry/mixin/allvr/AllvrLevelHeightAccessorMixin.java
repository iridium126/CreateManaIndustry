package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;

/**
 * Server commands and entity movement may address the cube range. Structural
 * height accessors retain the real [-128, 384) chunk window. ChunkAccess and
 * WorldGenRegion are excluded so their section array bounds remain truthful.
 * Clients retain the formal predicate for renderer array bounds; cube reads
 * and writes are intercepted before reaching it.
 */
@Mixin(LevelHeightAccessor.class)
public interface AllvrLevelHeightAccessorMixin {

    @Inject(method = "isOutsideBuildHeight(I)Z", at = @At("HEAD"), cancellable = true)
    private void allvr$widenBounds(int y, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Level level && !level.isClientSide
            && level.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            cir.setReturnValue(y < -AllvrDimensionLimits.Y_BOUND || y > AllvrDimensionLimits.Y_BOUND);
        }
    }
}
