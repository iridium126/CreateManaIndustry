package com.iridium126.createmanaindustry.compat.hextrick.execution;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.ExecutorChainAccess;
import com.iridium126.createmanaindustry.compat.trickster.partition.SavedPartition;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.executor.SpellExecutorType;
import io.wispforest.endec.Endec;
import io.wispforest.endec.StructEndec;
import io.wispforest.endec.impl.StructEndecBuilder;
import java.util.UUID;
import net.minecraft.core.Registry;

/** Native executor state plus independently owned CMI effect metadata. */
public final class ChainExecutorCodecs {
    // Current unreleased CMI format; native DefaultSpellExecutor state stays upstream-owned.
    private static final StructEndec<DefaultSpellExecutor> CHAIN_ENDEC = StructEndecBuilder.of(
            DefaultSpellExecutor.ENDEC.flatFieldOf(e -> e),
            Endec.STRING.fieldOf("cmi_chain", e -> ((ExecutorChainAccess)(Object)e).cmi$executorChain().encode()),
            Endec.STRING.fieldOf("cmi_budget", e -> ((ExecutorBudgetAccess)(Object)e).cmi$encodeBudget()),
            (executor, encoded, budget) -> {
                ((ExecutorChainAccess)(Object)executor).cmi$executorChain(CastChain.decode(encoded));
                ((ExecutorBudgetAccess)(Object)executor).cmi$restoreBudget(budget);
                return executor;
            });
    public static final SpellExecutorType<DefaultSpellExecutor> CHAIN_EXECUTOR = Registry.register(SpellExecutorType.REGISTRY,
            CreateManaIndustry.modLoc("chain_default"), new SpellExecutorType<>(CHAIN_ENDEC, DefaultSpellExecutor.NET_ENDEC));
    // Registry identities remain stable; unreleased CMI state uses the current format only.
    private static final StructEndec<DefaultSpellExecutor> TARGETS_ENDEC = StructEndecBuilder.of(
            CHAIN_ENDEC.flatFieldOf(e -> e),
            Endec.STRING.listOf().fieldOf("cmi_shared_fate_targets",
                    e -> ((ExecutorChainAccess)(Object)e).cmi$executorChain().fateTargets().stream().map(UUID::toString).toList()),
            (executor, targets) -> {
                ((ExecutorChainAccess)(Object)executor).cmi$executorChain().restoreFateTargets(targets);
                return executor;
            });
    private static final StructEndec<DefaultSpellExecutor> FATE_ENDEC = StructEndecBuilder.of(
            TARGETS_ENDEC.flatFieldOf(e -> e),
            Endec.STRING.fieldOf("cmi_origins", e -> ((ExecutorChainAccess)(Object)e).cmi$executorChain().encodeOrigins()),
            (executor, origins) -> {
                ((ExecutorChainAccess)(Object)executor).cmi$executorChain().restoreOrigins(origins);
                return executor;
            });
    public static final SpellExecutorType<DefaultSpellExecutor> FATE_EXECUTOR = Registry.register(SpellExecutorType.REGISTRY,
            CreateManaIndustry.modLoc("shared_fate_default"), new SpellExecutorType<>(FATE_ENDEC, DefaultSpellExecutor.NET_ENDEC));
    private static final StructEndec<DefaultSpellExecutor> PARTITION_ENDEC = StructEndecBuilder.of(
            FATE_ENDEC.flatFieldOf(e -> e),
            Endec.STRING.optionalFieldOf("cmi_partition", executor -> {
                var partition = ((ExecutorChainAccess)(Object)executor).cmi$executorChain().partition();
                return partition == null ? "" : partition.encode();
            }, ""),
            (executor, saved) -> {
                if (!saved.isEmpty()) ((ExecutorChainAccess)(Object)executor).cmi$executorChain().partition(SavedPartition.decode(saved));
                return executor;
            });
    public static final SpellExecutorType<DefaultSpellExecutor> PARTITION_EXECUTOR = Registry.register(SpellExecutorType.REGISTRY,
            CreateManaIndustry.modLoc("partition_default"), new SpellExecutorType<>(PARTITION_ENDEC, DefaultSpellExecutor.NET_ENDEC));
    private ChainExecutorCodecs() {}
}
