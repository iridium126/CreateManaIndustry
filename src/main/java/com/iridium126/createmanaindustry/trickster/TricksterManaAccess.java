package com.iridium126.createmanaindustry.trickster;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Read / write / transfer mana on Trickster knot items and block entities.
 * <p>
 * All mana-related operations that require the reflection bridge live here.
 * Every public method gates on {@link TricksterReflection#ensureChargeInit()}.
 */
public final class TricksterManaAccess {

	private TricksterManaAccess() {}

	// ---- public: query ---------------------------------------------------

	/** Returns the current liquid-mana amount in the knot stack (0 if unavailable). */
	public static float getMana(ItemStack stack) {
		return getMana(stack, null);
	}

	/** Returns the current liquid-mana amount in the knot stack (0 if unavailable). */
	public static float getMana(ItemStack stack, @Nullable Level level) {
		return readManaValue(stack, level, false);
	}

	/** Returns the maximum liquid-mana capacity of the knot stack (0 if unavailable). */
	public static float getMaxMana(ItemStack stack, @Nullable Level level) {
		return readManaValue(stack, level, true);
	}

	/** Returns {@code true} when the stack has an infinite mana pool. */
	public static boolean hasInfiniteMana(ItemStack stack) {
		if (!TricksterReflection.ensureChargeInit() || stack == null || stack.isEmpty())
			return false;

		try {
			ManaAccess access = getManaAccess(stack);
			return access != null && isInfiniteManaPool(access.pool());
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	// ---- public: transfer ------------------------------------------------

	/**
	 * Drain up to {@code manaAmount} traditional mana from the stack,
	 * returning the amount actually removed.
	 */
	public static float drainMana(ItemStack stack, Level level, float manaAmount) {
		if (!TricksterReflection.ensureChargeInit() || stack == null || stack.isEmpty() || level == null || manaAmount <= 0)
			return 0;

		try {
			ManaAccess access = getManaAccess(stack);
			if (access == null)
				return 0;
			if (isInfiniteManaPool(access.pool()))
				return manaAmount;
			Object variant = getPoolVariant(access.pool(), level);
			if (!isLiquidManaVariant(variant))
				return 0;

			long requested = toTricksterMana(manaAmount);
			if (requested <= 0)
				return 0;

			Object mutablePool = TricksterReflection.manaPoolMakeCloneMethod.invoke(access.pool(), level);
			long leftover = (long) TricksterReflection.mutableManaPoolUseMethod.invoke(mutablePool,
					TricksterReflection.traditionalManaVariant, requested, level);
			long consumed = requested - leftover;
			if (consumed <= 0)
				return 0;

			Object updatedComponent = TricksterReflection.manaComponentWithMethod.invoke(access.component(), mutablePool);
			TricksterReflection.itemStackSetComponentMethod.invoke(stack,
					TricksterReflection.manaComponentType, updatedComponent);
			return fromTricksterMana(consumed);
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	/**
	 * Refill up to {@code manaAmount} traditional mana into the stack,
	 * returning the amount actually inserted.
	 */
	public static float refillMana(ItemStack stack, Level level, float manaAmount) {
		if (!TricksterReflection.ensureChargeInit() || stack == null || stack.isEmpty() || level == null || manaAmount <= 0)
			return 0;

		try {
			ManaAccess access = getManaAccess(stack);
			if (access == null || isInfiniteManaPool(access.pool()))
				return 0;
			Object variant = getPoolVariant(access.pool(), level);
			if (!canAcceptLiquidMana(variant))
				return 0;

			long requested = toTricksterMana(manaAmount);
			if (requested <= 0)
				return 0;

			Object mutablePool = TricksterReflection.manaPoolMakeCloneMethod.invoke(access.pool(), level);
			long leftover = (long) TricksterReflection.mutableManaPoolRefillMethod.invoke(mutablePool,
					TricksterReflection.traditionalManaVariant, requested, level);
			long inserted = requested - leftover;
			if (inserted <= 0)
				return 0;

			Object updatedComponent = TricksterReflection.manaComponentWithMethod.invoke(access.component(), mutablePool);
			TricksterReflection.itemStackSetComponentMethod.invoke(stack,
					TricksterReflection.manaComponentType, updatedComponent);
			return fromTricksterMana(inserted);
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	// ---- public: charging block entities ---------------------------------

	/**
	 * Charge all knot items in the Trickster block entity at {@code targetPos}
	 * with {@code manaAmount} traditional mana.
	 *
	 * @return {@code true} if at least one knot received mana.
	 */
	public static boolean chargeKnotsAt(ServerLevel level, BlockPos targetPos, float manaAmount) {
		if (!TricksterReflection.ensureChargeInit() || manaAmount <= 0)
			return false;

		BlockEntity target = level.getBlockEntity(targetPos);
		if (target == null)
			return false;

		return chargeKnotsInBlockEntity(level, target, manaAmount);
	}

	// ---- public: trickster spell mana ------------------------------------

	/**
	 * Consume traditional mana from a spell context.
	 * <p>
	 * This method depends on {@link TricksterReflection#ensureRegisterInit()}
	 * because the {@code useMana / useScaledMana} method is discovered during
	 * registration-phase initialization.
	 */
	public static void useTraditionalMana(Object spellContext, Object trick, double amount)
			throws ReflectiveOperationException {
		if (!TricksterReflection.ensureRegisterInit())
			return;

		Class<?> amountType = TricksterReflection.spellContextUseManaMethod.getParameterTypes()[1];
		if (amountType == float.class) {
			TricksterReflection.spellContextUseManaMethod.invoke(spellContext, trick, (float) amount);
		} else {
			TricksterReflection.spellContextUseManaMethod.invoke(spellContext, trick, amount);
		}
	}

	// ---- public: knot crafting -------------------------------------------

	/**
	 * Returns the Trickster-defined creation cost of a knot item, or
	 * {@code fallback} when reflection is unavailable.
	 */
	public static float getCreationCost(Item item, float fallback) {
		if (!TricksterReflection.ensureChargeInit() || item == null
				|| !TricksterReflection.knotItemClass.isInstance(item))
			return fallback;
		try {
			return (float) TricksterReflection.getCreationCostMethod.invoke(item);
		} catch (ReflectiveOperationException e) {
			return fallback;
		}
	}

	/**
	 * Transfer mana / properties from a knot {@code input} to the
	 * {@code output} stack (e.g. after mechanical pressing).
	 */
	public static ItemStack applyKnotTransfer(Level level, ItemStack input, ItemStack output) {
		if (!TricksterReflection.ensureChargeInit() || !TricksterKnotUtils.isKnotStack(input))
			return output;
		try {
			Object crackedVersion = TricksterReflection.getCrackedVersionMethod.invoke(input.getItem());
			if (crackedVersion == null)
				return output;
			return (ItemStack) TricksterReflection.transferPropertiesToCrackedMethod.invoke(
					input.getItem(), level, input, output);
		} catch (ReflectiveOperationException e) {
			CreateManaIndustry.LOGGER.debug("Failed to transfer knot properties during pressing", e);
			return output;
		}
	}

	// ---- internals -------------------------------------------------------

	private record ManaAccess(Object component, Object pool) {}

	private static float readManaValue(ItemStack stack, @Nullable Level level, boolean includeBlankCapacity) {
		if (!TricksterReflection.ensureChargeInit() || stack == null || stack.isEmpty() || level == null)
			return 0;

		try {
			ManaAccess access = getManaAccess(stack);
			if (access == null)
				return 0;

			Object variant = getPoolVariant(access.pool(), level);
			if (includeBlankCapacity) {
				if (!canAcceptLiquidMana(variant))
					return 0;
				return fromTricksterMana((long) TricksterReflection.manaPoolGetMaxManaMethod.invoke(access.pool(), level));
			}

			if (!isLiquidManaVariant(variant))
				return 0;
			return fromTricksterMana((long) TricksterReflection.manaPoolGetManaMethod.invoke(access.pool(), level));
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	private static boolean isInfiniteManaPool(Object pool) {
		return TricksterReflection.infiniteManaPoolClass != null
				&& TricksterReflection.infiniteManaPoolClass.isInstance(pool);
	}

	@Nullable
	private static ManaAccess getManaAccess(ItemStack stack) throws ReflectiveOperationException {
		Object component = TricksterReflection.itemStackGetComponentMethod.invoke(stack,
				TricksterReflection.manaComponentType);
		if (component == null)
			return null;

		Object pool = TricksterReflection.manaComponentPoolMethod.invoke(component);
		if (pool == null)
			return null;

		return new ManaAccess(component, pool);
	}

	private static Object getPoolVariant(Object pool, Level level) throws ReflectiveOperationException {
		return TricksterReflection.manaPoolGetVariantMethod.invoke(pool, level);
	}

	private static boolean canAcceptLiquidMana(Object variant) throws ReflectiveOperationException {
		return variant != null && (isBlankManaVariant(variant) || isLiquidManaVariant(variant));
	}

	private static boolean isBlankManaVariant(Object variant) throws ReflectiveOperationException {
		return variant != null
				&& TricksterReflection.manaVariantGetManaMethod.invoke(variant) == TricksterReflection.emptyMana;
	}

	private static boolean isLiquidManaVariant(Object variant) throws ReflectiveOperationException {
		return variant != null
				&& TricksterReflection.manaVariantGetManaMethod.invoke(variant) == TricksterReflection.traditionalMana;
	}

	private static long toTricksterMana(float manaAmount) {
		return manaAmount <= 0 ? 0 : Math.max(0L, Math.round(manaAmount * TricksterReflection.manaScale));
	}

	private static float fromTricksterMana(long manaAmount) {
		return manaAmount <= 0 ? 0 : manaAmount / (float) TricksterReflection.manaScale;
	}

	private static boolean chargeKnotsInBlockEntity(ServerLevel level, BlockEntity blockEntity, float manaAmount) {
		try {
			if (TricksterReflection.spellConstructBlockEntityClass.isInstance(blockEntity)) {
				if (!(blockEntity instanceof Container container))
					return false;
				if (chargeKnotStack(level, container.getItem(0), manaAmount)) {
					markDirtyAndUpdateClients(blockEntity);
					return true;
				}
				return false;
			}

			if (TricksterReflection.modularSpellConstructBlockEntityClass.isInstance(blockEntity)) {
				if (!(blockEntity instanceof Container container) || container.isEmpty())
					return false;
				if (chargeKnotStack(level, container.getItem(0), manaAmount)) {
					markDirtyAndUpdateClients(blockEntity);
					return true;
				}
				return false;
			}

			if (TricksterReflection.chargingArrayBlockEntityClass.isInstance(blockEntity)) {
				if (!(blockEntity instanceof Container container))
					return false;

				boolean changed = false;
				int knotCount = 0;
				for (int i = 0; i < container.getContainerSize(); i++) {
					if (TricksterKnotUtils.isKnotStack(container.getItem(i)))
						knotCount++;
				}
				if (knotCount == 0)
					return false;

				float share = manaAmount / knotCount;
				for (int i = 0; i < container.getContainerSize(); i++) {
					ItemStack stack = container.getItem(i);
					if (TricksterKnotUtils.isKnotStack(stack) && chargeKnotStack(level, stack, share))
						changed = true;
				}
				if (changed)
					markDirtyAndUpdateClients(blockEntity);
				return changed;
			}
		} catch (ReflectiveOperationException e) {
			CreateManaIndustry.LOGGER.error("Failed to charge trickster knot mana", e);
		}

		return false;
	}

	private static void markDirtyAndUpdateClients(BlockEntity blockEntity) throws ReflectiveOperationException {
		blockEntity.getClass().getMethod("markDirtyAndUpdateClients").invoke(blockEntity);
	}

	private static boolean chargeKnotStack(ServerLevel level, ItemStack stack, float manaAmount)
			throws ReflectiveOperationException {
		if (!TricksterKnotUtils.isKnotStack(stack) || manaAmount <= 0)
			return false;

		Object component = TricksterReflection.itemStackGetComponentMethod.invoke(stack,
				TricksterReflection.manaComponentType);
		if (component == null)
			return false;

		Object pool = TricksterReflection.manaComponentPoolMethod.invoke(component);
		Object variant = getPoolVariant(pool, level);
		if (!canAcceptLiquidMana(variant))
			return false;

		long requested = toTricksterMana(manaAmount);
		if (requested <= 0)
			return false;

		Object mutablePool = TricksterReflection.manaPoolMakeCloneMethod.invoke(pool, level);
		long leftover = (long) TricksterReflection.mutableManaPoolRefillMethod.invoke(mutablePool,
				TricksterReflection.traditionalManaVariant, requested, level);
		if (leftover >= requested)
			return false;

		Object updatedComponent = TricksterReflection.manaComponentWithMethod.invoke(component, mutablePool);
		TricksterReflection.itemStackSetComponentMethod.invoke(stack,
				TricksterReflection.manaComponentType, updatedComponent);
		return true;
	}
}
