package com.iridium126.createtricks.mixin.bnb;

import java.util.List;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.bnb.BnBChainRenderContext;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.RenderedChainPathNode;
import com.mojang.logging.LogUtils;

@Mixin(targets = "com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain", remap = false)
public abstract class CogwheelChainPathNodesMixin {

	private static final Logger LOG = LogUtils.getLogger();

	@Inject(method = "getChainPathNodes", at = @At("RETURN"), cancellable = true, remap = false)
	private void createtricks$expandSpellConstructPathNodes(
			CallbackInfoReturnable<List<RenderedChainPathNode>> cir) {
		if (!BnBChainRenderContext.hasContext())
			return;

		List<RenderedChainPathNode> original = cir.getReturnValue();
		List<RenderedChainPathNode> expanded = BnBChainRenderContext
				.expandSpellConstructNodes(original);
		if (expanded != original) {
			LOG.debug("Expanded chain nodes {} → {}",
					original.size(), expanded.size());
			cir.setReturnValue(expanded);
		}
	}
}
