package com.iridium126.createtricks.mixin.bnb;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.iridium126.createtricks.content.kinetics.bnb.BnBReflection;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;

/**
 * Makes kinetics spell cores apply real stress to the Create kinetic network,
 * just like a regular {@code KineticBlockEntity}.
 * <p>
 * Each core in a connected modular spell construct contributes {@value #STRESS_PER_CORE}
 * stress units (&times; speed) to the chain controller's stress impact.
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class CogwheelChainBlockEntityStressMixin {

	private static final float STRESS_PER_CORE = 4.0f;

	@Shadow
	protected float lastStressApplied;

	@Inject(method = "calculateStressApplied", at = @At("RETURN"), cancellable = true, remap = false)
	private void createtricks$addKineticsCoreStress(CallbackInfoReturnable<Float> cir) {
		if (!BnBReflection.isChainBE(this))
			return;
		if (!BnBReflection.isController(this))
			return;

		CogwheelChain chain = BnBReflection.getChain(this);
		if (chain == null)
			return;

		KineticBlockEntity kbe = (KineticBlockEntity) (Object) this;
		if (kbe.getLevel() == null)
			return;

		var nodes = chain.getChainPathCogwheelNodes();
		BlockPos controllerPos = kbe.getBlockPos();

		int coreCount = 0;
		for (var node : nodes) {
			BlockPos nodeWorldPos = controllerPos.offset(node.localPos());
			if (BnBKineticsCoreNodes.isModularSpellConstruct(kbe.getLevel(), nodeWorldPos)) {
				coreCount += BnBKineticsCoreNodes.getKineticsCoreCount(kbe.getLevel(), nodeWorldPos);
			}
		}

		if (coreCount > 0) {
			float totalStress = cir.getReturnValueF() + coreCount * STRESS_PER_CORE;
			lastStressApplied = totalStress;
			cir.setReturnValue(totalStress);
		}
	}
}
