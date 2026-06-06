package com.iridium126.createtricks.mixin.bnb;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(targets = "com.kipti.bnb.content.cogwheel_chain.graph.CogwheelChain", remap = false)
public abstract class CogwheelChainMixin {
	@Shadow
	private List<?> cogwheelNodes;

	@Inject(method = "checkIntegrity", at = @At("HEAD"), cancellable = true)
	private void createtricks$checkKineticsCoreNodes(Level level, BlockPos origin, CallbackInfoReturnable<Boolean> cir) {
		try {
			if (!containsKineticsCoreNode(level, origin))
				return;

			for (Object node : cogwheelNodes) {
				BlockPos pos = origin.offset(localPos(node));
				if (!level.isLoaded(pos))
					continue;

				if (BnBKineticsCoreNodes.isModularSpellConstruct(level, pos)) {
					if (!BnBKineticsCoreNodes.hasAnyKineticsCore(level, pos)) {
						cir.setReturnValue(false);
						return;
					}
					continue;
				}

				BlockState state = level.getBlockState(pos);
				if (!isValidChainCogwheel(state, node)) {
					cir.setReturnValue(false);
					return;
				}
			}

			cir.setReturnValue(true);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			cir.setReturnValue(false);
		}
	}

	private boolean containsKineticsCoreNode(Level level, BlockPos origin) throws ReflectiveOperationException {
		for (Object node : cogwheelNodes) {
			BlockPos pos = origin.offset(localPos(node));
			if (level.isLoaded(pos) && BnBKineticsCoreNodes.isModularSpellConstruct(level, pos))
				return true;
		}
		return false;
	}

	private static boolean isValidChainCogwheel(BlockState state, Object node) throws ReflectiveOperationException {
		Class<?> placingChainClass = Class.forName("com.kipti.bnb.content.cogwheel_chain.graph.PlacingCogwheelChain");
		boolean validTarget = (Boolean) placingChainClass.getMethod("isValidBlockTarget", BlockState.class).invoke(null, state);
		if (!validTarget)
			return false;

		boolean nodeLarge = isLarge(node);
		boolean nodeOffset = offsetForSmallCogwheel(node);
		Direction.Axis nodeAxis = rotationAxis(node);

		boolean stateLarge = (Boolean) placingChainClass.getMethod("isLargeBlockTarget", BlockState.class).invoke(null, state);
		boolean stateOffset = (Boolean) placingChainClass.getMethod("hasSmallCogwheelOffset", BlockState.class).invoke(null, state);
		Direction.Axis stateAxis;
		try {
			stateAxis = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS);
		} catch (IllegalArgumentException e) {
			stateAxis = Direction.Axis.Y;
		}

		return nodeAxis == stateAxis && nodeLarge == stateLarge && nodeOffset == stateOffset;
	}

	private static BlockPos localPos(Object node) throws ReflectiveOperationException {
		return (BlockPos) node.getClass().getMethod("localPos").invoke(node);
	}

	private static Direction.Axis rotationAxis(Object node) throws ReflectiveOperationException {
		return (Direction.Axis) node.getClass().getMethod("rotationAxis").invoke(node);
	}

	private static boolean isLarge(Object node) throws ReflectiveOperationException {
		return (Boolean) node.getClass().getMethod("isLarge").invoke(node);
	}

	private static boolean offsetForSmallCogwheel(Object node) throws ReflectiveOperationException {
		return (Boolean) node.getClass().getMethod("offsetForSmallCogwheel").invoke(node);
	}
}
