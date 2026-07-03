package com.iridium126.createmanaindustry.mixin.bnb;

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

import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainCandidate;
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
			CogwheelChainCandidate candidate = CogwheelChainCandidate.getForBlock(state);
			if (candidate == null) {
				isLarge = false;
				hasOffset = false;
			} else {
				isLarge = candidate.isLarge();
				hasOffset = candidate.hasSmallCogwheelOffset();
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
	 * Override {@code checkMissingNodesInLevel} to handle spell construct
	 * positions which are not recognised by {@code CogwheelChainCandidate}.
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
					cir.setReturnValue(true);
					return;
				}
			}
		}
		if (!hasSpellConstruct)
			return;

		for (PlacingCogwheelNode node : getVisitedNodes()) {
			if (BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos()))
				continue;
			BlockState state = level.getBlockState(node.pos());
			CogwheelChainCandidate candidate = CogwheelChainCandidate.getForBlock(state);
			if (candidate == null || !candidate.isConsistentWithNode(node)) {
				cir.setReturnValue(true);
				return;
			}
		}
		cir.setReturnValue(false);
	}

	private static Direction.Axis getAxis(BlockState state) {
		try {
			return state.getValue(BlockStateProperties.AXIS);
		} catch (IllegalArgumentException e) {
			return Direction.Axis.Y;
		}
	}
}
