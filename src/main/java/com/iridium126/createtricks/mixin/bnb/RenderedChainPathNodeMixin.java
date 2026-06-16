package com.iridium126.createtricks.mixin.bnb;

import com.iridium126.createtricks.content.kinetics.bnb.BnBChainRenderContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.kipti.bnb.content.cogwheel_chain.graph.RenderedChainPathNode", remap = false)
public abstract class RenderedChainPathNodeMixin {
	@Shadow(remap = false)
	public abstract BlockPos relativePos();

	@Shadow(remap = false)
	public abstract Vec3 nodeOffset();

	@Inject(method = "getPosition", at = @At("RETURN"), cancellable = true, remap = false)
	private void createtricks$shrinkKineticsCoreEndpoint(CallbackInfoReturnable<Vec3> cir) {
		cir.setReturnValue(BnBChainRenderContext.adjustKineticsCorePosition(relativePos(), nodeOffset(), cir.getReturnValue()));
	}
}
