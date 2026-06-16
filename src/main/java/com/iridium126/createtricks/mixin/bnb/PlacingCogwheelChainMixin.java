package com.iridium126.createtricks.mixin.bnb;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.cogwheel_chain.graph.PlacingCogwheelChain;
import com.kipti.bnb.content.cogwheel_chain.graph.PlacingCogwheelNode;

@Mixin(targets = "com.kipti.bnb.content.cogwheel_chain.graph.PlacingCogwheelChain", remap = false)
public abstract class PlacingCogwheelChainMixin {

	@Shadow
	public abstract List<PlacingCogwheelNode> getVisitedNodes();

	@Shadow
	public abstract PlacingCogwheelNode getLastNode();

	@Inject(method = "tryAddNode", at = @At("HEAD"), cancellable = true, remap = false)
	private void createtricks$bypassForSpellConstruct(BlockPos pos, BlockState state,
		CallbackInfoReturnable<Boolean> cir) {
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
			isLarge = PlacingCogwheelChain.isLargeBlockTarget(state);
			hasOffset = PlacingCogwheelChain.hasSmallCogwheelOffset(state);
		}

		PlacingCogwheelNode newNode = new PlacingCogwheelNode(pos, axis, isLarge, hasOffset);
		nodes.add(newNode);
		cir.setReturnValue(true);
	}

	@Inject(method = "tryAddNode", at = @At("RETURN"), remap = false)
	private void createtricks$clearLastNodeFlag(CallbackInfoReturnable<Boolean> cir) {
		BnBKineticsCoreNodes.LAST_NODE_IS_SPELL.remove();
	}

	@Redirect(method = "checkMatchingNodesInLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;"),
		remap = false)
	private Comparable<?> createtricks$fixCheckMatchingAxis(BlockState state, Property<?> property) {
		if (BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			return BnBKineticsCoreNodes.getFacing(state).getAxis();
		return state.getValue(property);
	}

	@Inject(method = "checkMatchingNodesInLevel", at = @At("HEAD"), cancellable = true, remap = false)
	private void createtricks$rejectInvalidSpellConstruct(Level level, CallbackInfoReturnable<Boolean> cir) {
		for (PlacingCogwheelNode node : getVisitedNodes()) {
			if (!BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos()))
				continue;
			if (!BnBKineticsCoreNodes.hasAnyKineticsCore(level, node.pos())
				|| BnBKineticsCoreNodes.isAlreadyLinked(level, node.pos())) {
				cir.setReturnValue(false);
				return;
			}
		}
	}

	@Inject(method = "isValidBlockTarget", at = @At("RETURN"), cancellable = true, remap = false)
	private static void createtricks$acceptSpellConstruct(BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue() && BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			cir.setReturnValue(true);
	}

	@Inject(method = "isLargeBlockTarget", at = @At("HEAD"), cancellable = true, remap = false)
	private static void createtricks$spellConstructNotLarge(BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			cir.setReturnValue(false);
	}

	@Inject(method = "hasSmallCogwheelOffset", at = @At("HEAD"), cancellable = true, remap = false)
	private static void createtricks$spellConstructNoOffset(BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
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
