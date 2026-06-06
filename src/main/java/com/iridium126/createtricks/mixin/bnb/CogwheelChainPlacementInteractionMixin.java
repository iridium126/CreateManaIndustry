package com.iridium126.createtricks.mixin.bnb;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes;

@Mixin(targets = "com.kipti.bnb.content.cogwheel_chain.item.CogwheelChainPlacementInteraction", remap = false)
public abstract class CogwheelChainPlacementInteractionMixin {

	@Redirect(method = "onRightClick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;"),
		remap = false)
	private static Comparable<?> createtricks$fixOnRightClickAxis(BlockState state, Property<?> property) {
		if (BnBKineticsCoreNodes.isModularSpellConstructBlock(state.getBlock()))
			return BnBKineticsCoreNodes.getFacing(state).getAxis();
		return state.getValue(property);
	}
}
