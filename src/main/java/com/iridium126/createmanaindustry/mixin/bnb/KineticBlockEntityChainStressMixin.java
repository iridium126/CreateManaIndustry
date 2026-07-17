package com.iridium126.createmanaindustry.mixin.bnb;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBCompat;
import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBKineticsCoreNodes;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PathedCogwheelNode;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;

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
    private void createmanaindustry$addKineticsCoreStress(
            CallbackInfoReturnable<Float> cir) {
        KineticBlockEntity kbe = (KineticBlockEntity) (Object) this;
        CogwheelChain chain = BnBCompat.getChainIfController(kbe);
        if (chain == null) return;

        if (kbe.getLevel() == null) return;

        List<PathedCogwheelNode> nodes = chain.getChainPathCogwheelNodes();
        BlockPos controllerPos = kbe.getBlockPos();

        int coreCount = 0;
        for (PathedCogwheelNode node : nodes) {
            BlockPos worldPos = controllerPos.offset(node.localPos());
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
