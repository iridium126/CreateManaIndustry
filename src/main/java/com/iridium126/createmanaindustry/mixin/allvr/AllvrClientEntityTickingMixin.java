package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Starts ticking entities stored in the cube shell on the client.
 *
 * <p>The client entity manager is transient and normally receives its ticking
 * columns from {@link ClientLevel#onChunkLoaded(ChunkPos)}. Cube columns do
 * not have a native {@code LevelChunk}, so that callback never runs for them:
 * entities are tracked and rendered, but their sections stay TRACKED and are
 * omitted from {@code ClientLevel.tickingEntities}. Register the loaded cube
 * columns immediately before the vanilla entity pass so the normal client
 * {@code Entity.tick()} path handles movement, hurt-time decay, fluids and
 * passenger updates.</p>
 */
@Mixin(ClientLevel.class)
public abstract class AllvrClientEntityTickingMixin {

    @Shadow @Final private TransientEntitySectionManager<Entity> entityStorage;

    /** Columns started by this bridge; native unloads remove the marker. */
    @Unique
    private final LongOpenHashSet allvr$startedCubeChunks = new LongOpenHashSet();

    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void allvr$startLoadedCubeEntityChunks(CallbackInfo ci) {
        ClientLevel level = (ClientLevel) (Object) this;
        if (level.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }

        /*
         * EntityLookup contains tracked entities even when their section is
         * not ticking, so this also covers entities that arrived while the
         * player's native chunk view was intentionally empty. De-duplicate
         * by column: startTicking updates every existing section in that
         * column and must only be paid once per newly observed column.
         */
        for (Entity entity : this.entityStorage.getEntityGetter().getAll()) {
            if (entity.isRemoved()) {
                continue;
            }
            BlockPos pos = entity.blockPosition();
            if (AllvrDimensionLimits.isVanillaY(pos.getY())
                || !AllvrClientCubeCache.isLoaded(pos)) {
                continue;
            }

            ChunkPos chunkPos = entity.chunkPosition();
            if (this.allvr$startedCubeChunks.add(chunkPos.toLong())) {
                this.entityStorage.startTicking(chunkPos);
            }
        }
    }

    /**
     * ClientChunkCache calls this for native columns. Its stopTicking call
     * applies to every section sharing the X/Z column, so allow the next
     * cube pass to restore the cube section if the cube is still loaded.
     */
    @Inject(method = "unload", at = @At("TAIL"))
    private void allvr$forgetNativeUnload(LevelChunk chunk, CallbackInfo ci) {
        this.allvr$startedCubeChunks.remove(chunk.getPos().toLong());
    }
}
