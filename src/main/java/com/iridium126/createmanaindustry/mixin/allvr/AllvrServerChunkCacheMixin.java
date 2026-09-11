package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.server.level.ServerChunkCache;

/**
 * The allay dimension owns its block data in {@code AllvrCubeMap}.  Its
 * vanilla column chunks are transport shells and must never enter the normal
 * ChunkMap/IOWorker save pipeline.  This guard covers both /save-all and the
 * ServerChunkCache shutdown path.
 */
@Mixin(ServerChunkCache.class)
public abstract class AllvrServerChunkCacheMixin {

    /**
     * Allay owns its residency in {@code AllvrCubeMap}.  The vanilla tick
     * would otherwise run DistanceManager, promote empty LevelChunk holders,
     * and call ChunkMap's unload/send pipeline even though no column chunk is
     * ever a valid Allay data source.
     */
    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void allvr$skipVanillaChunkTick(java.util.function.BooleanSupplier hasTime,
                                             boolean tickChunks, CallbackInfo ci) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (self.getLevel().dimension() == AllvrDimensions.ALLAY_LEVEL) {
            ci.cancel();
        }
    }

    @Inject(method = "save(Z)V", at = @At("HEAD"), cancellable = true)
    private void allvr$skipVanillaChunkSave(boolean flush, CallbackInfo ci) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        if (self.getLevel().dimension() == AllvrDimensions.ALLAY_LEVEL) {
            ci.cancel();
        }
    }
}
