package com.iridium126.createmanaindustry.mixin.bnb;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainCandidate;

/**
 * Augments {@code CogwheelChainCandidate} to treat modular spell construct blocks
 * as valid cogwheel chain candidates.
 */
@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainCandidate", remap = false)
public class CogwheelChainCandidateMixin {

	@Inject(method = "getForBlock(Lnet/minecraft/world/level/block/state/BlockState;)Lcom/kipti/bnb/content/kinetics/cogwheel_chain/graph/CogwheelChainCandidate;",
		at = @At("RETURN"), cancellable = true, remap = false)
	private static void createtricks$acceptSpellConstructInCandidate(BlockState state,
			CallbackInfoReturnable<CogwheelChainCandidate> cir) {
		if (cir.getReturnValue() != null)
			return;
		if (!BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			return;

		Direction.Axis axis = BnBKineticsCoreNodes.getFacing(state).getAxis();
		cir.setReturnValue(new CogwheelChainCandidate(axis, false, false));
	}

	@Inject(method = "isLargeCogwheel(Lnet/minecraft/world/level/block/state/BlockState;)Z",
		at = @At("HEAD"), cancellable = true, remap = false)
	private static void createtricks$spellConstructNotLarge(BlockState state,
			CallbackInfoReturnable<Boolean> cir) {
		if (BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			cir.setReturnValue(false);
	}

	@Inject(method = "isValidCandidate(Lnet/minecraft/world/level/block/state/BlockState;)Z",
		at = @At("RETURN"), cancellable = true, remap = false)
	private static void createtricks$acceptSpellConstructAsCandidate(BlockState state,
			CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue() && BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			cir.setReturnValue(true);
	}
}
