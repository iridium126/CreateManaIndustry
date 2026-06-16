package com.iridium126.createtricks.mixin.bnb;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.iridium126.createtricks.content.kinetics.bnb.BnBReflection;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;

/**
 * Makes kinetics spell cores apply real stress to the Create kinetic network,
 * just like a regular {@code KineticBlockEntity}.
 * <p>
 * Each core in a connected modular spell construct contributes {@value #STRESS_PER_CORE}
 * stress units (&times; speed) to the chain controller's stress impact, so the
 * network treats the cores as genuine stress consumers.
 * <p>
 * Targets {@link KineticBlockEntity} (not the BnB class directly) because
 * {@code calculateStressApplied} is declared on the Create base class; targeting
 * the subclass would fail with a no-refmap setup since Mixin can't resolve
 * inherited methods when {@code remap = false}.
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class CogwheelChainBlockEntityStressMixin {

	/** Stress impact (SU per RPM) contributed by each kinetics spell core. */
	private static final float STRESS_PER_CORE = 4.0f;

	@Shadow
	protected float lastStressApplied;

	/**
	 * Augments {@code calculateStressApplied} with {@value #STRESS_PER_CORE} stress
	 * per kinetics core for chain controllers connected to modular spell
	 * constructs. Non-chain block entities pass through unchanged.
	 */
	@Inject(method = "calculateStressApplied", at = @At("RETURN"), cancellable = true, remap = false)
	private void createtricks$addKineticsCoreStress(CallbackInfoReturnable<Float> cir) {
		if (!BnBReflection.isChainBE(this))
			return;
		if (!BnBReflection.isController(this))
			return;

		Object chain = BnBReflection.getChain(this);
		if (chain == null)
			return;

		KineticBlockEntity kbe = (KineticBlockEntity) (Object) this;
		if (kbe.getLevel() == null)
			return;

		List<Object> nodes = BnBReflection.getChainPathCogwheelNodes(chain);
		BlockPos controllerPos = kbe.getBlockPos();

		int coreCount = 0;
		for (Object node : nodes) {
			BlockPos nodeWorldPos = controllerPos.offset(BnBReflection.localPos(node));
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
