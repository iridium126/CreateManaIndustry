package com.iridium126.createmanaindustry.mixin.allvr;

import java.util.function.BiConsumer;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.light.AllvrNativeBlockHalo;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.lighting.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockLightEngine.class)
public abstract class AllvrNativeBlockSourcesMixin extends LightEngine {
    protected AllvrNativeBlockSourcesMixin(LightChunkGetter source, BlockLightSectionStorage storage) { super(source, storage); }
    @WrapOperation(method = "propagateLightSources", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LightChunk;findBlockLightSources(Ljava/util/function/BiConsumer;)V"))
    private void allvr$haloSources(LightChunk chunk, BiConsumer<BlockPos, BlockState> emit, Operation<Void> original, @Local(argsOnly = true) ChunkPos column) {
        original.call(chunk, emit);
        if (chunkSource.getLevel() instanceof Level level && AllvrDimensions.isAllay(level))
            AllvrNativeBlockHalo.sources(level, column, (BlockLightEngine) (Object) this, emit);
    }
}
