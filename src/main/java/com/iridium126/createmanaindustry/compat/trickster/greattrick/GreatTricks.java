package com.iridium126.createmanaindustry.compat.trickster.greattrick;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.hextrick.execution.ChainExecutorCodecs;
import com.iridium126.createmanaindustry.compat.trickster.anima.AnimaMath;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.fragment.GreatTrickGlyph;
import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionManager;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateManager;
import dev.enjarai.trickster.spell.TrickContext;
import dev.enjarai.trickster.spell.exception.blunder.WrappableBlunder;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.executor.SpellExecutorType;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import java.util.*;
import net.fabricmc.fabric.api.event.registry.FabricRegistryBuilder;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;

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

    public static final SpellExecutorType<DefaultSpellExecutor> CHAIN_EXECUTOR = ChainExecutorCodecs.CHAIN_EXECUTOR;
    public static final SpellExecutorType<DefaultSpellExecutor> FATE_EXECUTOR = ChainExecutorCodecs.FATE_EXECUTOR;
    public static final SpellExecutorType<DefaultSpellExecutor> PARTITION_EXECUTOR = ChainExecutorCodecs.PARTITION_EXECUTOR;
    private GreatTricks() {
    }
    public static void register() {
        CastChains.register();
        SharedFateManager.register();
        PartitionManager.register();
    }
    public static GreatTrick lookup(GreatPattern pattern) {
        for (GreatTrick trick : REGISTRY) if (trick.pattern().equals(pattern)) return trick;
        return null;
    }
    @SuppressWarnings("serial") // Preserve the existing implicit serialization ID.
    public static class AnimaBlunder extends WrappableBlunder {
        private final long cost;
        public AnimaBlunder() {
            this(4000);
        }
        public AnimaBlunder(long cost) {
            this.cost = cost;
        }
        public MutableComponent createMessage(TrickContext ctx) {
            return Component.translatable("createmanaindustry.great_trick.not_enough_anima", AnimaMath.format(cost));
        }
    }
}
