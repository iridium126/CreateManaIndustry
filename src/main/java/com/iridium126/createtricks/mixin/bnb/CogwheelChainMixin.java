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

@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain", remap = false)
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

	/**
	 * Checks whether the block state at a chain position is consistent with the
	 * given path node, using the updated BnB {@code CogwheelChainCandidate} API.
	 */
	private static boolean isValidChainCogwheel(BlockState state, Object node) throws ReflectiveOperationException {
		Class<?> candidateClass = Class.forName(
				"com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainCandidate");

		// Get the candidate for this state; null means it's not a valid target
		Object candidate = candidateClass.getMethod("getForBlock", BlockState.class).invoke(null, state);
		if (candidate == null)
			return false;

		// Check consistency with the node: axis, isLarge, hasSmallCogwheelOffset
		boolean consistent = (Boolean) candidateClass.getMethod("isConsistentWithNode",
				Class.forName("com.kipti.bnb.content.kinetics.cogwheel_chain.graph.ICogwheelNode"))
				.invoke(candidate, node);
		return consistent;
	}

	private static BlockPos localPos(Object node) throws ReflectiveOperationException {
		return (BlockPos) node.getClass().getMethod("localPos").invoke(node);
	}
}
