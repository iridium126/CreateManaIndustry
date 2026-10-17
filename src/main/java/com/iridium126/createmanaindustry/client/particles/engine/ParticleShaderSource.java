package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.function.Function;

/** Resource-manager paths and includes shared by runtime compilation and driver validation. */
public final class ParticleShaderSource {
    public static final String ROOT = "shaders/particles/";
    private ParticleShaderSource() {}

    /** Names are relative to the particle shader directory, e.g. packages/pool_select.comp. */
    public static String loadParticle(String name, Function<String, String> readResource) {
        checkRelative(name);
        if (name.startsWith(ROOT)) throw new IllegalArgumentException("Expected a particle-relative shader name: " + name);
        return loadResource(ROOT + name, readResource);
    }

    /** Reader receives a namespace-relative resource path, never an assets/ filesystem path. */
    public static String loadResource(String path, Function<String, String> readResource) {
        checkRelative(path);
        return com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderSources.load(path,ROOT,readResource);
    }

    private static void checkRelative(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("assets/")
                || path.contains("\\") || path.contains(":") || path.contains(".."))
            throw new IllegalArgumentException("Expected a namespace-relative shader resource path: " + path);
    }
}
