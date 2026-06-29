package com.iridium126.createtricks.mixin.bnb;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes;

/**
 * Augments {@code CogwheelChainCandidate} to treat modular spell construct blocks
 * as valid cogwheel chain candidates with a configurable axis, small size, and no
 * small-cogwheel offset.
 */
@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainCandidate", remap = false)
public class CogwheelChainCandidateMixin {

	/**
	 * Returns a synthetic candidate for spell construct blocks so the chain
	 * placement system accepts them as valid targets.
	 */
	@SuppressWarnings("rawtypes")
	@Inject(method = "getForBlock(Lnet/minecraft/world/level/block/state/BlockState;)Lcom/kipti/bnb/content/kinetics/cogwheel_chain/graph/CogwheelChainCandidate;",
		at = @At("RETURN"), cancellable = true, remap = false)
	private static void createtricks$acceptSpellConstructInCandidate(BlockState state,
			CallbackInfoReturnable cir) {
		if (cir.getReturnValue() != null)
			return;
		if (!BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			return;

		Direction.Axis axis = BnBKineticsCoreNodes.getFacing(state).getAxis();
		try {
			Class<?> candidateClass = Class.forName(
					"com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainCandidate");
			Object synthetic = candidateClass.getConstructor(
					Direction.Axis.class, boolean.class, boolean.class)
					.newInstance(axis, false, false);
			cir.setReturnValue(synthetic);
		} catch (ReflectiveOperationException ignored) {
		}
	}

	/**
	 * Spell construct blocks are never large cogwheels.
	 */
	@Inject(method = "isLargeCogwheel(Lnet/minecraft/world/level/block/state/BlockState;)Z",
		at = @At("HEAD"), cancellable = true, remap = false)
	private static void createtricks$spellConstructNotLarge(BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			cir.setReturnValue(false);
	}

	/**
	 * Spell construct blocks count as valid candidates.
	 */
	@Inject(method = "isValidCandidate(Lnet/minecraft/world/level/block/state/BlockState;)Z",
		at = @At("RETURN"), cancellable = true, remap = false)
	private static void createtricks$acceptSpellConstructAsCandidate(BlockState state,
			CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue() && BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			cir.setReturnValue(true);
	}
}
