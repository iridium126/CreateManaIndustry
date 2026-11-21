package com.iridium126.createmanaindustry.client.xessfg;

import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackInventory;
import com.iridium126.createmanaindustry.client.xessfg.XessFgCoordinates.Warp;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/** Screen-space conventions of the local shader pack samples. */
public enum XessFgPack {
    BLISS("bliss", "lib/TAA_jitter.glsl", "const vec2\\[8] offsets"),
    BSL("bsl", "program/composite5.glsl", "void UnderwaterDistort"),
    COMPLEMENTARY("complementary", "program/final.glsl", "WATER_REFRACTION_INTENSITY"),
    ITERATION("iteration", "shaders.properties", "uniform.vec2.taaJitter"),
    PHOTON("photon", "program/gbuffers_all_solid.vsh", "clip_pos.xy \\+= taa_offset"),
    SILDUR("sildur", "final.fsh", "fake_refract"),
    SOLAS("solas", "lib/antialiasing/jitter.glsl", "offset \\*= 0.5"),
    SUNDIAL("sundial-lite", "programs/composite/Composite14.frag", "DISTORTION_STRENGTH");

    public interface Options {
        boolean flag(String key, boolean fallback);
        float number(String key, float fallback);
    }

    // Values here are normalized to pixels. The original vertex offsets are in homogeneous clip space.
    private static final float[] COMMON_X = {1, -1, 5, 3, -5, -7, 3, 7};
    private static final float[] COMMON_Y = {-3, 3, 1, -5, 5, -1, -7, 7};
    private static final float[] ITERATION_X = {-1, .50976f, .01951f, -.47073f, -.96098f, .54878f, .05853f,
            -.43171f, -.92196f, .58780f, .09755f, -.39269f, -.88294f, .62682f, .13657f, -.35367f};
    private static final float[] ITERATION_Y = {-1, .13968f, -.72064f, .41904f, -.44128f, .69840f, -.16191f,
            .97776f, .11744f, -.74287f, .39680f, -.46351f, .67617f, -.18415f, .95553f, .09521f};

    private final String family, source, contract;

    XessFgPack(String family, String source, String contract) {
        this.family = family;
        this.source = source;
        this.contract = contract;
    }

    public static XessFgPack detect(ShaderPackInventory inventory, String family) {
        for (var pack : values()) {
            if (pack.family.equals(family) && inventory.containsCode(pack.source, pack.contract)) return pack;
        }
        return null;
    }

    public boolean hasHandDeformation(ShaderPackInventory inventory) {
        return switch (this) {
            case COMPLEMENTARY -> inventory.containsCode("lib/misc/handSway.glsl",
                    "gl_Position\\.x\\s*\\+=.*sin\\(frameTimeCounter \\* 0\\.86\\).*gl_Position\\.y\\s*\\+=.*cos\\(frameTimeCounter \\* 1\\.5\\)");
            case BSL -> inventory.containsCode("program/gbuffers_hand.glsl", "position\\.y -= WorldCurvature\\(position\\.xz\\)")
                    && inventory.containsCode("lib/vertex/worldCurvature.glsl", "dot\\(pos, pos\\) / WORLD_CURVATURE_SIZE");
            default -> false;
        };
    }

    Consumer<Vector4f> handDeformation(Options options, Matrix4fc projection, Matrix4fc modelView,
                                       Matrix4fc worldView, float time) {
        if (this == COMPLEMENTARY) {
            float strength = switch ((int) options.number("HAND_SWAYING", 0)) {
                case 1 -> .5f;
                case 2 -> 1;
                case 3 -> 2;
                default -> 0;
            };
            if (strength == 0) return null;
            float x = strength * (float) Math.sin(time * .86f) / 256;
            float y = strength * (float) Math.cos(time * 1.5f) / 64;
            Matrix4f clip = new Matrix4f(projection).mul(modelView);
            return vertex -> clip.transform(vertex).add(x, y, 0, 0);
        }
        if (this != BSL || !options.flag("WORLD_CURVATURE", false) || projection.m22() >= -.5f) return null;
        float radius = options.number("WORLD_CURVATURE_SIZE", 256);
        if (!Float.isFinite(radius) || radius == 0) return null;
        Matrix4f world = new Matrix4f(worldView).invert().mul(modelView);
        Matrix4f clip = new Matrix4f(projection).mul(worldView);
        return vertex -> {
            world.transform(vertex);
            vertex.y -= (vertex.x * vertex.x + vertex.z * vertex.z) / radius;
            clip.transform(vertex);
        };
    }

