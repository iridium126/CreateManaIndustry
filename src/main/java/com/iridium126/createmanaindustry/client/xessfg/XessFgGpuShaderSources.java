package com.iridium126.createmanaindustry.client.xessfg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Appends a motion side effect to the stock shader after its clipping and material logic. */
public final class XessFgGpuShaderSources {
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
    private static final String PARTICLE_RECORD = "vec4 p3 = particles.data[base + 3u];";
    private static final String EXTENSIONS = "#extension GL_ARB_shader_storage_buffer_object : require\n"
            + "#extension GL_ARB_shading_language_420pack : require\n";
    private static final String ATOMICS = "#extension GL_ARB_gpu_shader_int64 : require\n"
            + "#extension GL_NV_shader_atomic_int64 : require\n"
            + "#extension GL_ARB_shading_language_packing : require\n";
    private static final Pattern FLAT_NORMALS = Pattern.compile("\\bvec3\\s+FlatNormals\\b");
    private static final Pattern GEOMETRY_POSITION =
            Pattern.compile("\\bgl_Position\\s*=\\s*gl_in\\s*\\[\\s*(\\w+)\\s*\\]\\.gl_Position\\s*;");
    private static final String PARTICLE_TOKENS = """
            layout(std430, binding = 35) readonly buffer CmiFgParticleTokens { uint cmiFgParticleToken[]; };
            uniform uint cmiFgIdentityBase;
            """;

    private XessFgGpuShaderSources() {}

    public record Pair(String vertex, String fragment) {}

    public static Pair mergedParticles(String vertex, String fragment) {
        if (!vertex.contains("cmi_FgSlot")) return null;
        String decoratedVertex = vertex(vertex, PARTICLE_TOKENS + "flat out int cmiFgGhost;\nflat out int cmiFgIsLod;\n", """
                cmiFgGhost = int(cmi_FgGhost);
                cmiFgIsLod = cmi_LodPass;
                if (cmiFgFrame.x != 0u && cmi_LodPass != 0 && abs(gl_Position.z) <= gl_Position.w) {
                    cmiFgRecord(uvec3(cmi_FgSlot, uint(gl_VertexID), cmi_FgMember ^ (0x91a29b00u + uint(cmi_LodPass))),
                                gl_Position, true);
                } else if (cmiFgFrame.x != 0u && cmi_FgSlot != 0xffffffffu && abs(gl_Position.z) <= gl_Position.w) {
                    uint id = cmi_FgMember != 0u ? cmi_FgMember : cmiFgParticleToken[cmiFgIdentityBase + cmi_FgSlot];
                    if (id != 0u) {
                        uint domain = 16u | uint(cmi_FeatherPass != 0) | (uint(cmi_FgMember != 0u) << 1);
                        uint localVertex = uint(gl_VertexID) | (uint(cmi_FeatherPass == 2) << 31);
                        cmiFgRecord(uvec3(id, localVertex, (cmi_FgEmitter << 5) | domain), gl_Position, true);
                    }
                }
                """);
        String decoratedFragment = wrap(fragment, EXTENSIONS + ATOMICS, Grid.GRID + Grid.FRAGMENT
                + "flat in int cmiFgGhost;\nflat in int cmiFgIsLod;\n", """
                cmiFgStockMain();
                if (cmiFgIsLod != 0) cmiFgPublishLod(1.0);
                else cmiFgPublish(1.0, cmiFgGhost != 0, false);
                """);
        return decoratedVertex == null || decoratedFragment == null ? null : new Pair(decoratedVertex, decoratedFragment);
    }

