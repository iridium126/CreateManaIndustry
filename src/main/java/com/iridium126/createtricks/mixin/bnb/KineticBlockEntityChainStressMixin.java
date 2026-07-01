package com.iridium126.createtricks.mixin.bnb;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.iridium126.createtricks.content.kinetics.bnb.BnBCompact;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/**
 * Makes kinetics spell cores consume Create stress through the chain
 * controller. Each core in a connected spell construct adds
 * {@value #STRESS_PER_CORE} SU &times; speed.
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class KineticBlockEntityChainStressMixin {

	private static final float STRESS_PER_CORE = 4.0f;

	@Shadow
	protected float lastStressApplied;

	@Inject(method = "calculateStressApplied", at = @At("RETURN"),
			cancellable = true, remap = false)
	private void createtricks$addKineticsCoreStress(
			CallbackInfoReturnable<Float> cir) {
		CogwheelChain chain = BnBCompact.getChainIfController(this);
		if (chain == null) return;

		KineticBlockEntity kbe = (KineticBlockEntity) (Object) this;
		if (kbe.getLevel() == null) return;

		var nodes = chain.getChainPathCogwheelNodes();
		var controllerPos = kbe.getBlockPos();

		int coreCount = 0;
		for (var node : nodes) {
			var worldPos = controllerPos.offset(node.localPos());
			if (BnBKineticsCoreNodes.isModularSpellConstruct(
					kbe.getLevel(), worldPos)) {
				coreCount += BnBKineticsCoreNodes.getKineticsCoreCount(
						kbe.getLevel(), worldPos);
			}
		}

		if (coreCount > 0) {
			float total = cir.getReturnValueF()
					+ coreCount * STRESS_PER_CORE;
			lastStressApplied = total;
			cir.setReturnValue(total);
		}
	}
}
