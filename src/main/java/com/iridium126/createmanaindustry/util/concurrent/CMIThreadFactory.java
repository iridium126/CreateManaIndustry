package com.iridium126.createmanaindustry.util.concurrent;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates daemon worker threads with a consistent mod and subsystem prefix. */
public final class CMIThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger sequence = new AtomicInteger();

    private CMIThreadFactory(String role) {
        Objects.requireNonNull(role, "role");
        if (!role.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Thread role must be lowercase kebab-case: " + role);
        }
        this.prefix = "CreateManaIndustry-" + role;
    }

    public static CMIThreadFactory daemonFactory(String role) {
        return new CMIThreadFactory(role);
    }

    @Override
    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}