    public static Pair particle(String name, String vertex, String fragment) {
        if (name.equals("storm_lod.vsh")) {
            String lod = vertex(vertex, "", """
                    if (cmiFgFrame.x != 0u && abs(gl_Position.z) <= gl_Position.w) {
                        uint id = order[(cmi_LodPass == 1 ? 0u : 131072u) + uint(gl_InstanceID)].x;
                        uint stormSeed = floatBitsToUint(records[id * 2u + 1u].w);
                        cmiFgRecord(uvec3(id, uint(gl_VertexID), stormSeed ^ (0x91a29b00u + uint(cmi_LodPass))),
                                    gl_Position, true);
                    }
                    """);
            String surface = fragment(fragment, "cmiFgPublishLod(fragColor.a);");
            return lod == null || surface == null ? null : new Pair(lod, surface);
        }
        if (name.equals("feather.vsh")) {
            String feather = vertex(vertex, PARTICLE_TOKENS, """
                    if (cmiFgFrame.x != 0u && abs(gl_Position.z) <= gl_Position.w) {
                        uint slot = uint(feather.words[CMI_FEATHER_VISIBLE + uint(gl_InstanceID)]);
                        uint id = cmiFgParticleToken[cmiFgIdentityBase + slot];
                        if (id != 0u) cmiFgRecord(uvec3(id, uint(gl_VertexID), 4u), gl_Position, true);
                    }
                    """);
            String surface = fragment(fragment, "cmiFgPublish(fragColor.a, false, false);");
            return feather == null || surface == null ? null : new Pair(feather, surface);
        }
        boolean spark = name.equals("additive.vsh");
        boolean sprite = name.equals("textured.vsh");
        boolean model = name.equals("model.vsh");
        if (!spark && !sprite && !model || !vertex.contains(PARTICLE_RECORD)) return null;
        String domain = spark ? "1u" : sprite ? "2u" : "3u";
        String identity = """
                uint emitter = floatBitsToUint(p3.w);
                bool storm = emitters.u[emitter * VEC4_PER_EMITTER + 18u].x > 0.5;
                uint stableId = storm ? uint(p0.w + 0.5) & 0x3ffffu : cmiFgParticleToken[cmiFgIdentityBase + inst];
                uint seed = storm ? floatBitsToUint(p3.z) & 0xfffffff0u : 0u;
                if (stableId != 0u)
                    cmiFgKey = uvec3(stableId, uint(gl_VertexID), ((emitter << 4u) ^ seed) | %s | (storm ? 8u : 0u));
                """.formatted(domain);
        // Guard the storage binding and read when the application is not in a capture frame.
        vertex = vertex.replace(PARTICLE_RECORD, PARTICLE_RECORD + "\n    if (cmiFgFrame.x != 0u) {\n" + identity + "}\n");
        String decoratedVertex = vertex(vertex, PARTICLE_TOKENS + "uvec3 cmiFgKey = uvec3(0u);\n", """
                if (cmiFgKey.x != 0u && abs(gl_Position.z) <= gl_Position.w)
                    cmiFgRecord(cmiFgKey, gl_Position, true);
                """);
        String coverage = spark ? "max(max(fragColor.r, fragColor.g), fragColor.b)" : "fragColor.a";
        String translucent = spark ? "true" : sprite ? "uMode == 0" : "vSeg > 0.5";
        String decoratedFragment = fragment(fragment, "cmiFgPublish(" + coverage + ", " + translucent + ", false);");
        return decoratedVertex == null || decoratedFragment == null ? null : new Pair(decoratedVertex, decoratedFragment);
    }

