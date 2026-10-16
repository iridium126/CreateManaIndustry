package com.iridium126.createmanaindustry.compat.trickster.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.anima.AnimaMath;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.casting.ExecutorChainAccess;
import com.iridium126.createmanaindustry.compat.trickster.fragment.GreatTrickGlyph;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateManager;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import dev.enjarai.trickster.spell.TrickContext;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.executor.SpellExecutorType;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import dev.enjarai.trickster.spell.exception.blunder.WrappableBlunder;
import net.fabricmc.fabric.api.event.registry.FabricRegistryBuilder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import io.wispforest.endec.Endec;
import io.wispforest.endec.StructEndec;
import io.wispforest.endec.impl.StructEndecBuilder;
import java.util.*;

public final class GreatTricks {
    public static final Registry<GreatTrick> REGISTRY = FabricRegistryBuilder.createSimple(
            ResourceKey.<GreatTrick>createRegistryKey(CreateManaIndustry.modLoc("great_trick"))).buildAndRegister();
    public static final FragmentType<GreatTrickGlyph> GLYPH = Registry.register(FragmentType.REGISTRY,
            CreateManaIndustry.modLoc("great_trick"), new FragmentType<>(GreatTrickGlyph.class, GreatTrickGlyph.ENDEC, OptionalInt.of(0x4da6ff)));
    public static final GreatTrick INVERSION = register("ectoplasmic_inversion", new EctoplasmicInversionTrick());
    public static final GreatTrick SHARED_FATE = register("shared_fate", new SharedFateTrick());
    public static final GreatTrick TINCTURE_SHAPING = register("tincture_shaping", new TinctureShapingTrick());
    public static final GreatTrick PARTITION = register("partition", new PartitionTrick());

    private static <T extends GreatTrick> T register(String path, T trick) {
        return Registry.register(REGISTRY, CreateManaIndustry.modLoc(path), trick);
    }

    // A new executor type adds state without changing the byte layout of existing saved executors.
    private static final StructEndec<DefaultSpellExecutor> CHAIN_ENDEC = StructEndecBuilder.of(
            DefaultSpellExecutor.ENDEC.flatFieldOf(e -> e),
            Endec.STRING.fieldOf("cmi_chain", e -> ((ExecutorChainAccess)(Object)e).cmi$executorChain().encode()),
            (executor, encoded) -> {
                ((ExecutorChainAccess)(Object)executor).cmi$executorChain(CastChain.decode(encoded));
                return executor;
            });
    public static final SpellExecutorType<DefaultSpellExecutor> CHAIN_EXECUTOR = Registry.register(SpellExecutorType.REGISTRY,
            CreateManaIndustry.modLoc("chain_default"), new SpellExecutorType<>(CHAIN_ENDEC, DefaultSpellExecutor.NET_ENDEC));
    // Separate type keeps older compact chain_default byte payloads readable without an appended field.
    private static final StructEndec<DefaultSpellExecutor> FATE_ENDEC = StructEndecBuilder.of(
            CHAIN_ENDEC.flatFieldOf(e -> e),
            Endec.STRING.listOf().optionalFieldOf("cmi_shared_fate_targets",
                    e -> ((ExecutorChainAccess)(Object)e).cmi$executorChain().fateTargets().stream().map(UUID::toString).toList(), List.of()),
            (executor, targets) -> {
                ((ExecutorChainAccess)(Object)executor).cmi$executorChain().restoreFateTargets(targets); return executor;
            });
    public static final SpellExecutorType<DefaultSpellExecutor> FATE_EXECUTOR = Registry.register(SpellExecutorType.REGISTRY,
            CreateManaIndustry.modLoc("shared_fate_default"), new SpellExecutorType<>(FATE_ENDEC, DefaultSpellExecutor.NET_ENDEC));
    private static final StructEndec<DefaultSpellExecutor> PARTITION_ENDEC = StructEndecBuilder.of(
            FATE_ENDEC.flatFieldOf(e -> e),
            Endec.STRING.optionalFieldOf("cmi_partition", e -> { var p=((ExecutorChainAccess)(Object)e).cmi$executorChain().partition(); return p==null?"":p.encode(); }, ""),
            (executor, saved) -> {
                if(!saved.isEmpty()) ((ExecutorChainAccess)(Object)executor).cmi$executorChain().partition(com.iridium126.createmanaindustry.compat.trickster.partition.SavedPartition.decode(saved));
                return executor;
            });
    public static final SpellExecutorType<DefaultSpellExecutor> PARTITION_EXECUTOR = Registry.register(SpellExecutorType.REGISTRY,
            CreateManaIndustry.modLoc("partition_default"), new SpellExecutorType<>(PARTITION_ENDEC, DefaultSpellExecutor.NET_ENDEC));
    private GreatTricks() {}
    public static void register() { CastChains.register(); SharedFateManager.register(); com.iridium126.createmanaindustry.compat.trickster.partition.PartitionManager.register(); }
    public static GreatTrick lookup(GreatPattern pattern) {
        for (GreatTrick trick : REGISTRY) if (trick.pattern().equals(pattern)) return trick;
        return null;
    }
    @SuppressWarnings("serial") // Preserve the existing implicit serialization ID.
    public static class AnimaBlunder extends WrappableBlunder {
        private final long cost;
        public AnimaBlunder() { this(4000); }
        public AnimaBlunder(long cost) { this.cost = cost; }
        public MutableComponent createMessage(TrickContext ctx) { return Component.translatable("createmanaindustry.great_trick.not_enough_anima", AnimaMath.format(cost)); }
    }
}
