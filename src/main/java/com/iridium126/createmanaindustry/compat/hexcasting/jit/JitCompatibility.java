package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;

/** Bootstrap-safe: must not reference Hexcasting classes or initialise registries. */
public final class JitCompatibility {
    private static final Set<String> VERIFIED = ConcurrentHashMap.newKeySet();
    private static volatile String failure;
    private static volatile boolean verifierInstalled;
    private JitCompatibility() {}
    public static void verifierInstalled() { verifierInstalled = true; }
    public static void verified(String name) { VERIFIED.add(name); }
    public static void disable(String reason) {
        if (failure == null) LoggerFactory.getLogger("CMI Hex JIT").warn("Hex JIT disabled: {}", reason);
        failure = reason;
    }
    public static boolean ready() { return failure == null && verifierInstalled && VERIFIED.size() == 7; }
    public static String status() {
        return failure != null ? failure : ready() ? "verified pre-53" : "waiting for target verification (" + VERIFIED.size() + "/7)";
    }
}
