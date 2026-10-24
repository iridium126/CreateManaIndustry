package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.*;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class AllvrServerChunkCacheMixin {
    @Shadow @Final private ServerLevel level;
    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    private void allvr$cubeUpdate(BlockPos pos, CallbackInfo ci) {
        if (AllvrDimensions.isAllay(level) && !AllvrDimensionLimits.isVanillaY(pos.getY())) {
            ((AllvrServerLevelDuck) level).allvr$getCubeMap().markForClientUpdate(pos);
            ci.cancel();
        }
    }
}
