package com.iridium126.createtricks.mixin.bnb;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChainCandidate;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelNode;
import com.kipti.bnb.content.kinetics.cogwheel_chain.placement.ChainInteractionFailedException;
import com.kipti.bnb.content.kinetics.cogwheel_chain.placement.CogwheelChainPlacementInteraction;
import com.kipti.bnb.content.kinetics.cogwheel_chain.types.CogwheelChainType;

@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.placement.CogwheelChainPlacementInteraction", remap = false)
public abstract class CogwheelChainPlacementInteractionMixin {

	/**
	 * Before {@code rightClickForChain} processes a click, check whether the
	 * previous node was a spell construct and set the thread-local flag.
	 */
	@Inject(method = "rightClickForChain",
		at = @At(value = "INVOKE",
			target = "Lcom/kipti/bnb/content/kinetics/cogwheel_chain/graph/PlacingCogwheelChain;tryAddNode(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcom/kipti/bnb/content/kinetics/cogwheel_chain/types/CogwheelChainType;)Z"),
		remap = false)
	private static void createtricks$setLastNodeSpellFlag(
		net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event,
		ClientLevel level, BlockPos hitPos, BlockState targetedState,
		CogwheelChainCandidate targetedCandidate, CogwheelChainType heldChainType,
		ItemStack chainItemInHand, LocalPlayer player, CallbackInfo ci) {
		PlacingCogwheelChain chain = CogwheelChainPlacementInteraction.getCurrentBuildingChain();
		if (chain != null) {
			PlacingCogwheelNode lastNode = chain.getLastNode();
			if (lastNode != null && BnBKineticsCoreNodes.isModularSpellConstruct(level, lastNode.pos())) {
				BnBKineticsCoreNodes.LAST_NODE_IS_SPELL.set(true);
			}
		}
	}

	/**
	 * Allow loop closure via {@code tryCompleteLoop} when a spell construct is
	 * part of the chain.
	 */
	@Redirect(method = "rightClickForChain",
		at = @At(value = "INVOKE",
			target = "Lcom/kipti/bnb/content/kinetics/cogwheel_chain/graph/PlacingCogwheelChain;tryCompleteLoop()Z"),
		remap = false)
	private static boolean createtricks$allowLoopClosureForSpellConstruct(PlacingCogwheelChain chain)
			throws ChainInteractionFailedException {
		try {
			return chain.tryCompleteLoop();
		} catch (ChainInteractionFailedException e) {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.level == null)
				throw e;

			for (PlacingCogwheelNode node : chain.getVisitedNodes()) {
				if (BnBKineticsCoreNodes.isModularSpellConstruct(mc.level, node.pos()))
					return true;
			}
			throw e;
		}
	}

	/**
	 * Handle spell construct right-click interaction. Injected into
	 * {@code onClickInput} before {@code onRightClick} is called.
	 */
	@Inject(method = "onClickInput",
		at = @At(value = "INVOKE",
			target = "Lcom/kipti/bnb/content/kinetics/cogwheel_chain/placement/CogwheelChainPlacementInteraction;onRightClick(Lnet/neoforged/neoforge/client/event/InputEvent$InteractionKeyMappingTriggered;)Z"),
		cancellable = true, remap = false)
	private static void createtricks$handleSpellConstructInteraction(
		net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event,
		CallbackInfo ci) {

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || !(mc.hitResult instanceof BlockHitResult hit))
			return;

		BlockPos pos = hit.getBlockPos();

		PlacingCogwheelChain chain = CogwheelChainPlacementInteraction.getCurrentBuildingChain();
		if (chain != null) {
			PlacingCogwheelNode lastNode = chain.getLastNode();
			if (lastNode != null && !lastNode.pos().equals(pos)
				&& BnBKineticsCoreNodes.isModularSpellConstruct(level, lastNode.pos())) {
				BnBKineticsCoreNodes.LAST_NODE_IS_SPELL.set(true);
			}
		}

		if (!BnBKineticsCoreNodes.isModularSpellConstruct(level, pos))
			return;

		if (!BnBKineticsCoreNodes.hasAnyKineticsCore(level, pos)) {
			if (mc.player != null)
				mc.player.displayClientMessage(
					Component.translatable("createtricks.bnb_chain.no_core"), true);
			ci.cancel();
			event.setCanceled(true);
			return;
		}

		if (BnBKineticsCoreNodes.isAlreadyLinked(level, pos)) {
			if (mc.player != null)
				mc.player.displayClientMessage(
					Component.translatable("createtricks.bnb_chain.already_linked"), true);
			ci.cancel();
			event.setCanceled(true);
		}
	}
}
