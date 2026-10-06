package com.iridium126.createmanaindustry.compat.hexcasting.jit;

/**
 * Explicit opt-in for cast-private Tick user-data reuse by custom environments.
 * Every environment callback, including prechecks, range/media checks and op-limit queries,
 * must neither retain nor mutate intermediate CastingImages or their user data. Extensions
 * with PostExecution callbacks still disable reuse. CastingImage fields always remain immutable.
 */
public interface TickStateReuseEnvironment {}
