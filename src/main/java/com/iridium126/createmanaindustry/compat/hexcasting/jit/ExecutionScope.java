package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import com.iridium126.createmanaindustry.config.ServerConfig;

/** Optional observer bookkeeping. Scopes hold booleans only and are removed in finally blocks. */
public final class ExecutionScope implements AutoCloseable {
    private static final ThreadLocal<ExecutionScope> CURRENT = new ThreadLocal<>();
    private final ExecutionScope previous;
    private boolean compiled;
    private boolean notifying;
    private ExecutionScope() { previous = CURRENT.get(); CURRENT.set(this); }
    public static ExecutionScope enter() { return new ExecutionScope(); }
    public void startStep() { compiled = false; notifying = false; }
    public void notifying(boolean value) { notifying = value; }
    public static void markCompiled() {
        if (!ServerConfig.hexJitSkipObservers) return;
        ExecutionScope scope = CURRENT.get();
        if (scope != null) scope.compiled = true;
    }
    public static ExecutionScope current() { return CURRENT.get(); }
    public static boolean maySkip() {
        ExecutionScope scope = CURRENT.get();
        return scope != null && scope.compiled && scope.notifying;
    }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
