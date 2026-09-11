package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Defense in depth for vanilla chunk persistence.  ChunkMap can save from its
 * unload queue independently of ServerChunkCache.save(), so both the bulk
 * save entry point and the private per-chunk save path are cancelled.
 */
@Mixin(ChunkMap.class)
public abstract class AllvrChunkMapMixin {

    @Shadow @Final ServerLevel level;

    @Inject(method = "saveAllChunks(Z)V", at = @At("HEAD"), cancellable = true)
    private void allvr$skipVanillaBulkSave(boolean flush, CallbackInfo ci) {
        if (this.level.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            ci.cancel();
        }
    }

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At("HEAD"), cancellable = true)
    private void allvr$skipVanillaChunkSave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (this.level.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Keep vanilla entity bookkeeping available, but do not create a normal
     * circular chunk-tracking view for an Allay player.  The cube map owns the
     * render/simulation tickets and sends cube packets directly.
     */
    @Inject(method = "updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD"), cancellable = true)
    private void allvr$skipVanillaChunkTracking(ServerPlayer player, CallbackInfo ci) {
        if (this.level.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            ci.cancel();
        }
    }

    /** Final guard: no empty LevelChunk can become a vanilla terrain packet. */
    @Inject(method = "onChunkReadyToSend(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At("HEAD"), cancellable = true)
    private void allvr$skipVanillaChunkPacket(LevelChunk chunk, CallbackInfo ci) {
        if (this.level.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            ci.cancel();
        }
    }
}
