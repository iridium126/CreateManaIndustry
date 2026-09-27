package com.iridium126.createmanaindustry.infrastructure.network;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A typed bridge from common payload handlers to client implementations.
 * The client entry point installs the callback once during startup; common
 * payload classes therefore never link against client-only classes.
 */
public final class ClientPayloadHandler<T> {
    private volatile Consumer<? super T> handler;

    /** Installs the client-side receiver during client initialization. */
    public void install(Consumer<? super T> handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /** Dispatches a payload when a client receiver has been installed. */
    public void dispatch(T payload) {
        Consumer<? super T> current = handler;
        if (current != null) {
            current.accept(payload);
        }
    }
}
