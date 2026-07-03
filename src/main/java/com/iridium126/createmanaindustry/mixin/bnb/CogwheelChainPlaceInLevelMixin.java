package com.iridium126.createmanaindustry.mixin.bnb;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PlacingCogwheelNode;
import com.kipti.bnb.content.kinetics.cogwheel_chain.types.CogwheelChainType;

@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain", remap = false)
public abstract class CogwheelChainPlaceInLevelMixin {

	@Shadow
	public abstract CogwheelChainType getChainType();

	@Invoker("placeChainCogwheelInLevel")
	abstract void createtricks$invokePlaceChainCogwheelInLevel(Level level, PlacingCogwheelNode node,
			boolean isController, int chainsRequired, BlockPos controllerPos, boolean isCreative);

	@Inject(method = "placeInLevel", at = @At("HEAD"), cancellable = true, remap = false)
	private void createtricks$customPlaceInLevel(Level level, PlacingCogwheelChain placingChain,
			boolean isCreative, CallbackInfo ci) {
		boolean hasSpellConstruct = false;
		for (PlacingCogwheelNode node : placingChain.getVisitedNodes()) {
			if (BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos())) {
				hasSpellConstruct = true;
				break;
			}
		}
		if (!hasSpellConstruct)
			return;

		ci.cancel();
		boolean isFirst = true;
		BlockPos controllerPos = placingChain.getFirstNode().pos();
		int chainsRequired = placingChain.getChainsRequiredInLoop(getChainType());

		for (PlacingCogwheelNode node : placingChain.getVisitedNodes()) {
			if (BnBKineticsCoreNodes.isModularSpellConstruct(level, node.pos())) {
				isFirst = false;
				continue;
			}
			createtricks$invokePlaceChainCogwheelInLevel(level, node, isFirst, chainsRequired,
					controllerPos, isCreative);
			isFirst = false;
		}
	}
}
