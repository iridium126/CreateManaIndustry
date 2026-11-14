package com.iridium126.createmanaindustry.compat.hexcasting.jit;

/**
 * Explicit opt-in for cast-private Hexal Tick counter reuse by custom environments.
 * Every environment callback, including prechecks, range/media checks and op-limit queries,
 * must neither retain nor mutate intermediate CastingImages or their components. Extensions
 * with PostExecution callbacks still disable reuse. CastingImage fields always remain immutable.
 */
public interface TickStateReuseEnvironment {}
