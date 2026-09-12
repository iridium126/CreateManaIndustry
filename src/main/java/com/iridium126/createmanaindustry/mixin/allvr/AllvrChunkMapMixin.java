package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep native column packets near the central band while retaining entity/block tickets. */
@Mixin(ChunkMap.class)
public abstract class AllvrChunkMapMixin {
    @Shadow @Final ServerLevel level;
    @Shadow abstract int getPlayerViewDistance(ServerPlayer player);
    @Shadow protected abstract void applyChunkTrackingView(ServerPlayer player, ChunkTrackingView view);

    @Unique
    private boolean allvr$outsideChunkView(ServerPlayer player) {
        if (level.dimension() != AllvrDimensions.ALLAY_LEVEL) return false;
        return !AllvrDimensionLimits.intersectsVanillaView(player.getY(), getPlayerViewDistance(player));
    }

    @Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
    private void allvr$limitColumnView(ServerPlayer player, CallbackInfo ci) {
        if (allvr$outsideChunkView(player)) {
            // Keep the player's DistanceManager ticket active so vanilla's
            // entity manager continues ticking mobs in the cube shell. Only
            // suppress column packet tracking; block reads in this Y range
            // are routed by AllvrLevelMixin to the cube store.
            if (player.getChunkTrackingView() != ChunkTrackingView.EMPTY) {
                applyChunkTrackingView(player, ChunkTrackingView.EMPTY);
            }
            ci.cancel();
        }
    }
}
