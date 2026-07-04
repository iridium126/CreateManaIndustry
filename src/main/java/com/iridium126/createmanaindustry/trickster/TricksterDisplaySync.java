package com.iridium126.createmanaindustry.trickster;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.display.SpellConstructDisplayArguments;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Synchronizes {@code DisplayLink} argument overrides into live Trickster
 * spell executors so that text written by Create's Display Link appears
 * as spell arguments inside Spell Construct and Modular Spell Construct
 * block entities.
 * <p>
 * All methods depend on {@link TricksterReflection#ensureDisplayInit()}.
 */
public final class TricksterDisplaySync {

	private TricksterDisplaySync() {}

	// ---- public API ------------------------------------------------------

	/**
	 * Synchronize stored display arguments into every executor on the given
	 * block entity.  Safe to call on any BE — silently returns if the BE is
	 * not a Trickster spell construct.
	 */
	public static void syncExecutors(BlockEntity be) {
		if (!TricksterReflection.ensureDisplayInit())
			return;

		try {
			if (TricksterReflection.spellConstructBlockEntityClass.isInstance(be)) {
				Object executor = TricksterReflection.spellConstructExecutorField.get(be);
				syncExecutor(executor, be, -1);
			} else if (TricksterReflection.modularSpellConstructBlockEntityClass.isInstance(be)) {
				@SuppressWarnings("unchecked")
				List<Optional<Object>> executors = (List<Optional<Object>>) TricksterReflection.modularExecutorsField.get(be);
				for (int slot = 0; slot < executors.size()
						&& slot < SpellConstructDisplayArguments.MODULAR_EXECUTOR_SLOTS; slot++) {
					Optional<Object> optional = executors.get(slot);
					int executorSlot = slot;
					optional.ifPresent(executor -> syncExecutor(executor, be, executorSlot));
				}
			}
		} catch (ReflectiveOperationException e) {
			CreateManaIndustry.LOGGER.error("Failed to sync spell construct display arguments", e);
		}
	}

	/**
	 * Build a merged argument list for the given block entity (single-executor
	 * spell construct), combining live executor arguments with any overrides
	 * stored via the DisplayLink system.
	 */
	public static List<?> buildExecutorArguments(BlockEntity be) {
		if (!TricksterReflection.ensureDisplayInit())
			return List.of();

		return mergeDisplayArguments(be, -1, List.of());
	}

	/**
	 * Extract the {@code BlockEntity} from a Trickster {@code BlockSpellSource}.
	 */
	@Nullable
	public static BlockEntity getBlockEntityFromSource(Object source) {
		if (!TricksterReflection.ensureDisplayInit() || source == null)
			return null;

		try {
			return (BlockEntity) TricksterReflection.blockSpellSourceBlockEntityField.get(source);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	// ---- internals -------------------------------------------------------

	private static void syncExecutor(@Nullable Object executor, BlockEntity be, int executorSlot) {
		if (executor == null || !TricksterReflection.defaultSpellExecutorClass.isInstance(executor))
			return;

		try {
			Object state = TricksterReflection.spellExecutorGetDeepestStateMethod.invoke(executor);
			@SuppressWarnings("unchecked")
			List<Object> current = (List<Object>) TricksterReflection.executionStateGetArgumentsMethod.invoke(state);
			List<Object> merged = mergeDisplayArguments(be, executorSlot, current);

			if (current instanceof ArrayList<?> arrayList) {
				@SuppressWarnings("unchecked")
				ArrayList<Object> mutable = (ArrayList<Object>) arrayList;
				mutable.clear();
				mutable.addAll(merged);
			} else {
				Field argumentsField = state.getClass().getDeclaredField("arguments");
				argumentsField.setAccessible(true);
				argumentsField.set(state, merged);
			}
		} catch (ReflectiveOperationException e) {
			CreateManaIndustry.LOGGER.error("Failed to update spell executor arguments", e);
		}
	}

	private static List<Object> mergeDisplayArguments(BlockEntity be, int executorSlot, List<Object> base) {
		ArrayList<Object> merged = new ArrayList<>(base);
		for (int i = 0; i < SpellConstructDisplayArguments.MAX_ARGUMENTS; i++) {
			if (!SpellConstructDisplayArguments.hasStoredArgument(be, executorSlot, i))
				continue;

			Object fragment = getDisplayArgument(be, executorSlot, i);
			if (fragment == null)
				continue;

			while (merged.size() <= i)
				merged.add(TricksterReflection.voidFragmentInstance);
			merged.set(i, fragment);
		}
		return merged;
	}

	@Nullable
	private static Object getDisplayArgument(BlockEntity be, int executorSlot, int index) {
		if (!TricksterReflection.ensureDisplayInit())
			return null;

		String value = SpellConstructDisplayArguments.getArgumentString(be, executorSlot, index);
		if (value == null)
			return null;

		try {
			return TricksterReflection.stringFragmentCtor.newInstance(value);
		} catch (ReflectiveOperationException e) {
			CreateManaIndustry.LOGGER.error("Failed to create StringFragment", e);
			return null;
		}
	}
}
