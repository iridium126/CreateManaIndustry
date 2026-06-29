package com.iridium126.createtricks.mixin.bnb;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelNode;
import com.kipti.bnb.content.kinetics.cogwheel_chain.types.CogwheelChainType;

@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelChain", remap = false)
public abstract class PlacingCogwheelChainMixin {

	@Shadow
	public abstract List<PlacingCogwheelNode> getVisitedNodes();

	@Shadow
	public abstract PlacingCogwheelNode getLastNode();

	@Inject(method = "tryAddNode", at = @At("HEAD"), cancellable = true, remap = false)
	private void createtricks$bypassForSpellConstruct(BlockPos pos, BlockState state,
			CogwheelChainType type, CallbackInfoReturnable<Boolean> cir) {
		boolean newIsSpell = BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock());
		boolean lastIsSpell = Boolean.TRUE.equals(BnBKineticsCoreNodes.LAST_NODE_IS_SPELL.get());

		if (!newIsSpell && !lastIsSpell)
			return;

		if (getLastNode().pos().equals(pos))
			return;

		List<PlacingCogwheelNode> nodes = getVisitedNodes();
		for (int i = 1; i < nodes.size(); i++) {
			if (nodes.get(i).pos().equals(pos))
				return;
		}

		Direction.Axis axis;
		boolean isLarge;
		boolean hasOffset;
		if (newIsSpell) {
			axis = BnBKineticsCoreNodes.getFacing(state).getAxis();
			isLarge = false;
			hasOffset = false;
		} else {
			axis = getAxis(state);
			try {
				Class<?> candidateClass = Class.forName(
						"com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainCandidate");
				Object candidate = candidateClass.getMethod("getForBlock", BlockState.class).invoke(null, state);
				if (candidate == null) {
					isLarge = false;
					hasOffset = false;
				} else {
					isLarge = (Boolean) candidateClass.getMethod("isLarge").invoke(candidate);
					hasOffset = (Boolean) candidateClass.getMethod("hasSmallCogwheelOffset").invoke(candidate);
				}
			} catch (ReflectiveOperationException e) {
				isLarge = false;
				hasOffset = false;
			}
		}

		PlacingCogwheelNode newNode = new PlacingCogwheelNode(pos, axis, isLarge, hasOffset);
		nodes.add(newNode);
		cir.setReturnValue(true);
	}

	@Inject(method = "tryAddNode", at = @At("RETURN"), remap = false)
	private void createtricks$clearLastNodeFlag(BlockPos pos, BlockState state, CogwheelChainType type,
			CallbackInfoReturnable<Boolean> cir) {
		BnBKineticsCoreNodes.LAST_NODE_IS_SPELL.remove();
	}

	/**
	 * In the updated BnB, {@code checkMissingNodesInLevel} uses
	 * {@code CogwheelChainCandidate.getForBlock} which returns null for spell
	 * constructs. We override the check to skip spell construct positions and
	 * verify they still have a valid kinetics core.
	 */
	@Inject(method = "checkMissingNodesInLevel", at = @At("HEAD"), cancellable = true, remap = false)
	private void createtricks$skipSpellConstructInMissingCheck(Level level, CogwheelChainType type,
			CallbackInfoReturnable<Boolean> cir) {
		boolean hasSpellConstruct = false;
		for (PlacingCogwheelNode node : getVisitedNodes()) {
			if (BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos())) {
				hasSpellConstruct = true;
				if (!BnBKineticsCoreNodes.hasAnyKineticsCore(level, node.pos())
					|| BnBKineticsCoreNodes.isAlreadyLinked(level, node.pos())) {
					cir.setReturnValue(true); // nodes are "missing" — reject
					return;
				}
			}
		}
		if (!hasSpellConstruct)
			return;

		// Check non-spell-construct nodes normally via CogwheelChainCandidate
		for (PlacingCogwheelNode node : getVisitedNodes()) {
			if (BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos()))
				continue;
			BlockState state = level.getBlockState(node.pos());
			try {
				Class<?> candidateClass = Class.forName(
						"com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainCandidate");
				Object candidate = candidateClass.getMethod("getForBlock", BlockState.class).invoke(null, state);
				if (candidate == null || !(Boolean) candidateClass.getMethod("isConsistentWithNode",
						Class.forName("com.kipti.bnb.content.kinetics.cogwheel_chain.graph.ICogwheelNode"))
						.invoke(candidate, node)) {
					cir.setReturnValue(true); // nodes are "missing" — reject
					return;
				}
			} catch (ReflectiveOperationException e) {
				cir.setReturnValue(true);
				return;
			}
		}
		cir.setReturnValue(false); // all good
	}

	private static Direction.Axis getAxis(BlockState state) {
		try {
			return state.getValue(BlockStateProperties.AXIS);
		} catch (IllegalArgumentException e) {
			return Direction.Axis.Y;
		}
	}
}
