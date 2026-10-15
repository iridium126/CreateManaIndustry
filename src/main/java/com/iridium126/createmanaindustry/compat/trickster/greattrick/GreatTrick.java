package com.iridium126.createmanaindustry.compat.trickster.greattrick;

import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellContext;
import java.util.List;

/** Separate from Trick: its pattern is not a nine-dot Pattern/32-bit mask. */
public abstract class GreatTrick {
    private final GreatPattern pattern;
    private final long animaCost;
    protected GreatTrick(GreatPattern pattern, long animaCost) { this.pattern=pattern; this.animaCost=animaCost; }
    public GreatPattern pattern() { return pattern; }
    public long animaCost() { return animaCost; }
    public java.util.OptionalLong estimateAnimaCost(List<Fragment> arguments) { return java.util.OptionalLong.of(animaCost); }
    public net.minecraft.network.chat.Component costDescription() { return net.minecraft.network.chat.Component.translatable("createmanaindustry.great_trick.book_cost", com.iridium126.createmanaindustry.compat.trickster.anima.AnimaMath.format(animaCost)); }
    public List<dev.enjarai.trickster.spell.fragment.FragmentType<?>> inputTypes() { return List.of(); }
    public dev.enjarai.trickster.spell.fragment.FragmentType<?> outputType() { return dev.enjarai.trickster.spell.fragment.FragmentType.VOID; }
    public abstract Fragment activate(SpellContext context, List<Fragment> arguments);
}
