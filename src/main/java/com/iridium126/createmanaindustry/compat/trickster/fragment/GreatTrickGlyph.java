package com.iridium126.createmanaindustry.compat.trickster.fragment;

import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatPattern;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatTrick;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatTricks;

import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import dev.enjarai.trickster.spell.fragment.VoidFragment;
import dev.enjarai.trickster.spell.exception.blunder.NoPlayerBlunder;
import dev.enjarai.trickster.spell.exception.blunder.WrappableBlunder;
import io.wispforest.endec.Endec;
import io.wispforest.endec.StructEndec;
import io.wispforest.endec.impl.StructEndecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import java.util.List;

public record GreatTrickGlyph(GreatPattern pattern) implements Fragment {
    public static final StructEndec<GreatTrickGlyph> ENDEC = StructEndecBuilder.of(
            Endec.BYTES.xmap(GreatPattern::fromBytes, GreatPattern::bytes).fieldOf("edges", GreatTrickGlyph::pattern), GreatTrickGlyph::new);
    public FragmentType<?> type() { return GreatTricks.GLYPH; }
    public Component asText() {
        GreatTrick trick = GreatTricks.lookup(pattern);
        return trick == null ? Component.translatable("createmanaindustry.great_trick.unknown") :
                Component.translatable("createmanaindustry.great_trick." + GreatTricks.REGISTRY.getKey(trick).getPath());
    }
    public int getWeight() { return 8 + pattern.edges().size() * 2; }
    public Fragment activateAsGlyph(SpellContext ctx, List<Fragment> args) {
        ctx.source().getPlayer().orElseThrow(NoPlayerBlunder::new);
        if (pattern.edges().isEmpty()) return VoidFragment.INSTANCE;
        GreatTrick trick = GreatTricks.lookup(pattern);
        if (trick == null) throw new UnknownGreatTrick();
        return trick.activate(ctx, args);
    }
    @SuppressWarnings("serial") // Preserve the existing implicit serialization ID.
    private static final class UnknownGreatTrick extends WrappableBlunder {
        public MutableComponent createMessage(TrickContext ctx) { return Component.translatable("createmanaindustry.great_trick.unknown"); }
    }
}