    public XessFgCoordinates coordinates(Options options, int counter, int width, int height, int fluid,
                                         boolean rain, float time) {
        boolean taa = options.flag("TAA", true);
        int step = counter & 7;
        float x = COMMON_X[step] / 16, y = COMMON_Y[step] / 16;
        float scaleX = 1, scaleY = 1, strength = 0;
        Warp warp = Warp.NONE;
        switch (this) {
            case BLISS -> {
                if (step == 3 || step == 6) x = -x;
                if (step == 6) y = -y;
                if (options.flag("SCREENSHOT_MODE", false)) return null;
                if (options.flag("TAA_UPSCALING", false)) scaleX = scaleY = options.number("SCALE_FACTOR", .75f);
            }
            case BSL -> {
                if (options.number("TAA_MODE", 0) != 0) {
                    x = (counter & 1) == 0 ? .5f : 0;
                    y = .5f - x;
                }
                if (fluid == 1 && options.flag("UNDERWATER_DISTORTION", true)) {
                    warp = Warp.BSL_WATER;
                    strength = .0005f;
                }
            }
            case COMPLEMENTARY -> {
                float weight = switch ((int) options.number("TAA_JITTER", 1)) {
                    case 0 -> 0;
                    case 1 -> .125f;
                    case 2 -> .33f;
                    default -> 1;
                };
                x *= weight;
                y *= weight;
                if (fluid == 1 && options.flag("UNDERWATER_DISTORTION", true)) {
                    warp = Warp.COMPLEMENTARY_WATER;
                    strength = options.number("WATER_REFRACTION_INTENSITY", 2) * .00035f;
                }
            }
            case ITERATION -> {
                x = ITERATION_X[counter & 15] * .5f;
                y = ITERATION_Y[counter & 15] * .5f;
            }
            case PHOTON -> {
                boolean taau = options.flag("TAAU", false);
                if (taau) scaleX = scaleY = options.number("TAAU_RENDER_SCALE", .75f);
                float weight = taau ? .5f : .33f;
                x = (fract(1.3247179572f * counter + .5f) * 2 - 1) * weight;
                y = (fract(1.7548776662f * counter + .5f) * 2 - 1) * weight * height / width;
                taa |= taau;
            }
            case SILDUR -> {
                if (step == 3) x = -x;
                if (step == 6 || step == 7) y = -y;
                if (options.flag("Refraction", true)) {
                    if (rain && options.flag("Rain_Drops", true)) warp = Warp.SILDUR_RAIN;
                    else if (fluid != 0) warp = Warp.SILDUR_REFRACTION;
                    strength = fluid * .002f;
                }
            }
            case SOLAS -> {
                x *= .5f;
                y *= .5f;
            }
            case SUNDIAL -> {
                if (options.flag("SR_ENABLE", false)) return null;
                x = fract(.5f + (counter & 15) * .7548776269f) - .5f;
                y = fract(.5f + (counter & 15) * .5698402524f) - .5f;
                strength = options.number("DISTORTION_STRENGTH", 0);
                if (strength != 0) warp = Warp.SUNDIAL_LENS;
            }
        }
        if (!(scaleX > 0 && scaleX <= 1 && scaleY > 0 && scaleY <= 1 && Float.isFinite(strength))) return null;
        return new XessFgCoordinates(scaleX, scaleY, taa ? x : 0, taa ? y : 0, warp, time, strength);
    }

    private static float fract(float value) {
        return value - (float) Math.floor(value);
    }
}
