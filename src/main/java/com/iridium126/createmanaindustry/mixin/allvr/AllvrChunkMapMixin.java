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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep native tickets and packets near the central band; leave save/IO/tick pipelines intact. */
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

    @Inject(method = "skipPlayer", at = @At("HEAD"), cancellable = true)
    private void allvr$skipDistantColumnTickets(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        // Vanilla move/updatePlayerStatus performs ticket removal/re-addition
        // when this predicate changes, including vertical-only movement.
        if (allvr$outsideChunkView(player)) cir.setReturnValue(true);
    }

    @Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
    private void allvr$limitColumnView(ServerPlayer player, CallbackInfo ci) {
        if (allvr$outsideChunkView(player)) {
            if (player.getChunkTrackingView() != ChunkTrackingView.EMPTY) {
                applyChunkTrackingView(player, ChunkTrackingView.EMPTY);
            }
            ci.cancel();
        }
    }
}
