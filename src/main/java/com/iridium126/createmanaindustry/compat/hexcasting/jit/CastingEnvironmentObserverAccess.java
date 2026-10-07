package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.PatternShapeMatch;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent;
import java.util.List;

/** Runtime access to Hexcasting's per-environment observer list for guarded immutable-state shortcuts. */
public interface CastingEnvironmentObserverAccess {
    List<CastingEnvironmentComponent.PostExecution> cmi$getPostExecutions();

    List<?> cmi$getPreMediaExtract();

    List<?> cmi$getPostMediaExtract();

    List<?> cmi$getIsVecInRanges();

    double cmi$getCostModifier();

    boolean cmi$applyCachedLoopTickPrecheck(PatternShapeMatch.PerWorld match, ExecutionScope scope);
}
