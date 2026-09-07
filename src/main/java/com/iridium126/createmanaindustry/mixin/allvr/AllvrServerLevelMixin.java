package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;

import net.minecraft.util.ProgressListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Attaches the per-level {@link AllvrCubeMap} to the allay dimension's
 * {@link ServerLevel}. The map is created lazily on first block access or
 * tick (server thread only), so nothing runs for other dimensions and no
 * work happens before the dimension is actually entered.
 * <p>
 * Persistence wiring (plan §7.5): {@link ServerLevel#save} HEAD/TAIL drive
 * the ALLVR save queue and the blocking flush (the event bus alone cannot
 * express {@code /save-all flush} — no flush parameter), and
 * {@link ServerLevel#close} idempotently drains + closes the storage and the
 * LOD pool. Both only ever touch an <i>existing</i> map ({@code peek}) —
 * saving or closing a never-visited allay level must not build the whole
 * subsystem. Exceptions are aggregated/logged so the vanilla close sequence
 * continues.
 * <p>
 * Also cancels vanilla per-column chunk ticking ({@code tickChunk}: thunder
 * target search, ice/snow RNG and the per-section random-tick loop) inside
 * the allay dimension — columns are deterministic air shells whose sections
 * all fail the {@code isRandomlyTicking()} counter check, so the body is pure
 * overhead there, and cube blocks never receive random ticks through this
 * path anyway. Phase 7 replaces it with cube-side random/scheduled ticking
 * (doc §13); until then gameplay ticking is intentionally absent.
 */
@Mixin(ServerLevel.class)
public abstract class AllvrServerLevelMixin implements AllvrServerLevelDuck {

    @Unique
    private AllvrCubeMap allvr$cubeMap;

    @Unique
    private com.iridium126.createmanaindustry.dimension.lod.AllvrLodMap allvr$lodMap;

    @Override
    public AllvrCubeMap allvr$getCubeMap() {
        this.allvr$lazilyCreate();
        return allvr$cubeMap;
    }

    @Override
    public AllvrCubeMap allvr$peekCubeMap() {
        return allvr$cubeMap;
    }

    @Override
    public com.iridium126.createmanaindustry.dimension.lod.AllvrLodMap allvr$getLodMap() {
        this.allvr$lazilyCreate();
        return allvr$lodMap;
    }

    @Unique
    private void allvr$lazilyCreate() {
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        if (allvr$cubeMap == null) {
            allvr$cubeMap = new AllvrCubeMap(self);
        }
        if (allvr$lodMap == null) {
            allvr$lodMap = new com.iridium126.createmanaindustry.dimension.lod.AllvrLodMap(self, allvr$cubeMap);
            allvr$cubeMap.setLodMap(allvr$lodMap);
        }
    }

    /**
     * HEAD of {@code save(progress, flush, skipSave)} — with saving enabled,
     * every loaded dirty cube joins the ALLVR snapshot queue before the
     * vanilla save (and before {@code LevelEvent.Save}) runs.
     */
    @Inject(method = "save(Lnet/minecraft/util/ProgressListener;ZZ)V",
        at = @At("HEAD"))
    private void allvr$saveQueue(ProgressListener progress, boolean flush, boolean skipSave, CallbackInfo ci) {
        if (skipSave) {
            return;
        }
        AllvrCubeMap map = this.allvr$peekCubeMap();
        if (map != null) {
            try {
                map.saveAll(false);
            } catch (Exception e) {
                CreateManaIndustry.LOGGER.error("[Allvr] queueing allvr cube save failed", e);
            }
        }
    }

    /**
     * RETURN of {@code save(progress, flush, skipSave)} — the {@code flush}
     * variant blocks until every ALLVR record is durable (after the vanilla
     * worker flush), mirroring {@code /save-all flush}.
     */
    @Inject(method = "save(Lnet/minecraft/util/ProgressListener;ZZ)V",
        at = @At("RETURN"))
    private void allvr$saveFlush(ProgressListener progress, boolean flush, boolean skipSave, CallbackInfo ci) {
        if (skipSave || !flush) {
            return;
        }
        AllvrCubeMap map = this.allvr$peekCubeMap();
        if (map != null) {
            map.saveAll(true); // throws on failure so /save-all cannot pretend success
        }
    }

    /**
     * HEAD of {@code close()} — idempotent allvr shutdown before the vanilla
     * level resources close. Exceptions are caught: the vanilla close
     * sequence must continue.
     */
    @Inject(method = "close()V", at = @At("HEAD"))
    private void allvr$close(CallbackInfo ci) {
        if (((ServerLevel) (Object) this).dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        if (allvr$cubeMap != null) {
            try {
                allvr$cubeMap.close();
            } catch (Throwable t) {
                CreateManaIndustry.LOGGER.error("[Allvr] closing allvr cube persistence failed", t);
            }
        }
        if (allvr$lodMap != null) {
            try {
                allvr$lodMap.close();
            } catch (Throwable t) {
                CreateManaIndustry.LOGGER.error("[Allvr] closing allvr LOD pipeline failed", t);
            }
        }
    }

    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void allvr$skipTickChunk(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        if (((ServerLevel) (Object) this).dimension() == AllvrDimensions.ALLAY_LEVEL) {
            ci.cancel();
        }
    }
}
