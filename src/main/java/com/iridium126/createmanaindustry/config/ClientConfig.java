package com.iridium126.createmanaindustry.config;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-only rendering config ({@code createmanaindustry-client.toml}).
 * <p>
 * Not loaded on dedicated servers; these values are only read from client-side
 * render code ({@code MistClientHandler}, {@code MistIrisHook}).
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class ClientConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ---- rendering ---------------------------------------------------------

    private static ModConfigSpec.DoubleValue MIST_GLOW_STRENGTH;
    private static ModConfigSpec.BooleanValue MIST_DEBUG_SHADOW;
    private static ModConfigSpec.DoubleValue FUEL_ROD_BLOOM_RING_STRENGTH;

    // ---- particles -------------------------------------------------------

    private static ModConfigSpec.BooleanValue PARTICLE_ENABLED;
    private static ModConfigSpec.IntValue PARTICLE_MAX_COUNT;
    private static ModConfigSpec.DoubleValue PARTICLE_BUDGET_MS;
    private static ModConfigSpec.BooleanValue PARTICLE_AUTO_THROTTLE;
    private static ModConfigSpec.IntValue PARTICLE_FADE_DISTANCE;
    private static ModConfigSpec.BooleanValue PARTICLE_SHADER_PACK_INTEGRATION;
    private static ModConfigSpec.BooleanValue PARTICLE_HEX_SPRAY_REDIRECT;

    // ---- allay dimension (ALLVR) -------------------------------------------

    private static ModConfigSpec.BooleanValue ALLVR_IRIS_INTEGRATION;
    private static ModConfigSpec.BooleanValue ALLVR_IRIS_SHADOW_PASS;
    private static ModConfigSpec.BooleanValue ALLVR_LOD;
    private static ModConfigSpec.EnumValue<AllvrLodBackendMode> ALLVR_LOD_BACKEND;

    static {
        BUILDER.comment("Volumetric mist rendering options.").push("rendering");
        MIST_GLOW_STRENGTH = BUILDER
                .comment("Global multiplier for mist glow.")
                .defineInRange("mistGlowStrength", 0.5, 0.0, 100.0);
        MIST_DEBUG_SHADOW = BUILDER
                .comment("Debug shadow visualization.")
                .define("mistDebugShadow", false);
        FUEL_ROD_BLOOM_RING_STRENGTH = BUILDER
                .comment("Bloom ring strength multiplier.")
                .defineInRange("fuelRodBloomRingStrength", 1.0, 0.0, 100.0);
        BUILDER.pop();

        BUILDER.comment("GPU particle engine options.").push("particles");
        PARTICLE_ENABLED = BUILDER
                .comment("Master switch for GPU particles.")
                .define("enabled", true);
        PARTICLE_MAX_COUNT = BUILDER
                .comment("Maximum live particles allocated.")
                .defineInRange("maxParticles", 2_000_000, 1_000, 4_000_000);
        PARTICLE_BUDGET_MS = BUILDER
                .comment("Frame-time budget (ms) for particles.")
                .defineInRange("frameBudgetMs", 16.6, 1.0, 50.0);
        PARTICLE_AUTO_THROTTLE = BUILDER
                .comment("Auto throttle emission when budget exceeded.")
                .define("autoThrottle", true);
        PARTICLE_FADE_DISTANCE = BUILDER
                .comment("Particle fade distance in blocks.")
                .defineInRange("fadeDistance", 96, 16, 256);
        PARTICLE_SHADER_PACK_INTEGRATION = BUILDER
                .comment("Enable shader pack integration for model particles.")
                .define("shaderPackIntegration", true);
        PARTICLE_HEX_SPRAY_REDIRECT = BUILDER
                .comment("Redirect Hexcasting sprays to GPU engine.")
                .define("hexSprayRedirect", true);
        BUILDER.pop();

        BUILDER.comment("Allay dimension (ALLVR) terrain renderer options.").push("allvr");
        ALLVR_IRIS_INTEGRATION = BUILDER
                .comment("Iris shader integration for allay dimension.")
                .define("irisIntegration", false);
        ALLVR_IRIS_SHADOW_PASS = BUILDER
                .comment("Render allay terrain into shadow map.")
                .define("irisShadowPass", true);
        ALLVR_LOD = BUILDER
                .comment("Enable far-terrain LOD for allay dimension.")
                .define("lod", true);
        ALLVR_LOD_BACKEND = BUILDER
                .comment("Far-terrain LOD backend mode.")
                .defineEnum("lodBackend", AllvrLodBackendMode.AUTO);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * Backend choice for the far-terrain LOD.
     */
    public enum AllvrLodBackendMode {
        AUTO, VOXY, OFF
    }

    public static double mistGlowStrength = 0.5;
    public static boolean mistDebugShadow = false;
    public static double fuelRodBloomRingStrength = 1.0;
    public static boolean particleEnabled = true;
    public static int particleMaxCount = 2_000_000;
    public static double particleBudgetMs = 16.6;
    public static boolean particleAutoThrottle = true;
    public static int particleFadeDistance = 96;
    public static boolean shaderPackIntegration = true;
    public static boolean hexSprayRedirect = true;
    public static boolean allvrIrisIntegration = false;
    public static boolean allvrIrisShadowPass = true;
    public static boolean allvrLod = true;
    public static AllvrLodBackendMode allvrLodBackend = AllvrLodBackendMode.AUTO;

    private ClientConfig() {}

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC
                && (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)) {
            mistGlowStrength = MIST_GLOW_STRENGTH.get();
            mistDebugShadow = MIST_DEBUG_SHADOW.get();
            fuelRodBloomRingStrength = FUEL_ROD_BLOOM_RING_STRENGTH.get();
            particleEnabled = PARTICLE_ENABLED.get();
            particleMaxCount = PARTICLE_MAX_COUNT.get();
            particleBudgetMs = PARTICLE_BUDGET_MS.get();
            particleAutoThrottle = PARTICLE_AUTO_THROTTLE.get();
            particleFadeDistance = PARTICLE_FADE_DISTANCE.get();
            shaderPackIntegration = PARTICLE_SHADER_PACK_INTEGRATION.get();
            hexSprayRedirect = PARTICLE_HEX_SPRAY_REDIRECT.get();
            allvrIrisIntegration = ALLVR_IRIS_INTEGRATION.get();
            allvrIrisShadowPass = ALLVR_IRIS_SHADOW_PASS.get();
            allvrLod = ALLVR_LOD.get();
            allvrLodBackend = ALLVR_LOD_BACKEND.get();
        }
    }
}