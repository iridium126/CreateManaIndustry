package com.iridium126.createtricks.mixin.bnb;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.cogwheel_chain.graph.ChainInteractionFailedException;
import com.kipti.bnb.content.cogwheel_chain.graph.PlacingCogwheelChain;
import com.kipti.bnb.content.cogwheel_chain.graph.PlacingCogwheelNode;

@Mixin(targets = "com.kipti.bnb.content.cogwheel_chain.item.CogwheelChainPlacementInteraction", remap = false)
public abstract class CogwheelChainPlacementInteractionMixin {

	@Shadow
	static PlacingCogwheelChain currentBuildingChain;

	private static final ThreadLocal<Minecraft> CAPTURED_MC = new ThreadLocal<>();

	@Inject(method = "onRightClick",
		at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/event/InputEvent$InteractionKeyMappingTriggered;setSwingHand(Z)V"),
		cancellable = true,
		remap = false)
	private static void createtricks$handleSpellConstructInteraction(
		net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event,
		CallbackInfoReturnable<Boolean> cir) {

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || !(mc.hitResult instanceof BlockHitResult hit))
			return;

		BlockPos pos = hit.getBlockPos();

		if (currentBuildingChain != null) {
			PlacingCogwheelNode lastNode = currentBuildingChain.getLastNode();
			if (lastNode != null && !lastNode.pos().equals(pos)
				&& BnBKineticsCoreNodes.isModularSpellConstruct(level, lastNode.pos())) {
				BnBKineticsCoreNodes.LAST_NODE_IS_SPELL.set(true);
			}
		}

		if (!BnBKineticsCoreNodes.isModularSpellConstruct(level, pos))
			return;

		if (!BnBKineticsCoreNodes.hasAnyKineticsCore(level, pos)) {
			if (mc.player != null)
				mc.player.displayClientMessage(Component.translatable("createtricks.bnb_chain.no_core"), true);
			cir.setReturnValue(true);
			return;
		}

		if (BnBKineticsCoreNodes.isAlreadyLinked(level, pos)) {
			if (mc.player != null)
				mc.player.displayClientMessage(Component.translatable("createtricks.bnb_chain.already_linked"), true);
			cir.setReturnValue(true);
			return;
		}

		CAPTURED_MC.set(mc);
	}

	@Inject(method = "onRightClick", at = @At("RETURN"), remap = false)
	private static void createtricks$clearCapturedMc(
		net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event,
		CallbackInfoReturnable<Boolean> cir) {
		CAPTURED_MC.remove();
	}

	@Redirect(method = "onRightClick",
		at = @At(value = "INVOKE", target = "Lcom/kipti/bnb/content/cogwheel_chain/graph/PlacingCogwheelChain;canBuildChainIfLooping()Z"),
		remap = false)
	private static boolean createtricks$allowLoopClosureForSpellConstruct(PlacingCogwheelChain chain) {
		try {
			return chain.canBuildChainIfLooping();
		} catch (ChainInteractionFailedException e) {
			Minecraft mc = CAPTURED_MC.get();
			if (mc == null || mc.level == null)
				return false;

			for (PlacingCogwheelNode node : chain.getVisitedNodes()) {
				if (BnBKineticsCoreNodes.isModularSpellConstruct(mc.level, node.pos()))
					return true;
			}
			return false;
		}
	}

	@Redirect(method = "onRightClick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;"),
		remap = false)
	private static Comparable<?> createtricks$fixOnRightClickAxis(BlockState state, Property<?> property) {
		if (BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			return BnBKineticsCoreNodes.getFacing(state).getAxis();
		return state.getValue(property);
	}
}
