package com.iridium126.createmanaindustry.mixin.bnb;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.iridium126.createmanaindustry.content.kinetics.bnb.BnBChainRenderContext;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.world.phys.Vec3;

/**
 * Sets the render context before the Flywheel visual builds chain segments,
 * so that {@code CogwheelChainPathNodesMixin} can expand Spell Construct
 * positions during Flywheel rendering.
 */
@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviourVisual", remap = false)
public abstract class CogwheelChainBehaviourVisualMixin {

    @Shadow(remap = false)
    private KineticBlockEntity kineticBlockEntity;

    @SuppressWarnings("rawtypes")
    @Redirect(method = "rebuildMeshIfNeeded",
        at = @At(value = "INVOKE",
            target = "Lcom/kipti/bnb/content/kinetics/cogwheel_chain/render/CogwheelChainRenderGeometryBuilder;buildSegments(Lcom/kipti/bnb/content/kinetics/cogwheel_chain/graph/CogwheelChain;Lnet/minecraft/world/phys/Vec3;)Ljava/util/List;"),
        remap = false)
    private List createmanaindustry$expandBeforeFlywheelBuild(CogwheelChain chain, Vec3 origin) {
        BnBChainRenderContext.begin(kineticBlockEntity);
        try {
            return com.kipti.bnb.content.kinetics.cogwheel_chain.render.CogwheelChainRenderGeometryBuilder
                    .buildSegments(chain, origin);
        } finally {
            // Context kept alive for angular velocity lookups; cleared elsewhere
        }
    }
}
