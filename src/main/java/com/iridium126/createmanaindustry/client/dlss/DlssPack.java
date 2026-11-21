package com.iridium126.createmanaindustry.client.dlss;

import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackInventory;
import com.iridium126.createmanaindustry.client.xessfg.XessFgPack;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Source and scene-color contracts for the pass that normally accumulates scene history. */
public record DlssPack(String pass, int color, Encoding encoding, Map<String, String> sources) {
    public static final String RESOLVE = "cmi_DlssResolve";

    public enum Encoding {
        SDR(false, 1, 1), HDR(true, 1, 1),
        SILDUR(true, 1, 0.454f), SUNDIAL(false, 10, 1);

        public final boolean hdr;
        public final float scale, gamma;

        Encoding(boolean hdr, float scale, float gamma) {
            this.hdr = hdr;
            this.scale = scale;
            this.gamma = gamma;
        }
    }

    public static DlssPack detect(ShaderPackInventory inventory, XessFgPack family) {
        if (family == null) return null;
        var edits = new HashMap<String, String>();
        var pack = switch (family) {
            case BLISS -> new DlssPack("composite6", 3, Encoding.HDR, edits);
            case BSL -> new DlssPack("composite7", 1, Encoding.SDR, edits);
            case COMPLEMENTARY -> new DlssPack("composite6", 3, Encoding.SDR, edits);
            case ITERATION -> new DlssPack("composite7", 1, Encoding.SDR, edits);
            case PHOTON -> new DlssPack("composite4", 0, Encoding.HDR, edits);
            case SILDUR -> new DlssPack("composite2", 4, Encoding.SILDUR, edits);
            case SOLAS -> new DlssPack("composite15", 1, Encoding.SDR, edits);
            case SUNDIAL -> new DlssPack("composite9", 3, Encoding.SUNDIAL, edits);
        };
        boolean matched = switch (family) {
            case BLISS -> edit(inventory, edits, "dimensions/composite5.fsh", source -> replace(source,
                    "vec4 color = computeTAA(texcoord, hand);",
                    "vec4 color = cmi_DlssResolve ? vec4(texture2D(colortex3, texcoord).rgb, 1.0) : computeTAA(texcoord, hand);"))
                    && edit(inventory, edits, "dimensions/composite5.vsh", source -> replace(source,
                    "gl_Position.xy = (gl_Position.xy*0.5+0.5)*RENDER_SCALE*2.0-1.0;",
                    "if (!cmi_DlssResolve) gl_Position.xy = (gl_Position.xy*0.5+0.5)*RENDER_SCALE*2.0-1.0;"));
            case BSL -> edit(inventory, edits, "program/composite7.glsl", source -> replace(replace(source,
                    "newTexCoord += offset / vec2(viewWidth, viewHeight);",
                    "if (!cmi_DlssResolve) newTexCoord += offset / vec2(viewWidth, viewHeight);"),
                    "prev = TemporalAA(color, prev.r);", "if (!cmi_DlssResolve) prev = TemporalAA(color, prev.r);"));
            case COMPLEMENTARY -> edit(inventory, edits, "program/composite6.glsl", source -> replace(source,
                    "DoTAA(color, temp, z1);", "if (!cmi_DlssResolve) DoTAA(color, temp, z1);"));
            case ITERATION -> edit(inventory, edits, "Lib/BasicFounctions/TAA.glsl", source -> replace(source,
                    "taa = TemporalReprojection(texCoord, velocity, depth);",
                    "taa = cmi_DlssResolve ? texelFetch(colortex1, texelCoord, 0) : TemporalReprojection(texCoord, velocity, depth);"))
                    && edit(inventory, edits, "Lib/BasicFounctions/TAA_Nether.glsl", source -> replace(source,
                    "taa = TemporalReprojection(texCoord, velocity, depth, referenceDepth);",
                    "if (cmi_DlssResolve) { taa = texelFetch(colortex1, texelCoord, 0); referenceDepth = taa.a; }"
                            + " else taa = TemporalReprojection(texCoord, velocity, depth, referenceDepth);"));
            case PHOTON -> edit(inventory, edits, "program/c4_taa_exposure.fsh", DlssPack::photon)
                    && editBefore(inventory, edits, "program/c4_taa_exposure.vsh", "void build_histogram(", source -> replace(replace(source,
                    "coord * taau_render_scale, lod", "coord * (cmi_DlssResolve ? 1.0 : taau_render_scale), lod"),
                    "0.5 * taau_render_scale), int(lod)", "0.5 * (cmi_DlssResolve ? 1.0 : taau_render_scale)), int(lod)"));
            case SILDUR -> edit(inventory, edits, "composite2.fsh", DlssPack::sildur)
                    && edit(inventory, edits, "world-1/composite2.fsh", DlssPack::sildur)
                    && edit(inventory, edits, "world1/composite2.fsh", DlssPack::sildur);
            case SOLAS -> edit(inventory, edits, "programs/composite15.glsl", source -> replace(source,
                    "previousColor = TemporalAA(color, previousColor.r, z1);",
                    "if (!cmi_DlssResolve) previousColor = TemporalAA(color, previousColor.r, z1);"));
            case SUNDIAL -> edit(inventory, edits, "programs/composite/Composite9.frag", source -> replace(source,
                    "solidColor = temporalAntiAliasing(texcoord, texel, velocity.st, solidColor, velocity.w);",
                    "if (!cmi_DlssResolve) solidColor = temporalAntiAliasing(texcoord, texel, velocity.st, solidColor, velocity.w);"));
        };
        return matched ? new DlssPack(pack.pass, pack.color, pack.encoding, Map.copyOf(edits)) : null;
    }

