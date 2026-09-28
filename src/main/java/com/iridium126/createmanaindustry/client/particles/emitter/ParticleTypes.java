package com.iridium126.createmanaindustry.client.particles.emitter;

import java.util.*;

/** Internal, startup-only type catalog. Material is a draw contract, not a behavior id. */
public final class ParticleTypes {
    public enum Feature { SPRITE_ATLAS, MODEL_ATLAS, COLLISION, STORM, HEX }
    public record Type(int id, String name, EmitterSpec.Material material,
                       String spawnModule, String updateModule, Set<Feature> features) {
        public Type {
            if (id < 0 || id > 65535 || !name.matches("[a-z][a-z0-9_]*"))
                throw new IllegalArgumentException("Invalid particle type id/name");
            Objects.requireNonNull(material);
            features = Set.copyOf(features);
            for (String module : new String[] {spawnModule, updateModule})
                if (module != null && !module.matches("chunks/[a-z0-9_/]+\\.glsl"))
                    throw new IllegalArgumentException("Invalid shader module: " + module);
        }
    }
    private static final Map<Integer, Type> TYPES = new LinkedHashMap<>();
    private static boolean frozen;
    static {
        register(new Type(1, "additive", EmitterSpec.Material.ADDITIVE, null, null, Set.of()));
        register(new Type(2, "alpha", EmitterSpec.Material.ALPHA, null, null, Set.of(Feature.SPRITE_ATLAS)));
        register(new Type(3, "model", EmitterSpec.Material.MODEL, null, null, Set.of(Feature.MODEL_ATLAS)));
        register(new Type(4, "opaque", EmitterSpec.Material.OPAQUE, null, null, Set.of(Feature.SPRITE_ATLAS)));
        register(new Type(5, "hex_pattern", EmitterSpec.Material.HEX_PATTERN, null, null, Set.of(Feature.HEX)));
    }
    private ParticleTypes() {}
    public static synchronized Type register(Type type) {
        if (frozen) throw new IllegalStateException("Particle types are frozen after shader compilation");
        if (TYPES.containsKey(type.id()) || TYPES.values().stream().anyMatch(t -> t.name().equals(type.name())))
            throw new IllegalArgumentException("Duplicate particle type: " + type.name());
        TYPES.put(type.id(), type);
        return type;
    }
    public static synchronized Type standard(EmitterSpec.Material material) { return TYPES.get(material.index() + 1); }
    public static synchronized void requireRegistered(Type type) {
        if (!type.equals(TYPES.get(type.id()))) throw new IllegalArgumentException("Unregistered particle type");
    }
    public static synchronized String shaderHooks(String phase) {
        if (!phase.equals("spawn") && !phase.equals("update")) throw new IllegalArgumentException(phase);
        frozen = true;
        StringBuilder out = new StringBuilder();
        Set<String> included = new LinkedHashSet<>();
        for (Type type : TYPES.values()) {
            String module = phase.equals("spawn") ? type.spawnModule() : type.updateModule();
            if (module != null && included.add(module)) out.append("#pragma cmi_include ").append(module).append('\n');
        }
        out.append("void cmiType_").append(phase).append("(uint type, uint header, inout vec4 p0, inout vec4 p1, inout vec4 p2, inout vec4 p3) {\n");
        for (Type type : TYPES.values()) {
            String module = phase.equals("spawn") ? type.spawnModule() : type.updateModule();
            if (module != null) out.append("if (type == ").append(type.id()).append("u) cmi_")
                    .append(type.name()).append('_').append(phase).append("(header,p0,p1,p2,p3);\n");
        }
        return out.append("}\n").toString();
    }
}
