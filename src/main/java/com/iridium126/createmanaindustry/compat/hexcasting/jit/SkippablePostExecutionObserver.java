package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent;

/**
 * Explicit opt-in for an observer whose invocation may be omitted on a compiled step.
 * Implementations MUST NOT mutate game/casting state, influence later decisions, consume randomness,
 * throw observable exceptions, or require a notification to release resources. Unknown listeners run normally.
 * This is a permission, not a promise: interpreted steps and default configuration still notify the observer.
 */
public interface SkippablePostExecutionObserver extends CastingEnvironmentComponent.PostExecution {}
