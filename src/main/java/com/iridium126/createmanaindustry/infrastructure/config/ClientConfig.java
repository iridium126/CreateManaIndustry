package com.iridium126.createmanaindustry.infrastructure.config;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-only rendering and spell drawing config ({@code createmanaindustry-client.toml}).
 * <p>
 * Local rendering/resource choices; gameplay authority belongs to ServerConfig.
 * Legacy configuration keys are intentionally not migrated during development.
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class ClientConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Definition order matches the generated TOML and the configuration screen.
    private static ModConfigSpec.DoubleValue MIST_GLOW_STRENGTH, FUEL_ROD_BLOOM_RING_STRENGTH;
    private static ModConfigSpec.BooleanValue MIST_DEBUG_SHADOW;
    private static ModConfigSpec.BooleanValue PARTICLE_ENABLED, PARTICLE_AUTO_THROTTLE, PARTICLE_SHADER_PACK_INTEGRATION;
    private static ModConfigSpec.IntValue PARTICLE_MAX_COUNT, PARTICLE_FADE_DISTANCE;
    private static ModConfigSpec.DoubleValue PARTICLE_BUDGET_MS;
    private static ModConfigSpec.EnumValue<ParticleShadowQuality> STORM_SHADOW_QUALITY;
    private static ModConfigSpec.EnumValue<StormSimulationBackend> STORM_BACKEND;
    private static ModConfigSpec.BooleanValue RT_CAMERA_OCCLUSION, PARTICLE_RECEIVE_SHADOWS;
    private static ModConfigSpec.IntValue ALLAY_FEATHER_RATE, ALLAY_GOLD_RATE, STORM_LOD_1_DISTANCE, STORM_LOD_DISTANCE;
    private static ModConfigSpec.BooleanValue PARTICLE_HEX_REDIRECT, PARTICLE_HEX_PATTERN_REDIRECT;
    private static ModConfigSpec.IntValue HEX_PATTERN_GPU_BUDGET;
    private static ModConfigSpec.BooleanValue PACKAGE_GPU_AUTHORITY;
    private static ModConfigSpec.DoubleValue PACKAGE_MAIN_THREAD_BUDGET_MS;
    private static ModConfigSpec.IntValue PACKAGE_COLLISION_MAX_SECTIONS;
    private static ModConfigSpec.DoubleValue GRID_SNAP_THRESHOLD;
    private static ModConfigSpec.IntValue PARTITION_MAX_PARTICLES, SAME_FATE_MAX_PARTICLES;
    private static ModConfigSpec.BooleanValue ALLVR_LOD, RIFT_SKY_CLIPPING;
    private static ModConfigSpec.EnumValue<RiftCulling> RIFT_CULLING;

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
                .defineInRange("frameBudgetMs", 15.0, 1.0, 50.0);
        PARTICLE_AUTO_THROTTLE = BUILDER
                .comment("Auto throttle emission when budget exceeded.")
                .define("autoThrottle", true);
        PARTICLE_FADE_DISTANCE = BUILDER
                .comment("ALPHA/ADDITIVE fade starts here; MODEL/OPAQUE disappear as whole particles 24 blocks farther away.")
                .defineInRange("fadeDistance", 96, 16, 256);
        PARTICLE_SHADER_PACK_INTEGRATION = BUILDER
                .comment("Enable shader pack integration for model particles.")
                .define("shaderPackIntegration", true);
        RT_CAMERA_OCCLUSION = BUILDER.comment("Use hardware RT for particle camera occlusion.")
                .define("rtCameraOcclusion", true);
        PARTICLE_RECEIVE_SHADOWS = BUILDER.comment("Receive native shader-pack shadows on particles.")
                .define("receiveShadows", true);
        BUILDER.pop();

        BUILDER.comment("Allay Storm visuals; these options do not change combat or simulation.").push("allayStorm");
        STORM_BACKEND = BUILDER.comment("AUTO uses RT and falls back to GL on failure. RT freezes on failure.")
                .defineEnum("simulationBackend", StormSimulationBackend.AUTO);
        STORM_SHADOW_QUALITY = BUILDER.comment("FULL uses Iris casting. COARSE can reject the whole Storm body.",
                "RT uses qualified light occlusion. OFF disables particle shadow casting.")
                .defineEnum("shaderPackShadowQuality", ParticleShadowQuality.FULL);
        RIFT_SKY_CLIPPING=BUILDER.comment("Clip target sky to the current portal; sky history and lighting may differ.").define("riftSkyClipping",true);
        RIFT_CULLING=BUILDER.comment("NONE, FRUSTUM, FRUSTUM_DEPTH. Unqualified depth falls back to frustum. Combat continues.").defineEnum("riftCulling",RiftCulling.FRUSTUM_DEPTH);
        ALLAY_FEATHER_RATE = BUILDER.comment("Feathers per second at R=32, scaled by (R/32)^2. 0 stops new feathers; existing ones finish their 60-second lifetime.")
                .defineInRange("allayFeatherMaxSpawnPerSecond",1000,0,2000);
        ALLAY_GOLD_RATE = BUILDER.comment("Gold particles per second at R=32, scaled by (R/32)^2. 0 stops new particles.")
                .defineInRange("allayGoldMaxSpawnPerSecond",2000,0,4000);
        STORM_LOD_1_DISTANCE = BUILDER.comment("Distance in blocks where opaque body LOD ends and billboard LOD begins.").defineInRange("stormLod1Distance",1024,120,8192);
        STORM_LOD_DISTANCE = BUILDER.comment("Distant display/subscription limit in blocks, also capped by the server. 0 disables distant members.").defineInRange("stormLodDistance",4096,0,8192);
        BUILDER.pop();

        BUILDER.comment("Hexcasting GPU rendering.").push("hexcasting");
        PARTICLE_HEX_REDIRECT = BUILDER
                .comment("Redirect Hexcasting conjure particles and sprays to GPU engine.")
                .define("hexParticleRedirect", true);
        PARTICLE_HEX_PATTERN_REDIRECT = BUILDER.comment("Render Hexcasting world/shadow/player-preview and spellcasting UI patterns with the shared GPU backend.").define("hexPatternRedirect", true);
        HEX_PATTERN_GPU_BUDGET = BUILDER.comment("Maximum Hex-specific GPU buffer allocation, including geometry, commands and in-flight parameter banks, in MiB. Overflow preserves native rendering.")
                .defineInRange("hexPatternGpuBudgetMiB", 16, 1, 256);
        BUILDER.pop();

        BUILDER.comment("Create package GPU resources.").push("packages");
        PACKAGE_GPU_AUTHORITY = BUILDER.comment("Experimental Create package authority opt-in. Requires matching server opt-in and complete GPU collision/render resources; visual validation is pending. Further bandwidth optimization is deferred.")
                .define("packageGpuAuthority", false);
        PACKAGE_MAIN_THREAD_BUDGET_MS = BUILDER
                .comment("Soft client-frame allowance shared by geometry capture and atlas upload, in milliseconds. Capture reserves half for upload; force/world reads and solver submission are measured separately.")
                .defineInRange("packageMainThreadBudgetMs", 10.0, 0.25, 50.0);
        PACKAGE_COLLISION_MAX_SECTIONS = BUILDER
                .comment("Maximum resident GPU package collision sections for this client. Independent of other players; changes apply on re-entering the world. Clamped to the device SSBO limit; light capacity is separate.")
                .defineInRange("packageCollisionMaxSections", 256, 1, 1024);
        BUILDER.pop();

        BUILDER.comment("Great-trick spell drawing options.").push("greatTricks");
        GRID_SNAP_THRESHOLD = BUILDER
                .comment("Fraction of one grid spacing to travel before snapping to the next dot. Capture radius is (1 - value) times grid spacing; 0.75 leaves the final 25%. 点阵吸附阈值：默认走过点间距的 75% 才吸附，捕获半径为剩余的 25%。")
                .defineInRange("gridSnapThreshold", 0.75, 0.0, 1.0);
        PARTITION_MAX_PARTICLES = BUILDER.comment("Maximum Partition membrane/edge samples within the shared GPU pool; 0 disables samples.")
                .defineInRange("partitionMaxParticles",131072,0,1048576);
        SAME_FATE_MAX_PARTICLES = BUILDER.comment("Maximum Shared Fate ribbon, star and dissolution records within the shared GPU pool.")
                .defineInRange("sameFateMaxParticles",131072,256,1048576);
        BUILDER.pop();

        BUILDER.comment("Allay dimension (ALLVR) options.").push("allvr");
        ALLVR_LOD = BUILDER
                .comment("Enable far-terrain LOD for allay dimension.")
                .define("lod", true);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static double mistGlowStrength = .5, fuelRodBloomRingStrength = 1.;
    public static boolean mistDebugShadow = false;
    public static boolean particleEnabled = true;
    public static int particleMaxCount = 2_000_000;
    public static double particleBudgetMs = 15.;
    public static boolean particleAutoThrottle = true;
    public static int particleFadeDistance = 96;
    public static boolean shaderPackIntegration = true;
    public static ParticleShadowQuality shaderPackShadowQuality = ParticleShadowQuality.FULL;
    public static volatile StormSimulationBackend stormSimulationBackend = StormSimulationBackend.AUTO;
    public static volatile boolean rtCameraOcclusion = true, particleReceiveShadows = true;
    public static int allayFeatherMaxSpawnPerSecond = 1000, allayGoldMaxSpawnPerSecond = 2000;
    public static int stormLod1Distance = 1024, stormLodDistance = 4096;
    public static boolean hexParticleRedirect = true, hexPatternRedirect = true;
    public static int hexPatternGpuBudgetMiB = 16;
    public static boolean packageGpuAuthority = false;
    public static double packageMainThreadBudgetMs = 10.;
    public static int packageCollisionMaxSections = 256;
    public static double gridSnapThreshold = .75;
    public static int partitionMaxParticles = 131072, sameFateMaxParticles = 131072;
    public static boolean allvrLod = true, riftSkyClipping=true;
    public static RiftCulling riftCulling=RiftCulling.FRUSTUM_DEPTH;

    private ClientConfig() {}

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC
                && (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)) {
            mistGlowStrength = MIST_GLOW_STRENGTH.get();
            fuelRodBloomRingStrength = FUEL_ROD_BLOOM_RING_STRENGTH.get();
            mistDebugShadow = MIST_DEBUG_SHADOW.get();
            particleEnabled = PARTICLE_ENABLED.get();
            particleMaxCount = PARTICLE_MAX_COUNT.get();
            particleBudgetMs = PARTICLE_BUDGET_MS.get();
            particleAutoThrottle = PARTICLE_AUTO_THROTTLE.get();
            particleFadeDistance = PARTICLE_FADE_DISTANCE.get();
            shaderPackIntegration = PARTICLE_SHADER_PACK_INTEGRATION.get();
            shaderPackShadowQuality = STORM_SHADOW_QUALITY.get();
            stormSimulationBackend = STORM_BACKEND.get();
            rtCameraOcclusion = RT_CAMERA_OCCLUSION.get();
            particleReceiveShadows = PARTICLE_RECEIVE_SHADOWS.get();
            allayFeatherMaxSpawnPerSecond = ALLAY_FEATHER_RATE.get();
            allayGoldMaxSpawnPerSecond = ALLAY_GOLD_RATE.get();
            stormLod1Distance = STORM_LOD_1_DISTANCE.get();
            stormLodDistance = STORM_LOD_DISTANCE.get();
            hexParticleRedirect = PARTICLE_HEX_REDIRECT.get();
            hexPatternRedirect = PARTICLE_HEX_PATTERN_REDIRECT.get();
            hexPatternGpuBudgetMiB = HEX_PATTERN_GPU_BUDGET.get();
            packageGpuAuthority = PACKAGE_GPU_AUTHORITY.get();
            packageMainThreadBudgetMs = PACKAGE_MAIN_THREAD_BUDGET_MS.get();
            packageCollisionMaxSections = PACKAGE_COLLISION_MAX_SECTIONS.get();
            gridSnapThreshold = GRID_SNAP_THRESHOLD.get();
            partitionMaxParticles = PARTITION_MAX_PARTICLES.get();
            sameFateMaxParticles = SAME_FATE_MAX_PARTICLES.get();
            allvrLod = ALLVR_LOD.get();
            riftSkyClipping=RIFT_SKY_CLIPPING.get();riftCulling=RIFT_CULLING.get();
        }
    }

    public static long packageMainThreadBudgetNanos() {
        return Math.round(packageMainThreadBudgetMs * 1_000_000.0);
    }
}
