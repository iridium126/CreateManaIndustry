package com.iridium126.createmanaindustry.compat.trickster;

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
    public abstract Fragment activate(SpellContext context, List<Fragment> arguments);
}
