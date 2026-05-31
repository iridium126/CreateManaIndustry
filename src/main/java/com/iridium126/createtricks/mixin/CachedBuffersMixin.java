package com.iridium126.createtricks.mixin;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.iridium126.createtricks.content.kinetics.TemporaryStressRenderContext;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(value = CachedBuffers.class, remap = false)
public class CachedBuffersMixin {
	@ModifyVariable(method = "partial(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static PartialModel createtricks$replacePartial(PartialModel partial, BlockState state) {
		return TemporaryStressRenderContext.replace(partial);
	}

	@ModifyVariable(method = "partial(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Ljava/util/function/Supplier;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static PartialModel createtricks$replaceTransformedPartial(PartialModel partial, BlockState state,
			Supplier<PoseStack> poseStack) {
		return TemporaryStressRenderContext.replace(partial);
	}

	@ModifyVariable(method = "partialFacing(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static PartialModel createtricks$replaceFacingPartial(PartialModel partial, BlockState state) {
		return TemporaryStressRenderContext.replace(partial);
	}

	@ModifyVariable(method = "partialFacing(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static PartialModel createtricks$replaceDirectedFacingPartial(PartialModel partial, BlockState state,
			Direction direction) {
		return TemporaryStressRenderContext.replace(partial);
	}

	@ModifyVariable(method = "partialFacingVertical(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static PartialModel createtricks$replaceVerticalFacingPartial(PartialModel partial, BlockState state,
			Direction direction) {
		return TemporaryStressRenderContext.replace(partial);
	}

	@ModifyVariable(method = "partialDirectional(Ldev/engine_room/flywheel/lib/model/baked/PartialModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Ljava/util/function/Supplier;)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static PartialModel createtricks$replaceDirectionalPartial(PartialModel partial, BlockState state,
			Direction direction, Supplier<PoseStack> poseStack) {
		return TemporaryStressRenderContext.replace(partial);
	}
}