    /** Position before deformation comes from Iris's transformed Sodium vertex input. */
    public static Pair terrain(XessFgPack pack, String vertex, String fragment, boolean translucent, boolean throughGeometry) {
        if (!vertex.contains("getVertexPosition") || !vertex.contains("iris_ProjectionMatrix")
                || !vertex.contains("iris_ModelViewMatrix")) return null;
        // Iris may cull this varying while a Bliss material branch still references it.
        String localNormal = "";
        if (pack == XessFgPack.BLISS && vertex.contains("FlatNormals") && !FLAT_NORMALS.matcher(vertex).find()) {
            boolean consumed = fragment.contains("FlatNormals");
            localNormal = consumed ? "out vec3 FlatNormals;\n" : "vec3 FlatNormals;\n";
            if (consumed && !FLAT_NORMALS.matcher(fragment).find())
                fragment = fragment.replaceFirst(MAIN.pattern(), "in vec3 FlatNormals;\nvoid main()");
        }
        String normalKey = vertex.contains("in uint iris_Normal;") ? " ^ (iris_Normal & 0xffffffu)" : "";
        String decoratedVertex = vertex(vertex, localNormal, """
                if (cmiFgFrame.x != 0u) {
                    vec4 rest = getVertexPosition();
                    vec4 expected = iris_ProjectionMatrix * iris_ModelViewMatrix * rest;
                    vec4 unjittered = gl_Position;
                    unjittered.xy -= unjittered.w * 2.0 * cmiFgSource.zw / cmiFgSource.xy;
                    vec3 error = unjittered.xyz / max(0.0001, unjittered.w) - expected.xyz / max(0.0001, expected.w);
                    bool deformed = any(greaterThan(abs(error), vec3(0.00005)));
                    uvec3 key = cmiFgWorldKey(rest.xyz);
                    key.z ^= 0xa614a3c5u%s;
                    cmiFgRecord(key, unjittered, deformed);
                }
                """.formatted(normalKey));
        String decoratedFragment = fragment(fragment, "cmiFgPublish(1.0, " + translucent + ", true);");
        if (throughGeometry && decoratedVertex != null)
            decoratedVertex = decoratedVertex.replace("cmiFgPriorClip", "cmiFgVertexPriorClip")
                    .replace("cmiFgMatched", "cmiFgVertexMatched");
        return decoratedVertex == null || decoratedFragment == null ? null : new Pair(decoratedVertex, decoratedFragment);
    }

    /** IterationT forwards the same three input vertices while computing the texture footprint. */
    public static String terrainGeometry(XessFgPack pack, String geometry) {
        if (pack != XessFgPack.ITERATION || !geometry.contains("textureResolution")) return null;
        var position = GEOMETRY_POSITION.matcher(geometry);
        int emit = geometry.indexOf("EmitVertex(");
        if (emit < 0 || emit != geometry.lastIndexOf("EmitVertex(") || !position.find()) return null;
        String input = position.group(1);
        int end = position.end();
        if (end >= emit || position.find()) return null;
        String forwarded = geometry.substring(0, end) + """

                cmiFgPriorClip = cmiFgVertexPriorClip[%s];
                cmiFgMatched = cmiFgVertexMatched[%s];
                """.formatted(input, input) + geometry.substring(end);
        return forwarded.replaceFirst(MAIN.pattern(), """
                in vec4 cmiFgVertexPriorClip[];
                in float cmiFgVertexMatched[];
                out vec4 cmiFgPriorClip;
                out float cmiFgMatched;
                void main()
                """);
    }

    private static String vertex(String source, String before, String record) {
        return wrap(source, EXTENSIONS, Grid.GRID + before + Grid.VERTEX,
                "cmiFgMatched = 0.0; cmiFgPriorClip = vec4(0.0); cmiFgStockMain();\n" + record);
    }

    private static String fragment(String source, String publish) {
        return wrap(source, EXTENSIONS + ATOMICS, Grid.GRID + Grid.FRAGMENT, "cmiFgStockMain();\n" + publish);
    }

    private static String wrap(String source, String extensions, String declarations, String main) {
        var matcher = MAIN.matcher(source);
        int version = source.indexOf("#version");
        if (version < 0 || !matcher.find()) return null;
        int header = source.indexOf('\n', version);
        if (header < 0 || header >= matcher.start()) return null;
        return source.substring(0, header + 1) + extensions + source.substring(header + 1, matcher.start())
                + "\n" + declarations + "\nvoid cmiFgStockMain()" + source.substring(matcher.end())
                + "\nvoid main() {\n" + main + "\n}\n";
    }

    private static final class Grid {
        static final String GRID = resource("history_grid.glsl");
        static final String VERTEX = resource("history_vertex.glsl");
        static final String FRAGMENT = resource("history_fragment.glsl");
    }

    static String resource(String file) {
        String path = "/assets/createmanaindustry/shaders/xessfg/" + file;
        try (var stream = XessFgGpuShaderSources.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing XeSS-FG shader " + file);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Reading XeSS-FG shader " + file, failure);
        }
    }
}
