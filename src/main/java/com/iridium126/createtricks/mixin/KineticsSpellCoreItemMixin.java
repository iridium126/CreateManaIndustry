package com.iridium126.createtricks.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.items.KineticsSpellCoreItem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Injects into Trickster's {@code SpellCoreItem.getExecutionLimit()} so that
 * kinetics spell cores scale the execution limit by the speed of the connected
 * kinetic network.
 * <p>
 * Formula: {@code |speed| / 32 * originalExecutionLimit}. A stopped network
 * (speed = 0) results in 0 — no execution through this core.
 */
@Mixin(targets = "dev.enjarai.trickster.item.SpellCoreItem", remap = false)
public abstract class KineticsSpellCoreItemMixin {

	private static final String COGWHEEL_CHAIN_BE_CLASS =
			"com.kipti.bnb.content.cogwheel_chain.block.CogwheelChainBlockEntity";

	/** Search radius for connected CogwheelChain controllers. */
	private static final int CHAIN_SEARCH_RADIUS = 8;

	@Inject(method = "getExecutionLimit", at = @At("RETURN"), cancellable = true, remap = false)
	private void createtricks$speedBasedExecutionLimit(ServerLevel world, Vec3 pos, int originalLimit,
			CallbackInfoReturnable<Integer> cir) {
		// Only apply to kinetics spell core items
		Item item = (Item) (Object) this;
		if (!KineticsSpellCoreItem.is(item.getDefaultInstance()))
			return;

		float speed = getConnectedChainSpeed(world, BlockPos.containing(pos));
		if (speed == 0) {
			cir.setReturnValue(0);
			return;
		}
		cir.setReturnValue(Math.max(1, (int) (speed / 32.0f * originalLimit)));
	}

	private static float getConnectedChainSpeed(Level level, BlockPos spellPos) {
		int r = CHAIN_SEARCH_RADIUS;
		for (BlockPos checkPos : BlockPos.betweenClosed(
				spellPos.offset(-r, -r, -r),
				spellPos.offset(r, r, r))) {
			BlockEntity be = level.getBlockEntity(checkPos);
			if (be == null)
				continue;
			try {
				Class<?> chainBEClass = Class.forName(COGWHEEL_CHAIN_BE_CLASS);
				if (!chainBEClass.isInstance(be))
					continue;

				boolean isController = (Boolean) chainBEClass.getMethod("isController").invoke(be);
				if (!isController)
					continue;

				Object chain = chainBEClass.getMethod("getChain").invoke(be);
				if (chain == null)
					continue;

				@SuppressWarnings("unchecked")
				List<Object> nodes = (List<Object>) chain.getClass()
						.getMethod("getChainPathCogwheelNodes").invoke(chain);
				BlockPos controllerPos = be.getBlockPos();

				for (Object node : nodes) {
					BlockPos localPos = (BlockPos) node.getClass().getMethod("localPos").invoke(node);
					if (controllerPos.offset(localPos).equals(spellPos)) {
						return Math.abs((Float) chainBEClass.getMethod("getSpeed").invoke(be));
					}
				}
			} catch (ReflectiveOperationException ignored) {
			}
		}
		return 0;
	}
}
