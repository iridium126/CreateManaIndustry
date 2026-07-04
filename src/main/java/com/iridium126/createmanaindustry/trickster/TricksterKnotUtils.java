package com.iridium126.createmanaindustry.trickster;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Utility methods for identifying Trickster knot items and block entities.
 * <p>
 * All knot-related type checks that were previously scattered across
 * {@link TricksterReflection} live here, delegating to the shared reflection
 * fields initialized by {@link TricksterReflection#ensureChargeInit()}.
 */
public final class TricksterKnotUtils {

	private TricksterKnotUtils() {}

	// ---- knot item checks ------------------------------------------------

	public static boolean isKnotItem(Item item) {
		return TricksterReflection.ensureChargeInit()
				&& item != null
				&& TricksterReflection.knotItemClass.isInstance(item);
	}

	public static boolean isKnotStack(ItemStack stack) {
		return stack != null && !stack.isEmpty() && isKnotItem(stack.getItem());
	}

	// ---- block entity checks ---------------------------------------------

	public static boolean isTricksterKnotBlockEntity(BlockEntity be) {
		if (!TricksterReflection.ensureChargeInit() || be == null)
			return false;
		return TricksterReflection.chargingArrayBlockEntityClass.isInstance(be)
				|| TricksterReflection.spellConstructBlockEntityClass.isInstance(be)
				|| TricksterReflection.modularSpellConstructBlockEntityClass.isInstance(be);
	}

	public static boolean isSpellConstructBlockEntity(BlockEntity be) {
		if (!TricksterReflection.ensureChargeInit() || be == null)
			return false;
		return TricksterReflection.spellConstructBlockEntityClass.isInstance(be);
	}

	public static boolean isModularSpellConstructBlockEntity(BlockEntity be) {
		if (!TricksterReflection.ensureChargeInit() || be == null)
			return false;
		return TricksterReflection.modularSpellConstructBlockEntityClass.isInstance(be);
	}
}
