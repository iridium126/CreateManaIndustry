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
 * Widens the world-bounds predicate ({@code isOutsideBuildHeight}, and with
 * it {@code isInWorldBounds}) from the dimension_type's formal window (384
 * blocks — deliberately minimal, the window is pure formality since every
 * block access routes to the cube layer) to the cube range
 * (±AllvrDimensionLimits.Y_BOUND) inside the allay dimension — server side
 * only.
 * <p>
 * Without this, server consumers of the predicate reject cube-only Y
 * positions before cube code ever runs — e.g. {@code BlockPosArgument}
 * fails {@code /setblock}, {@code /data get block}, {@code /fill}, ... with
 * "Position is outside of this world". Vanilla bodies that pass the widened
 * check and then touch column sections are all either routed to the cube map
 * first (Level get/setBlockState), guarded by section-index bounds checks
 * (LevelChunk get/setBlockState), or Y-independent (heightmaps, chunk coords).
 * <p>
 * The widening must not apply to client levels: the predicate doubles as the
 * guard third-party code runs before indexing column sections — Sodium's
 * {@code ClonedChunkSectionCache.clone} does literally
 * {@code if (!isOutsideBuildHeight(y)) chunk.getSections()[getSectionIndexFromSectionY(y)]}
 * — and a widened predicate turns that guard into a lie. Rebuilding a
 * window-edge render section whose neighbor slice reaches one section past
 * the formal window (columns whose bottom section Y=-12 is non-empty — a save
 * with a build at the window floor — do this on every initial build) then
 * crashes with {@code ArrayIndexOutOfBoundsException: Index -1 out of bounds
 * for length 24} on the render thread. With the formal window the client
 * guard works as designed (out-of-window neighbors clone as null → air,
 * exactly vanilla boundary behavior). Client block access never reaches the
 * predicate anyway (the client Level mixins intercept reads/writes at HEAD),
 * and the remaining client consumers — LevelRenderer's entity culling and
 * LevelLoadStatusManager — are correct with the formal window: cube-only Y
 * entities must skip the section-compiled check (no vanilla section exists
 * for them), and the join screen must not wait on one.
 * <p>
 * Gated by {@code instanceof Level} so other implementors keep vanilla
 * semantics: {@code LevelChunk} (window-sized section arrays) and
 * {@code WorldGenRegion} (window-only worldgen) are deliberately excluded.
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
