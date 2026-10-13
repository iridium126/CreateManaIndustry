package com.iridium126.createmanaindustry.compat.trickster;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.executor.SpellExecutorType;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import dev.enjarai.trickster.spell.fragment.VoidFragment;
import dev.enjarai.trickster.spell.exception.blunder.*;
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
    public static final GreatTrick INVERSION = Registry.register(REGISTRY, CreateManaIndustry.modLoc("ectoplasmic_inversion"),
            new GreatTrick(GreatPattern.INVERSION, 4000) {
                public Fragment activate(SpellContext ctx, List<Fragment> args) {
                    var player = ctx.source().getPlayer().orElseThrow(NoPlayerBlunder::new);
                    if (!args.isEmpty()) throw new InvalidInputsBlunder();
                    if (!BatteryAnima.consume(player, animaCost())) throw new AnimaBlunder();
                    ChainState state = (ChainState)ctx.state();
                    CastChain chain = CastChains.current() != null ? CastChains.current() : state.cmi$getChain();
                    state.cmi$setChain(chain);
                    state.cmi$markResourceUse();
                    com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope.resourceTransition();
                    chain.toggle();
                    com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime.invalidate("ectoplasmic inversion toggled");
                    return VoidFragment.INSTANCE;
                }
            });

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
    private GreatTricks() {}
    public static void register() { CastChains.register(); }
    public static GreatTrick lookup(GreatPattern pattern) {
        for (GreatTrick trick : REGISTRY) if (trick.pattern().equals(pattern)) return trick;
        return null;
    }
    public static class AnimaBlunder extends WrappableBlunder {
        public MutableComponent createMessage(TrickContext ctx) { return Component.translatable("createmanaindustry.great_trick.not_enough_anima"); }
    }
}
