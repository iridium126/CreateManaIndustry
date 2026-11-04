package com.iridium126.createmanaindustry.client.particles.emitter;

import java.util.*;

/** Internal, startup-only type catalog. Material is a draw contract, not a behavior id. */
public final class ParticleTypes {
    /** GPU draw material and its stable header/shader value. */
    public enum Material {
        ADDITIVE(0), ALPHA(1), MODEL(2), OPAQUE(3), PACKAGE(5);

        private final int index;
        Material(int index) { this.index = index; }
        public int index() { return index; }
        public static Material byIndex(int index) {
            for (Material material : values()) if (material.index == index) return material;
            return ADDITIVE;
        }
    }

    public enum Feature { SPRITE_ATLAS, MODEL_ATLAS, COLLISION, STORM, PACKAGE }
    /** Only continuous fields belong here. Identity, frames and lifecycle stay discrete. */
    public enum InterpolationField {
        POSITION(1),VELOCITY(2),ROTATION(4),COLOR(8),CLOCK(16),BRIGHTNESS(32),SIZE(64),OPACITY(128);
        final int bit;
        InterpolationField(int bit){this.bit=bit;}
    }
    public enum Coordinates { WORLD, CAMERA_RELATIVE }
    public record Type(int id, String name, Material material,
                       String spawnModule, String updateModule, Set<Feature> features,
                       Set<InterpolationField> interpolation, Coordinates coordinates) {
        public Type(int id,String name,Material material,String spawnModule,String updateModule,Set<Feature> features,Set<InterpolationField> interpolation){
            this(id,name,material,spawnModule,updateModule,features,interpolation,Coordinates.WORLD);
        }
        public Type(int id,String name,Material material,String spawnModule,String updateModule,Set<Feature> features){
            this(id,name,material,spawnModule,updateModule,features,defaultInterpolation(id,material),Coordinates.WORLD);
        }
        public Type {
            if (id < 0 || id > 65535 || !name.matches("[a-z][a-z0-9_]*"))
                throw new IllegalArgumentException("Invalid particle type id/name");
            Objects.requireNonNull(material);
            Objects.requireNonNull(coordinates);
            features = Set.copyOf(features);
            interpolation = Set.copyOf(interpolation);
            for (String module : new String[] {spawnModule, updateModule})
                if (module != null && !module.matches("chunks/[a-z0-9_/]+\\.glsl"))
                    throw new IllegalArgumentException("Invalid shader module: " + module);
        }
    }
    private static Set<InterpolationField> defaultInterpolation(int id,Material material){
        if(material==Material.PACKAGE)return Set.of(); // The package producer publishes its own two-pose layout.
        var fields=EnumSet.of(InterpolationField.POSITION,InterpolationField.VELOCITY,InterpolationField.ROTATION,InterpolationField.COLOR,InterpolationField.CLOCK);
        if(material==Material.MODEL)fields.remove(InterpolationField.ROTATION);
        if(id==AllayFeatherLayout.TYPE||id==AllayGoldSpecs.TYPE)fields.remove(InterpolationField.COLOR);
        if(id==AllayGoldSpecs.TYPE)fields.add(InterpolationField.BRIGHTNESS);
        return fields;
    }
    public static synchronized String interpolationShader(){
        var code=new StringBuilder("uint cmiDisplayFields(uint type){\n");
        for(var type:TYPES.values()){
            int mask=0;for(var field:type.interpolation())mask|=field.bit;
            code.append("if(type==").append(type.id()).append("u)return ").append(mask).append("u;\n");
        }
        code.append("return 0u;}\nbool cmiDisplayCameraRelative(uint type){return ");
        for(var type:TYPES.values())if(type.coordinates()==Coordinates.CAMERA_RELATIVE)code.append("type==").append(type.id()).append("u||");
        return code.append("false;}\n").toString();
    }
    private static final Map<Integer, Type> TYPES = new LinkedHashMap<>();
    private static final Map<Material, Type> STANDARD_TYPES = new EnumMap<>(Material.class);
    private static boolean frozen;
    static {
        registerStandard(new Type(1, "additive", Material.ADDITIVE, null, null, Set.of()));
        registerStandard(new Type(2, "alpha", Material.ALPHA, null, null, Set.of(Feature.SPRITE_ATLAS)));
        registerStandard(new Type(3, "model", Material.MODEL, null, null, Set.of(Feature.MODEL_ATLAS)));
        registerStandard(new Type(4, "opaque", Material.OPAQUE, null, null, Set.of(Feature.SPRITE_ATLAS)));
        registerStandard(new Type(6, "package", Material.PACKAGE, null, null, Set.of(Feature.PACKAGE,Feature.COLLISION)));
        register(new Type(AllayGoldSpecs.TYPE,"allay_gold",Material.ADDITIVE,"chunks/gold_spawn.glsl",null,Set.of()));
        register(new Type(AllayFeatherLayout.TYPE, "allay_feather", Material.OPAQUE,
                "chunks/feather_spawn.glsl", null, Set.of(Feature.MODEL_ATLAS,Feature.COLLISION)));
        register(new Type(10,"external_conjure",Material.ADDITIVE,null,null,Set.of(),
                Set.of(InterpolationField.POSITION,InterpolationField.ROTATION,InterpolationField.COLOR,InterpolationField.SIZE,InterpolationField.OPACITY),Coordinates.CAMERA_RELATIVE));
        register(new Type(11,"fate_ribbon_glow",Material.ADDITIVE,null,null,Set.of(),
                Set.of(InterpolationField.POSITION,InterpolationField.SIZE),Coordinates.CAMERA_RELATIVE));
        register(new Type(12,"fate_ribbon_alpha",Material.ALPHA,null,null,Set.of(),
                Set.of(InterpolationField.POSITION,InterpolationField.SIZE),Coordinates.CAMERA_RELATIVE));
    }
    private ParticleTypes() {}
    private static void registerStandard(Type type) {
        register(type);
        if (STANDARD_TYPES.putIfAbsent(type.material(), type) != null)
            throw new IllegalStateException("Duplicate standard type for material " + type.material());
    }
    public static synchronized Type register(Type type) {
        Objects.requireNonNull(type, "type");
        if (frozen) throw new IllegalStateException("Particle types are frozen after shader compilation");
        if (TYPES.containsKey(type.id()) || TYPES.values().stream().anyMatch(t -> t.name().equals(type.name())))
            throw new IllegalArgumentException("Duplicate particle type: " + type.name());
        TYPES.put(type.id(), type);
        return type;
    }
    public static synchronized Type standard(Material material) {
        Type type = STANDARD_TYPES.get(Objects.requireNonNull(material, "material"));
        if (type == null) throw new IllegalArgumentException("No standard particle type for " + material);
        return type;
    }
    public static Type allayGold(){return TYPES.get(AllayGoldSpecs.TYPE);}
    public static Type allayFeather() { return TYPES.get(AllayFeatherLayout.TYPE); }
    public static Type externalConjure(){return TYPES.get(10);}
    public static Type fateRibbon(boolean alpha){return TYPES.get(alpha?12:11);}
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
