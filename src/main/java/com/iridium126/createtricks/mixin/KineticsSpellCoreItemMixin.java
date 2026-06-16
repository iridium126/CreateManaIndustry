package com.iridium126.createtricks.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.items.KineticsSpellCoreItem;
import com.iridium126.createtricks.content.kinetics.bnb.BnBReflection;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
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

	private static final int CHAIN_SEARCH_RADIUS = 8;

	@Inject(method = "getExecutionLimit", at = @At("RETURN"), cancellable = true, remap = false)
	private void createtricks$speedBasedExecutionLimit(ServerLevel world, Vec3 pos, int originalLimit,
			CallbackInfoReturnable<Integer> cir) {
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

	// -----------------------------------------------------------------------
	// Speed lookup — cached per tick via BnBReflection (avoids O(n³) scans)
	// -----------------------------------------------------------------------

	private static float getConnectedChainSpeed(Level level, BlockPos spellPos) {
		long gameTime = level.getGameTime();
		float cached = BnBReflection.getCachedChainSpeed(spellPos, gameTime);
		if (cached >= 0)
			return cached;

		float speed = scanConnectedChainSpeed(level, spellPos);
		BnBReflection.putCachedChainSpeed(spellPos, speed, gameTime);
		return speed;
	}

	private static float scanConnectedChainSpeed(Level level, BlockPos spellPos) {
		int r = CHAIN_SEARCH_RADIUS;
		for (BlockPos checkPos : BlockPos.betweenClosed(
				spellPos.offset(-r, -r, -r),
				spellPos.offset(r, r, r))) {
			var be = level.getBlockEntity(checkPos);
			if (be == null || !BnBReflection.isChainBE(be))
				continue;
			if (!BnBReflection.isController(be))
				continue;

			Object chain = BnBReflection.getChain(be);
			if (chain == null)
				continue;

			List<Object> nodes = BnBReflection.getChainPathCogwheelNodes(chain);
			BlockPos controllerPos = be.getBlockPos();

			for (Object node : nodes) {
				if (controllerPos.offset(BnBReflection.localPos(node)).equals(spellPos))
					return Math.abs(BnBReflection.getSpeed(be));
			}
		}
		return 0;
	}
}
