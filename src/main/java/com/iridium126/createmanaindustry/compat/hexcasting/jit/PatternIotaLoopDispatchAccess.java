package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.PatternShapeMatch;

/** Read-only cache view used to specialize a stable Tick head in a repeated evaluation frame. */
public interface PatternIotaLoopDispatchAccess {
    PatternShapeMatch.PerWorld cmi$getCachedLoopTickMatch(long registryGeneration);
    boolean cmi$cachedLoopTickRequiresEnlightenment();
}
