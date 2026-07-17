package com.iridium126.createmanaindustry.trickster;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.display.SpellConstructDisplayArguments;

import dev.enjarai.trickster.block.ModularSpellConstructBlockEntity;
import dev.enjarai.trickster.block.SpellConstructBlockEntity;
import dev.enjarai.trickster.spell.SpellExecutor;
import dev.enjarai.trickster.spell.execution.ExecutionState;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.source.BlockSpellSource;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.fragment.StringFragment;
import dev.enjarai.trickster.spell.fragment.VoidFragment;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Synchronizes {@code DisplayLink} argument overrides into live Trickster
 * spell executors so that text written by Create's Display Link appears
 * as spell arguments inside Spell Construct and Modular Spell Construct
 * block entities.
 * <p>
 * All methods use direct Trickster API — no reflection except for the
 * private {@code arguments} field of {@link ExecutionState} (used only as a
 * fallback when the returned list is not an {@link ArrayList}).
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
        if (!CreateManaIndustry.TRICKSTER_ACTIVE)
            return;

        if (be instanceof SpellConstructBlockEntity sc) {
            syncExecutor(sc.executor, be, -1);
        } else if (be instanceof ModularSpellConstructBlockEntity msc) {
            List<Optional<SpellExecutor>> executors = msc.executors;
            for (int slot = 0; slot < executors.size()
                    && slot < SpellConstructDisplayArguments.MODULAR_EXECUTOR_SLOTS; slot++) {
                Optional<SpellExecutor> optional = executors.get(slot);
                int executorSlot = slot;
                optional.ifPresent(executor -> syncExecutor(executor, be, executorSlot));
            }
        }
    }

    /**
     * Build a merged argument list for the given block entity (single-executor
     * spell construct), combining live executor arguments with any overrides
     * stored via the DisplayLink system.
     */
    public static List<Fragment> buildExecutorArguments(BlockEntity be) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE)
            return List.of();

        return mergeDisplayArguments(be, -1, List.of());
    }

    /**
     * Extract the {@code BlockEntity} from a Trickster {@code BlockSpellSource}.
     */
    @Nullable
    public static BlockEntity getBlockEntityFromSource(Object source) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE || source == null)
            return null;

        if (source instanceof BlockSpellSource<?> bss) {
            return bss.blockEntity;
        }
        return null;
    }

    // ---- internals -------------------------------------------------------

    private static void syncExecutor(@Nullable SpellExecutor executor, BlockEntity be, int executorSlot) {
        if (executor == null || !(executor instanceof DefaultSpellExecutor))
            return;

        ExecutionState state = executor.getDeepestState();
        List<Fragment> current = state.getArguments();
        List<Fragment> merged = mergeDisplayArguments(be, executorSlot, current);

        if (current instanceof ArrayList<?>) {
            @SuppressWarnings("unchecked")
            ArrayList<Fragment> mutable = (ArrayList<Fragment>) current;
            mutable.clear();
            mutable.addAll(merged);
        } else {
            // Fallback: access the private 'arguments' field directly
            try {
                Field argumentsField = ExecutionState.class.getDeclaredField("arguments");
                argumentsField.setAccessible(true);
                argumentsField.set(state, merged);
            } catch (ReflectiveOperationException e) {
                CreateManaIndustry.LOGGER.error("Failed to update spell executor arguments", e);
            }
        }
    }

    private static List<Fragment> mergeDisplayArguments(BlockEntity be, int executorSlot, List<Fragment> base) {
        ArrayList<Fragment> merged = new ArrayList<>(base);
        for (int i = 0; i < SpellConstructDisplayArguments.MAX_ARGUMENTS; i++) {
            if (!SpellConstructDisplayArguments.hasStoredArgument(be, executorSlot, i))
                continue;

            Fragment fragment = getDisplayArgument(be, executorSlot, i);
            if (fragment == null)
                continue;

            while (merged.size() <= i)
                merged.add(VoidFragment.INSTANCE);
            merged.set(i, fragment);
        }
        return merged;
    }

    @Nullable
    private static Fragment getDisplayArgument(BlockEntity be, int executorSlot, int index) {
        if (!CreateManaIndustry.TRICKSTER_ACTIVE)
            return null;

        String value = SpellConstructDisplayArguments.getArgumentString(be, executorSlot, index);
        if (value == null)
            return null;

        return new StringFragment(value);
    }
}
