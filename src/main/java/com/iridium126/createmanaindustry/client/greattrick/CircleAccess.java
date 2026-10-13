package com.iridium126.createmanaindustry.client.greattrick;

import dev.enjarai.trickster.render.CircleRenderer;
import dev.enjarai.trickster.spell.SpellView;

public interface CircleAccess {
    CircleRenderer cmi$renderer();
    SpellView cmi$view();
    boolean cmi$mutable();
    boolean cmi$hidden();
    boolean cmi$inner();
    double cmi$angle();
    double cmi$radius();
    boolean cmi$highlight();
    Runnable cmi$eval();
}
