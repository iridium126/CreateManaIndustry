package com.iridium126.createmanaindustry.mixin.bnb;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBChainRenderContext;
import com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviour;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PathedCogwheelNode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;

@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviourRenderer", remap = false)
public class CogwheelChainBehaviourRendererMixin {

    @Inject(
        method = "renderSafe(Lcom/cake/azimuth/behaviour/SuperBlockEntityBehaviour;Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At("HEAD"), remap = false)
    private void createmanaindustry$beginKineticsCoreRender(@Coerce Object behaviour,
            KineticBlockEntity be, float partialTicks, PoseStack ms,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        BnBChainRenderContext.begin(be);
        if (behaviour instanceof CogwheelChainBehaviour chainBehaviour)
            populateAngularVelocities(chainBehaviour, be);
    }

    @Inject(
        method = "renderSafe(Lcom/cake/azimuth/behaviour/SuperBlockEntityBehaviour;Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At("RETURN"), remap = false)
    private void createmanaindustry$endKineticsCoreRender(@Coerce Object behaviour,
            KineticBlockEntity be, float partialTicks, PoseStack ms,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        BnBChainRenderContext.end();
    }

    private static final float ANGULAR_VELOCITY_FACTOR = (float) (Math.PI / -300.0);

    private static void populateAngularVelocities(
            CogwheelChainBehaviour chainBehaviour, KineticBlockEntity be) {
        if (!chainBehaviour.isController()) return;

        CogwheelChain chain = chainBehaviour.getControlledChain();
        if (chain == null) return;

        float speed = be.getSpeed() * ANGULAR_VELOCITY_FACTOR;
        List<PathedCogwheelNode> nodes = chain.getChainPathCogwheelNodes();
        BlockPos cp = be.getBlockPos();

        for (PathedCogwheelNode node : nodes) {
            BnBChainRenderContext.putChainAngularVelocity(
                    cp.offset(node.localPos()), node.sideFactor() * speed);
        }
    }
}
