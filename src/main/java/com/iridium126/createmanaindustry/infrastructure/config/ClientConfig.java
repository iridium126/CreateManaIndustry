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

    // ---- defaults (single source for the spec and the runtime fields) ------

    public static final Boolean DEFAULT_CLIENT_PACKAGES = false;
    public static final double DEFAULT_MIST_GLOW_STRENGTH = 0.5;
    public static final boolean DEFAULT_MIST_DEBUG_SHADOW = false;
    public static final boolean DEFAULT_PARTICLE_ENABLED = true;
    public static final int DEFAULT_PARTICLE_MAX_COUNT = 2_000_000;
    public static final double DEFAULT_PARTICLE_BUDGET_MS = 15.0;
    public static final boolean DEFAULT_PARTICLE_AUTO_THROTTLE = true;
    public static final int DEFAULT_PARTICLE_FADE_DISTANCE = 96;
    public static final boolean DEFAULT_SHADER_PACK_INTEGRATION = true;
    public static final boolean DEFAULT_RT_CAMERA_OCCLUSION = true;
    public static final boolean DEFAULT_PARTICLE_RECEIVE_SHADOWS = true;
    public static final int DEFAULT_ALLAY_FEATHER_RATE = 1000;
    public static final int DEFAULT_ALLAY_GOLD_RATE = 2000;
    public static final int DEFAULT_STORM_LOD_1_DISTANCE = 1024;
    public static final int DEFAULT_STORM_LOD_DISTANCE = 4096;
    public static final boolean DEFAULT_HEX_PARTICLE_REDIRECT = true;
    public static final boolean DEFAULT_HEX_PATTERN_REDIRECT = true;
    public static final int DEFAULT_HEX_PATTERN_GPU_BUDGET_MIB = 16;
    public static final double DEFAULT_PACKAGE_MAIN_THREAD_BUDGET_MS = 10.0;
    public static final int DEFAULT_PACKAGE_COLLISION_MAX_SECTIONS = 256;
    public static final double DEFAULT_GRID_SNAP_THRESHOLD = 0.75;
    public static final int DEFAULT_PARTITION_MAX_PARTICLES = 131072;
    public static final int DEFAULT_SAME_FATE_MAX_PARTICLES = 131072;
    public static final boolean DEFAULT_ALLVR_LOD = true;
    public static final boolean DEFAULT_RIFT_SKY_CLIPPING = true;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Definition order matches the generated TOML and the configuration screen.
    private static ModConfigSpec.DoubleValue MIST_GLOW_STRENGTH;
    private static ModConfigSpec.BooleanValue MIST_DEBUG_SHADOW;
    private static ModConfigSpec.BooleanValue ALLVR_LOD;
    private static ModConfigSpec.BooleanValue PARTICLE_ENABLED, PARTICLE_AUTO_THROTTLE, PARTICLE_SHADER_PACK_INTEGRATION;
    private static ModConfigSpec.IntValue PARTICLE_MAX_COUNT, PARTICLE_FADE_DISTANCE;
    private static ModConfigSpec.DoubleValue PARTICLE_BUDGET_MS;
    private static ModConfigSpec.IntValue PARTITION_MAX_PARTICLES, SAME_FATE_MAX_PARTICLES;
    private static ModConfigSpec.EnumValue<ParticleShadowQuality> STORM_SHADOW_QUALITY;
    private static ModConfigSpec.EnumValue<StormSimulationBackend> STORM_BACKEND;
    private static ModConfigSpec.ConfigValue<String> STORM_GPU;
    private static final ModConfigSpec.BooleanValue XESS_FG_ENABLED;
    private static final ModConfigSpec.ConfigValue<String> XESS_FG_GPU;
    private static ModConfigSpec.BooleanValue RT_CAMERA_OCCLUSION, PARTICLE_RECEIVE_SHADOWS;
    private static ModConfigSpec.IntValue ALLAY_FEATHER_RATE, ALLAY_GOLD_RATE, STORM_LOD_1_DISTANCE, STORM_LOD_DISTANCE;
    private static ModConfigSpec.BooleanValue PARTICLE_HEX_REDIRECT, PARTICLE_HEX_PATTERN_REDIRECT;
    private static ModConfigSpec.IntValue HEX_PATTERN_GPU_BUDGET;
    private static ModConfigSpec.DoubleValue PACKAGE_MAIN_THREAD_BUDGET_MS;
    private static ModConfigSpec.IntValue PACKAGE_COLLISION_MAX_SECTIONS;
    private static ModConfigSpec.DoubleValue GRID_SNAP_THRESHOLD;
    private static ModConfigSpec.BooleanValue RIFT_SKY_CLIPPING;
    private static ModConfigSpec.EnumValue<RiftCulling> RIFT_CULLING;

    static {
        BUILDER.comment("Startup switches read before any config is loaded. Changing a switch requires restarting the game.").push("experimental");
        // The runtime value is read from the raw TOML by ExperimentalStartup before Mixins load;
        // this entry only writes the file and renders the toggle. Changing it does not affect the
        // running process until the game restarts.
        BUILDER.comment("Enable client GPU package simulation and its Mixins. Requires a participating server and compatible GPU resources.")
                .gameRestart().define("clientPackages", DEFAULT_CLIENT_PACKAGES);
        BUILDER.pop();

        BUILDER.comment("Volumetric mist and far-terrain rendering options.").push("rendering");
        MIST_GLOW_STRENGTH = BUILDER
                .comment("Global multiplier for mist glow.")
                .defineInRange("mistGlowStrength", DEFAULT_MIST_GLOW_STRENGTH, 0.0, 100.0);
        MIST_DEBUG_SHADOW = BUILDER
                .comment("Color mist by the Tyndall shadow sample (green = lit, red = occluded). Requires the shaderpack (Iris) mist path; the native mist shader has no shadow sampling.")
                .define("mistDebugShadow", DEFAULT_MIST_DEBUG_SHADOW);
        ALLVR_LOD = BUILDER
                .comment("Enable far-terrain LOD rendering for the Allay dimension.")
                .define("lod", DEFAULT_ALLVR_LOD);
        BUILDER.pop();

        BUILDER.push("xessFg");
        XESS_FG_ENABLED = BUILDER.comment("Enable Intel XeSS Frame Generation on supported Windows clients.")
                .translation("createmanaindustry.configuration.xessFg.enabled")
                .gameRestart().define("enabled", false);
        XESS_FG_GPU = BUILDER.comment("Use auto or a PnP device ID with an optional |model label.")
                .translation("createmanaindustry.configuration.xessFg.gpu")
                .gameRestart().define("gpu", XessFgGpu.AUTO, XessFgGpu::valid);
        BUILDER.pop();

        BUILDER.comment("Shared GPU particle engine: capacity, frame budget, fade distance and shader integration.").push("particles");
        PARTICLE_ENABLED = BUILDER
                .comment("Master switch for GPU particles.")
                .define("enabled", DEFAULT_PARTICLE_ENABLED);
        PARTICLE_MAX_COUNT = BUILDER
                .comment("Maximum live particles allocated by the shared GPU pool.")
                .defineInRange("maxParticles", DEFAULT_PARTICLE_MAX_COUNT, 1_000, 4_000_000);
        PARTICLE_BUDGET_MS = BUILDER
                .comment("Frame-time budget (ms) for particle work.")
                .defineInRange("frameBudgetMs", DEFAULT_PARTICLE_BUDGET_MS, 1.0, 50.0);
        PARTICLE_AUTO_THROTTLE = BUILDER
                .comment("Reduce emission automatically when the frame budget is exceeded.")
                .define("autoThrottle", DEFAULT_PARTICLE_AUTO_THROTTLE);
        PARTICLE_FADE_DISTANCE = BUILDER
                .comment("Distance in blocks where ALPHA/ADDITIVE particles start fading; MODEL/OPAQUE particles vanish as whole instances 24 blocks farther.")
                .defineInRange("fadeDistance", DEFAULT_PARTICLE_FADE_DISTANCE, 16, 256);
        PARTICLE_SHADER_PACK_INTEGRATION = BUILDER
                .comment("Enable shader pack integration for model particles.")
                .define("shaderPackIntegration", DEFAULT_SHADER_PACK_INTEGRATION);
        RT_CAMERA_OCCLUSION = BUILDER.comment("Use hardware RT for particle camera occlusion.")
                .define("rtCameraOcclusion", DEFAULT_RT_CAMERA_OCCLUSION);
        PARTICLE_RECEIVE_SHADOWS = BUILDER.comment("Receive native shader-pack shadows on particles.")
                .define("receiveShadows", DEFAULT_PARTICLE_RECEIVE_SHADOWS);
        PARTITION_MAX_PARTICLES = BUILDER.comment("Maximum Partition membrane/edge samples within the shared GPU pool; 0 disables samples.")
                .defineInRange("partitionMaxParticles", DEFAULT_PARTITION_MAX_PARTICLES, 0, 1048576);
        SAME_FATE_MAX_PARTICLES = BUILDER.comment("Maximum Shared Fate ribbon, star and dissolution records within the shared GPU pool.")
                .defineInRange("sameFateMaxParticles", DEFAULT_SAME_FATE_MAX_PARTICLES, 256, 1048576);
        BUILDER.pop();

        BUILDER.comment("Allay Storm visuals and client simulation.").push("allayStorm");
        STORM_BACKEND = BUILDER.comment("AUTO prefers OptiX, then DXR, with OpenGL fallback. DXR and OPTIX retain the last frame on failure.")
                .defineEnum("simulationBackend", StormSimulationBackend.AUTO);
        STORM_GPU = BUILDER.comment("Use minecraft or a PnP device ID with an optional |model label. OPENGL follows Minecraft.")
                .define("simulationGpu", StormSimulationGpu.DEFAULT_VALUE, StormSimulationGpu::valid);
        STORM_SHADOW_QUALITY = BUILDER.comment("FULL uses Iris casting. COARSE can reject the whole Storm body.",
                "RT uses qualified light occlusion. OFF disables particle shadow casting.")
                .defineEnum("shaderPackShadowQuality", ParticleShadowQuality.FULL);
        RIFT_SKY_CLIPPING=BUILDER.comment("Clip target sky to the current portal; sky history and lighting may differ.").define("riftSkyClipping",DEFAULT_RIFT_SKY_CLIPPING);
        RIFT_CULLING=BUILDER.comment("NONE, FRUSTUM, FRUSTUM_DEPTH. Unqualified depth falls back to frustum. Combat continues.").defineEnum("riftCulling",RiftCulling.FRUSTUM_DEPTH);
        ALLAY_FEATHER_RATE = BUILDER.comment("Target total feathers per second over the whole 2R field at storm radius 32, scaled by (R/32)^2. Spawn points are sampled uniformly across the 2R disk and accepted with weight 1 inside R and (2R - d)/R out to 2R, so density falls off linearly from R to 2R. 0 stops new feathers; existing ones finish their lifetime.")
                .defineInRange("allayFeatherMaxSpawnPerSecond",DEFAULT_ALLAY_FEATHER_RATE,0,2000);
        ALLAY_GOLD_RATE = BUILDER.comment("Target total gold particles per second over the whole 2R field at storm radius 32, scaled by (R/32)^2. Uses the same radial weight as feathers. 0 stops new particles.")
                .defineInRange("allayGoldMaxSpawnPerSecond",DEFAULT_ALLAY_GOLD_RATE,0,4000);
        STORM_LOD_1_DISTANCE = BUILDER.comment("Distance in blocks where opaque body LOD ends and billboard LOD begins.").defineInRange("stormLod1Distance",DEFAULT_STORM_LOD_1_DISTANCE,120,8192);
        STORM_LOD_DISTANCE = BUILDER.comment("Distant display/subscription limit in blocks, capped by the server's stormLodMaxDistance. 0 disables distant members.").defineInRange("stormLodDistance",DEFAULT_STORM_LOD_DISTANCE,0,8192);
        BUILDER.pop();

        BUILDER.comment("Hexcasting GPU rendering.").push("hexcasting");
        PARTICLE_HEX_REDIRECT = BUILDER
                .comment("Redirect Hexcasting conjure particles and sprays to GPU engine.")
                .define("hexParticleRedirect", DEFAULT_HEX_PARTICLE_REDIRECT);
        PARTICLE_HEX_PATTERN_REDIRECT = BUILDER.comment("Render Hexcasting world/shadow/player-preview and spellcasting UI patterns with the shared GPU backend.").define("hexPatternRedirect", DEFAULT_HEX_PATTERN_REDIRECT);
        HEX_PATTERN_GPU_BUDGET = BUILDER.comment("Maximum Hex-specific GPU buffer allocation, including geometry, commands and in-flight parameter banks, in MiB. Overflow preserves native rendering.")
                .defineInRange("hexPatternGpuBudgetMiB", DEFAULT_HEX_PATTERN_GPU_BUDGET_MIB, 1, 256);
        BUILDER.pop();

        BUILDER.comment("Create package GPU resources.").push("packages");
        PACKAGE_MAIN_THREAD_BUDGET_MS = BUILDER
                .comment("Soft client-frame allowance shared by geometry capture and atlas upload, in milliseconds. Capture reserves half for upload; force/world reads and solver submission are measured separately.")
                .defineInRange("packageMainThreadBudgetMs", DEFAULT_PACKAGE_MAIN_THREAD_BUDGET_MS, 0.25, 50.0);
        PACKAGE_COLLISION_MAX_SECTIONS = BUILDER
                .comment("Maximum resident GPU package collision sections for this client. Independent of other players; changes apply on re-entering the world. Clamped to the device SSBO limit; light capacity is separate.")
                .defineInRange("packageCollisionMaxSections", DEFAULT_PACKAGE_COLLISION_MAX_SECTIONS, 1, 1024);
        BUILDER.pop();

        BUILDER.comment("Great-trick spell drawing options.").push("greatTricks");
        GRID_SNAP_THRESHOLD = BUILDER
                .comment("Fraction of one grid spacing to travel before snapping to the next dot. Capture radius is (1 - value) times grid spacing; 0.75 leaves the final 25%. 点阵吸附阈值：默认走过点间距的 75% 才吸附，捕获半径为剩余的 25%。")
                .defineInRange("gridSnapThreshold", DEFAULT_GRID_SNAP_THRESHOLD, 0.0, 1.0);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static double mistGlowStrength = DEFAULT_MIST_GLOW_STRENGTH;
    public static boolean mistDebugShadow = DEFAULT_MIST_DEBUG_SHADOW;
    public static boolean allvrLod = DEFAULT_ALLVR_LOD;
    public static boolean particleEnabled = DEFAULT_PARTICLE_ENABLED;
    public static int particleMaxCount = DEFAULT_PARTICLE_MAX_COUNT;
    public static double particleBudgetMs = DEFAULT_PARTICLE_BUDGET_MS;
    public static boolean particleAutoThrottle = DEFAULT_PARTICLE_AUTO_THROTTLE;
    public static int particleFadeDistance = DEFAULT_PARTICLE_FADE_DISTANCE;
    public static boolean shaderPackIntegration = DEFAULT_SHADER_PACK_INTEGRATION;
    public static ParticleShadowQuality shaderPackShadowQuality = ParticleShadowQuality.FULL;
    public record StormSimulation(StormSimulationBackend backend, StormSimulationGpu gpu) {}
    public static volatile StormSimulation stormSimulation = new StormSimulation(
            StormSimulationBackend.AUTO, StormSimulationGpu.FOLLOW_MINECRAFT);
    public record XessFg(boolean enabled, XessFgGpu gpu) {}
    private static volatile XessFg xessFg = new XessFg(false, XessFgGpu.parse(XessFgGpu.AUTO));

    public static XessFg xessFg() {
        return xessFg;
    }
    public static volatile boolean rtCameraOcclusion = DEFAULT_RT_CAMERA_OCCLUSION, particleReceiveShadows = DEFAULT_PARTICLE_RECEIVE_SHADOWS;
    public static int allayFeatherMaxSpawnPerSecond = DEFAULT_ALLAY_FEATHER_RATE, allayGoldMaxSpawnPerSecond = DEFAULT_ALLAY_GOLD_RATE;
    public static int stormLod1Distance = DEFAULT_STORM_LOD_1_DISTANCE, stormLodDistance = DEFAULT_STORM_LOD_DISTANCE;
    public static boolean hexParticleRedirect = DEFAULT_HEX_PARTICLE_REDIRECT, hexPatternRedirect = DEFAULT_HEX_PATTERN_REDIRECT;
    public static int hexPatternGpuBudgetMiB = DEFAULT_HEX_PATTERN_GPU_BUDGET_MIB;
    public static boolean packageGpuAuthority = Boolean.TRUE.equals(DEFAULT_CLIENT_PACKAGES);
    public static double packageMainThreadBudgetMs = DEFAULT_PACKAGE_MAIN_THREAD_BUDGET_MS;
    public static int packageCollisionMaxSections = DEFAULT_PACKAGE_COLLISION_MAX_SECTIONS;
    public static double gridSnapThreshold = DEFAULT_GRID_SNAP_THRESHOLD;
    public static int partitionMaxParticles = DEFAULT_PARTITION_MAX_PARTICLES, sameFateMaxParticles = DEFAULT_SAME_FATE_MAX_PARTICLES;
    public static boolean riftSkyClipping = DEFAULT_RIFT_SKY_CLIPPING;
    public static RiftCulling riftCulling=RiftCulling.FRUSTUM_DEPTH;

    private ClientConfig() {}

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC
                && (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)) {
            if (event instanceof ModConfigEvent.Loading) {
                xessFg = new XessFg(XESS_FG_ENABLED.get(), XessFgGpu.parse(XESS_FG_GPU.get()));
            }
            allvrLod = ALLVR_LOD.get();
            mistGlowStrength = MIST_GLOW_STRENGTH.get();
            mistDebugShadow = MIST_DEBUG_SHADOW.get();
            particleEnabled = PARTICLE_ENABLED.get();
            particleMaxCount = PARTICLE_MAX_COUNT.get();
            particleBudgetMs = PARTICLE_BUDGET_MS.get();
            particleAutoThrottle = PARTICLE_AUTO_THROTTLE.get();
            particleFadeDistance = PARTICLE_FADE_DISTANCE.get();
            shaderPackIntegration = PARTICLE_SHADER_PACK_INTEGRATION.get();
            partitionMaxParticles = PARTITION_MAX_PARTICLES.get();
            sameFateMaxParticles = SAME_FATE_MAX_PARTICLES.get();
            shaderPackShadowQuality = STORM_SHADOW_QUALITY.get();
            stormSimulation = new StormSimulation(STORM_BACKEND.get(), StormSimulationGpu.parse(STORM_GPU.get()));
            rtCameraOcclusion = RT_CAMERA_OCCLUSION.get();
            particleReceiveShadows = PARTICLE_RECEIVE_SHADOWS.get();
            allayFeatherMaxSpawnPerSecond = ALLAY_FEATHER_RATE.get();
            allayGoldMaxSpawnPerSecond = ALLAY_GOLD_RATE.get();
            stormLod1Distance = STORM_LOD_1_DISTANCE.get();
            stormLodDistance = STORM_LOD_DISTANCE.get();
            hexParticleRedirect = PARTICLE_HEX_REDIRECT.get();
            hexPatternRedirect = PARTICLE_HEX_PATTERN_REDIRECT.get();
            hexPatternGpuBudgetMiB = HEX_PATTERN_GPU_BUDGET.get();
            packageGpuAuthority = ExperimentalStartup.settings().clientPackages();
            packageMainThreadBudgetMs = PACKAGE_MAIN_THREAD_BUDGET_MS.get();
            packageCollisionMaxSections = PACKAGE_COLLISION_MAX_SECTIONS.get();
            gridSnapThreshold = GRID_SNAP_THRESHOLD.get();
            riftSkyClipping=RIFT_SKY_CLIPPING.get();riftCulling=RIFT_CULLING.get();
        }
    }

    public static long packageMainThreadBudgetNanos() {
        return Math.round(packageMainThreadBudgetMs * 1_000_000.0);
    }
}
