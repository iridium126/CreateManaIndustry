package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resource-manager paths and includes shared by runtime compilation and driver validation. */
public final class ParticleShaderSource {
    public static final String ROOT = "shaders/particles/";
    private static final Pattern INCLUDE = Pattern.compile(
            "^\\s*#pragma\\s+cmi_include\\s+(\\S+)\\s*$", Pattern.MULTILINE);

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
        return resolve(path, readResource, 0);
    }

    private static String resolve(String path, Function<String, String> reader, int depth) {
        String raw = reader.apply(path);
        if (raw == null) return null;
        Matcher matcher = INCLUDE.matcher(raw);
        if (!matcher.find()) return raw;
        if (depth >= 12) throw new IllegalArgumentException("Particle shader include depth exceeded: " + path);
        matcher.reset();
        StringBuffer out = new StringBuffer(raw.length());
        while (matcher.find()) {
            String name = matcher.group(1);
            checkRelative(name);
            String included = resolve(ROOT + name, reader, depth + 1);
            if (included == null) return null;
            matcher.appendReplacement(out, Matcher.quoteReplacement(included));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static void checkRelative(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("assets/")
                || path.contains("\\") || path.contains(":") || path.contains(".."))
            throw new IllegalArgumentException("Expected a namespace-relative shader resource path: " + path);
    }
}
