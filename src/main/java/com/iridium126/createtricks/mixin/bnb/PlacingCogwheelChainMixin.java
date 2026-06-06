package com.iridium126.createtricks.mixin.bnb;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes;

@Mixin(targets = "com.kipti.bnb.content.cogwheel_chain.graph.PlacingCogwheelChain", remap = false)
public abstract class PlacingCogwheelChainMixin {

	@Redirect(method = "tryAddNode",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;"),
		remap = false)
	private Comparable<?> createtricks$fixTryAddNodeAxis(BlockState state, Property<?> property) {
		if (BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			return BnBKineticsCoreNodes.getFacing(state).getAxis();
		return state.getValue(property);
	}

	@Redirect(method = "checkMatchingNodesInLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;"),
		remap = false)
	private Comparable<?> createtricks$fixCheckMatchingAxis(BlockState state, Property<?> property) {
		if (BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			return BnBKineticsCoreNodes.getFacing(state).getAxis();
		return state.getValue(property);
	}

	@Inject(method = "isValidBlockTarget", at = @At("RETURN"), cancellable = true, remap = false)
	private static void createtricks$acceptSpellConstruct(BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue() && BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			cir.setReturnValue(true);
	}

	@Inject(method = "isLargeBlockTarget", at = @At("HEAD"), cancellable = true, remap = false)
	private static void createtricks$spellConstructNotLarge(BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			cir.setReturnValue(false);
	}

	@Inject(method = "hasSmallCogwheelOffset", at = @At("HEAD"), cancellable = true, remap = false)
	private static void createtricks$spellConstructNoOffset(BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			cir.setReturnValue(false);
	}
}
