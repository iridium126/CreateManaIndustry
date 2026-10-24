package com.iridium126.createmanaindustry.compat.hextrick.execution;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.ExecutorChainAccess;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.executor.SpellExecutorType;
import io.wispforest.endec.Endec;
import io.wispforest.endec.StructEndec;
import io.wispforest.endec.impl.StructEndecBuilder;
import net.minecraft.core.Registry;

/** Native executor state plus independently owned CMI effect metadata. */
public final class ChainExecutorCodecs {
    // Development format only: all CMI executor identities share the same complete snapshot schema.
    private static final StructEndec<DefaultSpellExecutor> CHAIN_ENDEC = StructEndecBuilder.of(
            DefaultSpellExecutor.ENDEC.flatFieldOf(e -> e),
            CastChainSnapshotEndec.ENDEC.fieldOf("cmi_chain", e -> ((ExecutorChainAccess)(Object)e).cmi$executorChain().snapshot()),
            Endec.STRING.fieldOf("cmi_budget", e -> ((ExecutorBudgetAccess)(Object)e).cmi$encodeBudget()),
            (executor, snapshot, budget) -> {
                ((ExecutorChainAccess)(Object)executor).cmi$executorChain(CastChain.restore(snapshot));
                ((ExecutorBudgetAccess)(Object)executor).cmi$restoreBudget(budget);
                return executor;
            });
    private static SpellExecutorType<DefaultSpellExecutor> register(String id) {
        return Registry.register(SpellExecutorType.REGISTRY, CreateManaIndustry.modLoc(id),
                new SpellExecutorType<>(CHAIN_ENDEC, DefaultSpellExecutor.NET_ENDEC));
    }
    public static final SpellExecutorType<DefaultSpellExecutor> CHAIN_EXECUTOR = register("chain_default");
    public static final SpellExecutorType<DefaultSpellExecutor> FATE_EXECUTOR = register("shared_fate_default");
    public static final SpellExecutorType<DefaultSpellExecutor> PARTITION_EXECUTOR = register("partition_default");
    private ChainExecutorCodecs() {}
}
