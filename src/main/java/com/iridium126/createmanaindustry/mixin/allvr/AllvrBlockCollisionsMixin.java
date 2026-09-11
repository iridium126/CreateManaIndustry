package com.iridium126.createmanaindustry.mixin.allvr;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;

/**
 * The vanilla collision iterator ({@code Entity#collide}, suffocation checks
 * and {@code findSupportingBlock} all share it) does NOT go through
 * {@code Level#getBlockState}: per XZ column it fetches the chunk via
 * {@code getChunkForCollisions} and reads {@code LevelChunk#getBlockState}
 * directly (vanilla fast path). Inside the allay dimension those column
 * chunks are transport shells and are not kept resident by the cube loader;
 * after the vanilla chunk tick is disabled, {@code getChunkForCollisions}
 * can return either an empty shell or {@code null}. The former loses the
 * cube state and the latter makes the iterator skip the position entirely.
 * <p>
 * For Allay, make the iterator use the current {@link Level} as its
 * {@link BlockGetter}. The existing Allay {@code Level#getBlockState} routes
 * reads to the server cube map or the client cube cache, so the complete
 * vanilla iterator keeps working without a column chunk. This also makes the
 * {@code onlySuffocatingBlocks} path use the same source as normal movement;
 * unloaded cubes still resolve to air and do not trigger generation.
 */
@Mixin(BlockCollisions.class)
public abstract class AllvrBlockCollisionsMixin {

    @Shadow
    @Final
    private CollisionGetter collisionGetter;

    /**
     * Vanilla's {@code BlockCollisions#getChunk} caches the result of this
     * call by XZ chunk. Allay has no authoritative column chunks, so returning
     * the level is intentional: it supplies a stable, non-null BlockGetter
     * while the dimension-specific Level mixins provide the actual cube data.
     */
    @WrapOperation(method = "getChunk", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/CollisionGetter;getChunkForCollisions(II)Lnet/minecraft/world/level/BlockGetter;"))
    private BlockGetter allvr$useCubeBackedGetter(CollisionGetter getter, int chunkX, int chunkZ,
                                                   Operation<BlockGetter> original) {
        if (this.collisionGetter instanceof Level level
            && level.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            return level;
        }
        return original.call(getter, chunkX, chunkZ);
    }
}