    public String transform(String file, String source) {
        return sources.getOrDefault(ShaderPackInventory.normalize(file), source);
    }

    private static boolean edit(ShaderPackInventory inventory, Map<String, String> edits, String file,
                                UnaryOperator<String> patch) {
        return editBefore(inventory, edits, file, "void main(", patch);
    }

    private static boolean editBefore(ShaderPackInventory inventory, Map<String, String> edits, String file,
                                      String declaration, UnaryOperator<String> patch) {
        String source = inventory.source(file).replace("\r\n", "\n");
        if (source.isEmpty()) return false;
        String changed = patch.apply(source);
        if (changed == null) return false;
        var main = Pattern.compile("\\b" + Pattern.quote(declaration.substring(0, declaration.length() - 1))
                + "\\s*\\(").matcher(ShaderPackInventory.code(changed));
        if (!main.find()) return false;
        edits.put(file, changed.substring(0, main.start()) + "uniform bool " + RESOLVE + ";\n" + changed.substring(main.start()));
        return true;
    }

    private static String replace(String source, String expected, String replacement) {
        if (source == null) return null;
        int position = ShaderPackInventory.code(source).indexOf(expected);
        if (position < 0 || source.indexOf(expected, position + expected.length()) >= 0) return null;
        return source.substring(0, position) + replacement + source.substring(position + expected.length());
    }

    private static String sildur(String source) {
        return replace(source, "buffer7.rgb = funTAA(sky);", "if (!cmi_DlssResolve) buffer7.rgb = funTAA(sky);");
    }

    private static String photon(String source) {
        String start = "#ifdef TAA\n#ifndef LOD_MOD_ACTIVE\n    vec3 closest";
        String end = "result = texelFetch(colortex0, texel, 0);\n#endif";
        return replace(replace(replace(source, end, end + "\n    }"), start,
                "if (cmi_DlssResolve) {\n        result = texelFetch(colortex0, ivec2(gl_FragCoord.xy), 0);\n    } else {\n"
                        + start), "ivec2 texel = ivec2(gl_FragCoord.xy * taau_render_scale);",
                "ivec2 texel = ivec2(gl_FragCoord.xy * (cmi_DlssResolve ? 1.0 : taau_render_scale));");
    }
}
