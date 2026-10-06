package com.iridium126.createmanaindustry.infrastructure.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.tterrag.registrate.builders.BlockBuilder;
import com.tterrag.registrate.util.nullness.NonNullUnaryOperator;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import net.createmod.catnip.registry.RegisteredObjectsHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

/**
 * Server-authoritative gameplay config ({@code createmanaindustry-server.toml}),
 * synced to clients by NeoForge so every player sees the server's values.
 * <p>
 * Grouped by subsystem. The {@code impact}/{@code capacity} stress groups are
 * built lazily by {@link #build()} because their entries are fed by
 * {@link #setImpact}/{@link #setCapacity} during block registration — a
 * {@code static final} spec would be built too early and come out empty.
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class ServerConfig {

    public enum HexJitMode { OFF, PROFILE, AUTO }

    public static volatile HexJitMode hexJitMode = HexJitMode.AUTO;
    public static volatile int hexJitThreshold = 64;
    public static volatile int hexJitMaxUnits = 1024;
    public static volatile long hexJitByteBudget = 16L << 20;
    public static volatile boolean hexJitCompileActions;
    public static volatile boolean hexJitSkipObservers;
    public static volatile boolean hexJitCoalesceDecorations = true;
    public static volatile boolean hexJitBatchAddMotion;
    public static volatile boolean hexJitFastAddMotionArguments;
    public static volatile boolean hexJitMemoAddMotionNormalization;
    public static volatile boolean hexJitFastTickAction = true;
    public static volatile boolean hexJitCombineTickSideEffects = true;
    public static volatile boolean hexJitCacheMaxOpCount = true;
    public static volatile boolean hexJitReuseTickUserData = true;
    public static volatile boolean hexJitBatchTickCounterWrites;
    public static volatile boolean hexJitCacheTickStackPop;
    public static volatile boolean hexJitReuseTickMediaScan = true;
    public static volatile boolean hexJitFastHexOPMediaPool = true;
    public static volatile boolean hexJitBatchTickPersonalMediaWrites = true;
    public static volatile boolean hexJitCacheTickMediaHolder = true;
    public static volatile boolean hexJitCacheTickMediaAvailability = true;
    public static volatile boolean hexJitDirectTickMediaPreflight = true;
    public static volatile boolean hexJitDirectTickMediaExtraction = true;
    public static volatile boolean hexJitCacheTickChunk = true;
    public static volatile boolean hexJitCacheTickRangeCheck = true;
    public static volatile boolean hexJitCacheTickBlockEligibility = true;
    public static volatile boolean hexJitCacheBuddingAmethystState = true;
    public static volatile boolean hexJitLoopSpecialization = true;
    public static volatile boolean hexJitLoopTickDispatch = true;
    public static volatile boolean hexJitLoopTickBatch = true;
    public static volatile boolean hexJitCollectMetrics;
    public static volatile boolean hexJitCoalesceEvalSounds;
    public static volatile boolean hexJitSkipEmptyPostExecution = true;
    public static volatile boolean hexJitFastSpendMediaTrigger = true;
    public static volatile boolean hexJitFastBuddingAmethystRandomTick;
    public static volatile boolean hexJitCacheActionResourceKeys = true;
    public static volatile boolean hexJitCacheActionTagMembership = true;
    public static volatile boolean hexJitCacheActionPrechecks;
    public static volatile boolean hexJitFastStackValidation;
    public static volatile boolean hexJitCacheStackMetrics = true;
    public static volatile boolean hexJitCacheStackValidationResults = true;
    public static volatile boolean hexJitReuseFrameTail;
    public static volatile boolean hexJitFastSpecialHandlerMath = true;
    public static volatile boolean hexJitFastSpecialHandlerLookup;
    public static volatile boolean hexJitFastNumberLiterals = true;
    public static volatile boolean hexJitCacheNormalPatternLookup = true;
    public static volatile boolean hexJitCachePerWorldPatternLookup = true;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.BooleanValue PACKAGE_GPU_AUTHORITY;
    private static final ModConfigSpec.DoubleValue PACKAGE_MAIN_THREAD_BUDGET_MS;
    public static volatile boolean packageGpuAuthority;
    public static volatile double packageMainThreadBudgetMs = 10.0;

    // ---- fluid -------------------------------------------------------------

    private static ModConfigSpec.IntValue MANA_PER_BUCKET;
    private static ModConfigSpec.IntValue MEDIA_PER_BUCKET;
    private static ModConfigSpec.IntValue SOURCE_PER_BUCKET;

    // ---- kinetics ----------------------------------------------------------

    private static ModConfigSpec.DoubleValue MANA_PER_STRESS;
    private static ModConfigSpec.DoubleValue KINETIC_STRESS_TRICK_MANA_MULTIPLIER;

    // ---- mist --------------------------------------------------------------

    private static ModConfigSpec.IntValue MIST_MAX_RADIUS;
    private static ModConfigSpec.IntValue MIST_FLUID_PER_TICK;
    private static ModConfigSpec.DoubleValue MIST_BASE_CONCENTRATION;
    private static ModConfigSpec.DoubleValue CONDENSE_EFFICIENCY;

    // ---- allay_burner ------------------------------------------------------

    private static ModConfigSpec.IntValue MEDIA_CONSUMED_PER_TICK;
    private static ModConfigSpec.IntValue ALLAY_BURNER_MIST_RADIUS;
    private static ModConfigSpec.IntValue ALLAY_BURNER_MIST_PER_TICK;

    // ---- fuel_tank ---------------------------------------------------------

    private static ModConfigSpec.IntValue FUEL_TANK_CAPACITY;
    private static ModConfigSpec.IntValue FUEL_TANK_MAX_BLOCKS;

    // ---- fuel_rod ----------------------------------------------------------

    private static ModConfigSpec.IntValue FUEL_ROD_MAX_RADIUS;
    private static ModConfigSpec.BooleanValue FUEL_ROD_STRICT_STACKING;

    // ---- allay storm -------------------------------------------------------

    private static ModConfigSpec.DoubleValue STORM_CORRECTION_HZ;
    private static ModConfigSpec.IntValue STORM_MAX_COUNT;
    private static ModConfigSpec.DoubleValue STORM_GROWTH_PER_SECOND;
    private static ModConfigSpec.DoubleValue STORM_WAVE_INTERVAL;
    private static ModConfigSpec.DoubleValue STORM_WAVE_FRACTION;
    private static ModConfigSpec.IntValue STORM_WAVE_MAX_SIZE;
    private static ModConfigSpec.DoubleValue STORM_WAVE_DAMAGE;
    private static ModConfigSpec.DoubleValue STORM_WAVE_RANGE;
    private static ModConfigSpec.IntValue STORM_CHASE_Y;

    // ---- hexcasting --------------------------------------------------------

    private static ModConfigSpec.LongValue CYPHER_MAX_MEDIA;
    private static ModConfigSpec.LongValue TRINKET_MAX_MEDIA;
    private static ModConfigSpec.LongValue ARTIFACT_MAX_MEDIA;
    private static ModConfigSpec.LongValue BATTERY_MAX_MEDIA;
    private static ModConfigSpec.IntValue YSM_APPLY_CRYSTALS;
    private static ModConfigSpec.IntValue YSM_EXPORT_CRYSTALS;
    private static ModConfigSpec.DoubleValue HEX_TICK_CONSTANT_COST;
    private static ModConfigSpec.DoubleValue HEX_TICK_COST_PER_TICKED;
    private static ModConfigSpec.IntValue HEX_TICK_RANDOM_TICK_I_PROB;
    private static ModConfigSpec.ConfigValue<List<? extends String>> HEX_TICK_ACCELERATE_DENY_LIST;
    private static ModConfigSpec.EnumValue<HexJitMode> HEX_JIT_MODE;
    private static ModConfigSpec.IntValue HEX_JIT_THRESHOLD;
    private static ModConfigSpec.IntValue HEX_JIT_MAX_UNITS;
    private static ModConfigSpec.IntValue HEX_JIT_BYTE_BUDGET_MIB;
    private static ModConfigSpec.BooleanValue HEX_JIT_COMPILE_ACTIONS;
    private static ModConfigSpec.BooleanValue HEX_JIT_SKIP_OBSERVERS;
    private static ModConfigSpec.BooleanValue HEX_JIT_COALESCE_DECORATIONS;
    private static ModConfigSpec.BooleanValue HEX_JIT_BATCH_ADD_MOTION;
    private static ModConfigSpec.BooleanValue HEX_JIT_FAST_ADD_MOTION_ARGUMENTS;
    private static ModConfigSpec.BooleanValue HEX_JIT_MEMO_ADD_MOTION_NORMALIZATION;
    private static ModConfigSpec.BooleanValue HEX_JIT_FAST_TICK_ACTION;
    private static ModConfigSpec.BooleanValue HEX_JIT_COMBINE_TICK_SIDE_EFFECTS;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_MAX_OP_COUNT;
    private static ModConfigSpec.BooleanValue HEX_JIT_REUSE_TICK_USER_DATA;
    private static ModConfigSpec.BooleanValue HEX_JIT_BATCH_TICK_COUNTER_WRITES;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_TICK_STACK_POP;
    private static ModConfigSpec.BooleanValue HEX_JIT_REUSE_TICK_MEDIA_SCAN;
    private static ModConfigSpec.BooleanValue HEX_JIT_FAST_HEXOP_MEDIA_POOL;
    private static ModConfigSpec.BooleanValue HEX_JIT_BATCH_TICK_PERSONAL_MEDIA_WRITES;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_TICK_MEDIA_HOLDER;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_TICK_MEDIA_AVAILABILITY;
    private static ModConfigSpec.BooleanValue HEX_JIT_DIRECT_TICK_MEDIA_PREFLIGHT;
    private static ModConfigSpec.BooleanValue HEX_JIT_DIRECT_TICK_MEDIA_EXTRACTION;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_TICK_CHUNK;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_TICK_RANGE_CHECK;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_TICK_BLOCK_ELIGIBILITY;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_BUDDING_AMETHYST_STATE;
    private static ModConfigSpec.BooleanValue HEX_JIT_LOOP_SPECIALIZATION;
    private static ModConfigSpec.BooleanValue HEX_JIT_LOOP_TICK_DISPATCH;
    private static ModConfigSpec.BooleanValue HEX_JIT_LOOP_TICK_BATCH;
    private static ModConfigSpec.BooleanValue HEX_JIT_REUSE_LOOP_TICK_IMAGE;
    private static ModConfigSpec.BooleanValue HEX_JIT_COLLECT_METRICS;
    private static ModConfigSpec.BooleanValue HEX_JIT_COALESCE_EVAL_SOUNDS;
    private static ModConfigSpec.BooleanValue HEX_JIT_SKIP_EMPTY_POST_EXECUTION;
    private static ModConfigSpec.BooleanValue HEX_JIT_FAST_SPEND_MEDIA_TRIGGER;
    private static ModConfigSpec.BooleanValue HEX_JIT_FAST_BUDDING_AMETHYST_RANDOM_TICK;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_ACTION_RESOURCE_KEYS;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_ACTION_TAG_MEMBERSHIP;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_ACTION_PRECHECKS;
    private static ModConfigSpec.BooleanValue HEX_JIT_FAST_STACK_VALIDATION;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_STACK_METRICS;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_STACK_VALIDATION_RESULTS;
    private static ModConfigSpec.BooleanValue HEX_JIT_REUSE_FRAME_TAIL;
    private static ModConfigSpec.BooleanValue HEX_JIT_FAST_SPECIAL_HANDLER_MATH;
    private static ModConfigSpec.BooleanValue HEX_JIT_FAST_SPECIAL_HANDLER_LOOKUP;
    private static ModConfigSpec.BooleanValue HEX_JIT_FAST_NUMBER_LITERALS;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_NORMAL_PATTERN_LOOKUP;
    private static ModConfigSpec.BooleanValue HEX_JIT_CACHE_PER_WORLD_PATTERN_LOOKUP;

    static {
        BUILDER.push("gpuPackages");
        PACKAGE_GPU_AUTHORITY=BUILDER.comment("Development ownership transport. Remains inactive until a client has verified complete GPU collision/render resources.")
                .define("authorityEnabled",false);
        PACKAGE_MAIN_THREAD_BUDGET_MS=BUILDER
                .comment("Maximum server main-thread time per tick for each GPU package processing pass, in milliseconds.")
                .defineInRange("mainThreadBudgetMs",10.0,0.25,50.0);
        BUILDER.pop();
        BUILDER.comment("Fluid conversion ratios — how much mana/media/source one bucket holds.").push("fluid");
        MANA_PER_BUCKET = BUILDER
                .comment("The amount of mana contained in one bucket (1000mB) of Liquid Mana.")
                .defineInRange("manaPerBucket", 2048, 2048, 81920);
        MEDIA_PER_BUCKET = BUILDER
                .comment("The amount of media contained in one bucket (1000mB) of Liquid Media.")
                .defineInRange("mediaPerBucket", 400000, 1000, Integer.MAX_VALUE);
        SOURCE_PER_BUCKET = BUILDER
                .comment("The amount of source contained in one bucket (1000mB) of Liquid Source.")
                .defineInRange("sourcePerBucket", 1000, 100, 1000000);
        BUILDER.pop();

        BUILDER.comment("Kinetic-to-mana conversion and the kinetic stress trick.").push("kinetics");
        MANA_PER_STRESS = BUILDER
                .comment("Mana added per stress unit consumed each tick when converting kinetic stress into knot mana.")
                .defineInRange("manaPerStress", 0.001, 0.0, 1000.0);
        KINETIC_STRESS_TRICK_MANA_MULTIPLIER = BUILDER
                .comment("The multiplier applied to the kinetic stress mana trick when costing mana.")
                .defineInRange("kineticStressTrickManaMultiplier", 2.0, 0.0, 1000.0);
        BUILDER.pop();

        BUILDER.comment("Volumetric mist field behaviour (atomizer, condenser, burner).").push("mist");
        MIST_MAX_RADIUS = BUILDER
                .comment("Maximum Euclidean radius (in blocks) of the mist field around an active Kinetic Atomizer.")
                .defineInRange("mistMaxRadius", 16, 1, 32);
        MIST_FLUID_PER_TICK = BUILDER
                .comment("Base fluid amount (mB) consumed per tick when the atomizer is running at 256 RPM. Scales linearly with actual speed.")
                .defineInRange("mistFluidPerTick", 8, 1, 1000);
        MIST_BASE_CONCENTRATION = BUILDER
                .comment("Base concentration at distance 0 from the atomizer. Used in the formula: concentration = base * (1 - distance / radius).")
                .defineInRange("mistBaseConcentration", 1.0, 0.0, 1000.0);
        CONDENSE_EFFICIENCY = BUILDER
                .comment("Base amount (mB/tick) of mist fluid condensed per unit of concentration when water flows through a Condenser.")
                .defineInRange("condenseEfficiency", 5.0, 0.0, 1000.0);
        BUILDER.pop();

        BUILDER.comment("Allay Burner fuel consumption and its Liquid Soul mist.").push("allay_burner");
        MEDIA_CONSUMED_PER_TICK = BUILDER
                .comment("Media consumed per tick while the Allay Burner is burning. Drives the burn duration of all fuels: an item worth N media burns N / mediaConsumedPerTick ticks, and 1 mB of Liquid Media burns (mediaPerBucket / 1000) / mediaConsumedPerTick ticks.")
                .defineInRange("mediaConsumedPerTick", 50, 1, 10000);
        ALLAY_BURNER_MIST_RADIUS = BUILDER
                .comment("Radius (in blocks) of the Liquid Soul mist field emitted while the Allay Burner is burning.")
                .defineInRange("allayBurnerMistRadius", 4, 1, 32);
        ALLAY_BURNER_MIST_PER_TICK = BUILDER
                .comment("Liquid Soul mist capacity (mB) added per tick by a burning Allay Burner.")
                .defineInRange("allayBurnerMistPerTick", 1, 1, 1000);
        BUILDER.pop();

        BUILDER.comment("Molten Salt Fuel Tank — multi-block fluid storage connecting in arbitrary shapes.").push("fuel_tank");
        // Bounds are coupled: blocks × capacity × 1000 must stay ≤ Integer.MAX_VALUE
        // (FluidTank stores mB as an int). 21,474 × 100 × 1000 = 2,147,400,000 ✓.
        FUEL_TANK_CAPACITY = BUILDER
                .comment("Fluid capacity of one fuel tank block, in buckets. Total capacity of a connected group = blocks × fuelTankCapacity × 1000 mB. Capped at 100 so blocks × capacity × 1000 never overflows the int tank capacity.")
                .defineInRange("fuelTankCapacity", 8, 1, 100);
        FUEL_TANK_MAX_BLOCKS = BUILDER
                .comment("Maximum number of blocks in one connected fuel tank group. Bounds the connectivity/basin recomputation cost. Capped at 21,474 (= 2,147,483 / 100) so blocks × fuelTankCapacity × 1000 never overflows the int tank capacity.")
                .defineInRange("fuelTankMaxBlocks", 4096, 1, 21474);
        BUILDER.pop();

        BUILDER.comment("Molten Salt Reactor Fuel Rod — a multi-block structure of fuel tanks and glass.").push("fuel_rod");
        // A layer of radius r holds 2(r-2)² + 2(r-2) + 1 tanks; r = 32 gives 1,861,
        // comfortably inside the fuelTankMaxBlocks group cap with several layers.
        FUEL_ROD_MAX_RADIUS = BUILDER
                .comment("Maximum horizontal radius (in blocks) of one fuel rod layer. A layer is a solid diamond of fuel tanks (Manhattan distance < radius - 1) outlined by glass (#minecraft:impermeable) at distance radius - 1; the minimum valid layer (radius 2) is one tank surrounded by four glass blocks.")
                .defineInRange("fuelRodMaxRadius", 5, 2, 32);
        FUEL_ROD_STRICT_STACKING = BUILDER
                .comment("Whether every fuel rod layer's radius must be less than or equal to the layer below it (a cone that widens downward). When false, layers may stack in any radius order as long as their centers align.")
                .define("fuelRodStrictStacking", true);
        BUILDER.pop();

        BUILDER.comment("Allay Storm — the GPU-driven boss swarm (persistence + network sync).").push("allay_storm");
        STORM_CORRECTION_HZ = BUILDER
                .comment("Authoritative-client position correction snapshots per second for storm members near players. Higher = tighter cross-player position agreement, more bandwidth (~4 KB/s per snapshot at the 256-member cap). Delivered to the authority client with its assignment; changes apply from the next snapshot.")
                .defineInRange("stormCorrectionHz", 5.0, 0.5, 20.0);
        STORM_MAX_COUNT = BUILDER
                .comment("Maximum generated member population of a storm. The command's count argument is the INITIAL population; the storm then grows toward this ceiling (members killed along the way stay dead and keep consuming budget — the total number of members a storm ever generates is capped here). Also hard-clamps the command's initial count. Hard engine ceiling is 131072.")
                .defineInRange("stormMaxCount", 65536, 1, 131072);
        STORM_GROWTH_PER_SECOND = BUILDER
                .comment("Members the storm generates per second while below stormMaxCount (growth runs on the server tick regardless of whether any player is in range). New members spawn on a ring just outside the visible envelope and fly to their storm positions. 0 disables growth (storms spawn at their initial count and never grow).")
                .defineInRange("stormGrowthPerSecond", 20.0, 0.0, 2048.0);
        STORM_WAVE_INTERVAL = BUILDER
                .comment("Seconds between dive-wave launches per player. Each player's cooldown fires independently; a wave launches only when the target is eligible (active, under open sky, within stormWaveRange of the chased center) and fewer than 4 waves are running. Waves are the storm's only offense: squad members break formation, follow a server-computed corridor to the target and deal stormWaveDamage on contact (self-reported by the target's client, server-validated).")
                .defineInRange("stormWaveInterval", 60.0, 5.0, 600.0);
        STORM_WAVE_FRACTION = BUILDER
                .comment("Squad size of a dive wave as a fraction of the ALIVE member population (membership is a deterministic per-member hash against this fraction, identical on every client and re-derivable server-side). Population attrition visibly shrinks the waves — the population IS the boss health bar.")
                .defineInRange("stormWaveFraction", 0.10, 0.001, 1.0);
        STORM_WAVE_MAX_SIZE = BUILDER
                .comment("Absolute cap on a dive-wave squad size (the fraction is clamped so alive * fraction never exceeds this). Bounds the separation-force cost of a converged dive.")
                .defineInRange("stormWaveMaxSize", 256, 1, 4096);
        STORM_WAVE_DAMAGE = BUILDER
                .comment("Damage per dive-wave contact. Fixed (no variance). Routed through player.hurt with the createmanaindustry:storm_peck damage type, so vanilla invulnerability frames, armor, absorption and totems all apply — concurrent divers cannot burst through the 10-tick i-frame window.")
                .defineInRange("stormWaveDamage", 7.0, 0.0, 40.0);
        STORM_WAVE_RANGE = BUILDER
                .comment("Maximum distance from the chased center for a player to be eligible as a wave target (keeps dives inside the client visibility envelope so every active client can render the attack).")
                .defineInRange("stormWaveRange", 96.0, 16.0, 120.0);
        STORM_CHASE_Y = BUILDER
                .comment("Fixed altitude of the chased storm center (the typhoon is a sky storm pinned to this Y; the command input Y is overridden). Lower it and the storm may clip taller terrain — the wave corridor pathfinding routes dives around obstacles regardless.")
                .defineInRange("stormChaseY", 128, 40, 300);
        BUILDER.pop();

        BUILDER.comment("Hexcasting integration settings.").push("hexcasting");
        CYPHER_MAX_MEDIA = BUILDER
                .comment("Maximum media capacity for incomplete cyphers.")
                .defineInRange("cypherMaxMedia", 6400000L, 10000L, Long.MAX_VALUE);
        TRINKET_MAX_MEDIA = BUILDER
                .comment("Maximum media capacity for incomplete trinkets.")
                .defineInRange("trinketMaxMedia", 64000000L, 10000L, Long.MAX_VALUE);
        ARTIFACT_MAX_MEDIA = BUILDER
                .comment("Maximum media capacity for incomplete artifacts.")
                .defineInRange("artifactMaxMedia", 640000000L, 10000L, Long.MAX_VALUE);
        BATTERY_MAX_MEDIA = BUILDER
                .comment("Maximum media capacity for incomplete media batteries.")
                .defineInRange("batteryMaxMedia", 640000000L, 10000L, Long.MAX_VALUE);
        YSM_APPLY_CRYSTALS = BUILDER
                .comment("Charged amethyst units consumed when applying a temporary YSM model edit.")
                .defineInRange("ysmApplyCrystalUnits", 1, 0, 64);
        YSM_EXPORT_CRYSTALS = BUILDER
                .comment("Charged amethyst units consumed when exporting a YSM model.")
                .defineInRange("ysmExportCrystalUnits", 1, 0, 64);
        BUILDER.comment("Hexal-compatible settings for the Tick Acceleration great spell.").push("great_spells");
        HEX_TICK_CONSTANT_COST = BUILDER
                .comment("Base cost per block tick, in Amethyst Dust units. Hexcasting counts 1 dust as 10,000 media.")
                .defineInRange("tickConstantCost", 0.1, 0.0001, 10000.0);
        HEX_TICK_COST_PER_TICKED = BUILDER
                .comment("Additional cost per earlier acceleration of the same block during this cast, in Amethyst Dust units.")
                .defineInRange("tickCostPerTicked", 0.001, 0.0001, 10000.0);
        HEX_TICK_RANDOM_TICK_I_PROB = BUILDER
                .comment("Inverse probability denominator for a random tick: a randomly ticking block is advanced once per this many casts on average.")
                .defineInRange("tickRandomTickIProb", 1365, 600, 2100);
        HEX_TICK_ACCELERATE_DENY_LIST = BUILDER
                .comment("Block IDs that Tick Acceleration cannot advance.")
                .defineList("accelerateDenyList", List.of("hexcasting:impetus_look", "create:deployer"),
                        ServerConfig::isValidResourceLocation);
        BUILDER.pop();
        BUILDER.comment("Server-side Hex JIT controls. These tune execution only; compiled calls preserve Hexcasting action semantics.").push("jit");
        HEX_JIT_MODE = BUILDER
                .comment("OFF uses the interpreter; PROFILE counts hot calls without compiling; AUTO enables tiered compilation.")
                .defineEnum("mode", HexJitMode.AUTO);
        HEX_JIT_THRESHOLD = BUILDER
                .comment("Valid executions at a shared call site before compilation is requested.")
                .defineInRange("hotThreshold", 64, 1, 1000000);
        HEX_JIT_MAX_UNITS = BUILDER
                .comment("Maximum compiled call sites retained at once.")
                .defineInRange("maxUnits", 1024, 1, 65536);
        HEX_JIT_BYTE_BUDGET_MIB = BUILDER
                .comment("Maximum generated class bytecode retained by the JIT, in MiB.")
                .defineInRange("bytecodeBudgetMiB", 16, 1, 256);
        HEX_JIT_COMPILE_ACTIONS = BUILDER
                .comment("Experimental ordinary Action call-site compilation; disabled until it demonstrates a net benefit.")
                .define("compileActions", false);
        HEX_JIT_SKIP_OBSERVERS = BUILDER
                .comment("Allow skipping only PostExecution observers that explicitly implement SkippablePostExecutionObserver.")
                .define("skipDeclaredObservers", false);
        HEX_JIT_COALESCE_DECORATIONS = BUILDER
                .comment("Within one JIT cast, emit only one copy of each identical Hexcasting particle spray and pigment.")
                .define("coalesceDecorations", true);
        HEX_JIT_BATCH_ADD_MOTION = BUILDER
                .comment("Batch repeated Hexcasting Add Motion vector writes while preserving ordered double additions.")
                .define("batchAddMotion", false);
        HEX_JIT_FAST_ADD_MOTION_ARGUMENTS = BUILDER
                .comment("Use a compact two-iota argument list for the stock Hexcasting Add Motion action after exact bytecode verification.")
                .define("fastAddMotionArguments", true);
        HEX_JIT_MEMO_ADD_MOTION_NORMALIZATION = BUILDER
                .comment("Reuse bit-identical Vec3 normalization results inside the stock Add Motion action after exact bytecode verification.")
                .define("memoAddMotionNormalization", true);
        HEX_JIT_FAST_TICK_ACTION = BUILDER
                .comment("Use the low-allocation execution path for this project's Hexcasting Tick action in AUTO mode.")
                .define("fastTickAction", true);
        HEX_JIT_COMBINE_TICK_SIDE_EFFECTS = BUILDER
                .comment("Combine Tick media extraction with its AttemptSpell side effect when AUTO casts have no post-execution observers.")
                .define("combineTickSideEffects", true);
        HEX_JIT_CACHE_MAX_OP_COUNT = BUILDER
                .comment("Cache Hexcasting's configured per-cast operation limit for one synchronous AUTO cast.")
                .define("cacheMaxOpCount", true);
        HEX_JIT_REUSE_TICK_USER_DATA = BUILDER
                .comment("Reuse one private Tick user-data copy during a cast when no execution observer can retain intermediate images.")
                .define("reuseTickUserData", true);
        HEX_JIT_BATCH_TICK_COUNTER_WRITES = BUILDER
                .comment("Experimental: defer Tick's per-cast counter NBT writes across adjacent loop-dispatched Tick actions; flush before another continuation, callback, or cast completion. Disabled by default because current benchmarks show no consistent speedup.")
                .define("batchTickCounterWrites", false);
        HEX_JIT_CACHE_TICK_STACK_POP = BUILDER
                .comment("Reuse a persistent VM stack predecessor after the same TreeList instance was appended during this cast. Disabled by default pending a reference-spell benchmark.")
                .define("cacheTickStackPop", false);
        HEX_JIT_REUSE_TICK_MEDIA_SCAN = BUILDER
                .comment("Reuse the source list from a successful simulated Tick media extraction for its immediate real extraction.")
                .define("reuseTickMediaScan", true);
        HEX_JIT_FAST_HEXOP_MEDIA_POOL = BUILDER
                .comment("Use HexOverpowered's personal media pool directly during eligible standard AUTO casts.")
                .define("fastHexOPMediaPool", true);
        HEX_JIT_BATCH_TICK_PERSONAL_MEDIA_WRITES = BUILDER
                .comment("Defer HexOverpowered personal media attribute writes during repeated Tick extraction while preserving ordered media reads and extraction callbacks.")
                .define("batchTickPersonalMediaWrites", true);
        HEX_JIT_CACHE_TICK_MEDIA_HOLDER = BUILDER
                .comment("Reuse the validated HexOverpowered personal media holder for the current cast.")
                .define("cacheTickMediaHolder", true);
        HEX_JIT_CACHE_TICK_MEDIA_AVAILABILITY = BUILDER
                .comment("Track HexOverpowered personal media remaining during one Tick loop to avoid repeated simulated reads; invalidate before other spell actions.")
                .define("cacheTickMediaAvailability", true);
        HEX_JIT_DIRECT_TICK_MEDIA_PREFLIGHT = BUILDER
                .comment("Directly simulate Tick's media preflight against HexOverpowered's pool when it covers the full scaled cost.")
                .define("directTickMediaPreflight", true);
        HEX_JIT_DIRECT_TICK_MEDIA_EXTRACTION = BUILDER
                .comment("Skip Hexcasting's outer media extraction dispatch for Tick when the prepared HexOverpowered pool covers the full cost and the standard environment has no media hooks.")
                .define("directTickMediaExtraction", true);
        HEX_JIT_CACHE_TICK_CHUNK = BUILDER
                .comment("Reuse Tick target positions and LevelChunks during one AUTO cast while reading the live block state on every Tick.")
                .define("cacheTickChunk", true);
        HEX_JIT_CACHE_TICK_RANGE_CHECK = BUILDER
                .comment("Reuse a successful Tick range check for the same Budding Amethyst target in standard player casts without range or execution observers; invalidate it before other spell effects.")
                .define("cacheTickRangeCheck", true);
        HEX_JIT_CACHE_TICK_BLOCK_ELIGIBILITY = BUILDER
                .comment("Cache the Tick acceleration deny-list decision for the current block during one AUTO cast.")
                .define("cacheTickBlockEligibility", true);
        HEX_JIT_CACHE_BUDDING_AMETHYST_STATE = BUILDER
                .comment("Reuse Budding Amethyst's unchanged block state during one AUTO cast and skip its block-entity lookup.")
                .define("cacheBuddingAmethystState", true);
        HEX_JIT_LOOP_SPECIALIZATION = BUILDER
                .comment("Reuse FrameEvaluate loop frames and immutable program tails during synchronous AUTO casts without post-execution observers.")
                .define("loopSpecialization", true);
        HEX_JIT_LOOP_TICK_DISPATCH = BUILDER
                .comment("Dispatch cached Tick patterns directly from repeated loop frames while retaining per-call environment and media prechecks.")
                .define("loopTickDispatch", true);
        HEX_JIT_LOOP_TICK_BATCH = BUILDER
                .comment("Collapse consecutive loop-specialized Tick VM steps into one frame evaluation while preserving each Tick action, media precheck, world effect, and op-limit check.")
                .define("loopTickBatch", true);
        HEX_JIT_COLLECT_METRICS = BUILDER
                .comment("Collect per-cast HexJIT optimization counters used for profiling and GameTests. Disable to avoid hot-loop counter writes.")
                .define("collectMetrics", false);
        HEX_JIT_COALESCE_EVAL_SOUNDS = BUILDER
                .comment("Skip rebuilding intermediate metacast result wrappers only when the cast environment has no post-execution observers to receive their sound.")
                .define("coalesceEvalSounds", false);
        HEX_JIT_SKIP_EMPTY_POST_EXECUTION = BUILDER
                .comment("Skip the per-pattern post-execution callback when a standard cast environment has no registered observers.")
                .define("skipEmptyPostExecution", true);
        HEX_JIT_FAST_SPEND_MEDIA_TRIGGER = BUILDER
                .comment("Skip Hexcasting's unused player loot-context construction for Spend Media advancement checks during AUTO casts; matching media thresholds still award normally.")
                .define("fastSpendMediaTrigger", true);
        HEX_JIT_FAST_BUDDING_AMETHYST_RANDOM_TICK = BUILDER
                .comment("Use a cast-local geometric random gate for repeated Tick calls on Budding Amethyst. This preserves the configured random-tick probability but changes the world's random-number stream.")
                .define("fastBuddingAmethystRandomTick", false);
        HEX_JIT_CACHE_ACTION_RESOURCE_KEYS = BUILDER
                .comment("Reuse immutable registry resource keys created for Hexcasting action tag checks after exact HexUtils bytecode verification.")
                .define("cacheActionResourceKeys", true);
        HEX_JIT_CACHE_ACTION_TAG_MEMBERSHIP = BUILDER
                .comment("Cache registry tag membership results during AUTO casts until the registry/data-pack epoch changes.")
                .define("cacheActionTagMembership", true);
        HEX_JIT_CACHE_ACTION_PRECHECKS = BUILDER
                .comment("Reuse successful action precheck cost modifiers for immutable registry actions during one standard staff cast.")
                .define("cacheActionPrechecks", false);
        HEX_JIT_FAST_STACK_VALIDATION = BUILDER
                .comment("Use indexed validation for Hexcasting's immutable TreeList casting stacks after exact bytecode verification.")
                .define("fastStackValidation", true);
        HEX_JIT_CACHE_STACK_METRICS = BUILDER
                .comment("Cache serialization metrics for shared immutable TreeList2 segments during one cast after exact bytecode verification.")
                .define("cacheStackMetrics", true);
        HEX_JIT_CACHE_STACK_VALIDATION_RESULTS = BUILDER
                .comment("Reuse successful size checks for identical immutable casting stacks during one cast.")
                .define("cacheStackValidationResults", true);
        HEX_JIT_REUSE_FRAME_TAIL = BUILDER
                .comment("Reuse FrameEvaluate's immutable TreeList tail instead of slicing it twice for one step after exact upstream bytecode verification.")
                .define("reuseFrameTail", true);
        HEX_JIT_FAST_SPECIAL_HANDLER_MATH = BUILDER
                .comment("Avoid cloning Hexcasting's direction enum arrays during special-pattern matching after exact upstream bytecode verification.")
                .define("fastSpecialHandlerMath", true);
        HEX_JIT_FAST_SPECIAL_HANDLER_LOOKUP = BUILDER
                .comment("Cache the ordered special-handler factory registry while still evaluating every factory for each pattern.")
                .define("fastSpecialHandlerLookup", false);
        HEX_JIT_CACHE_NORMAL_PATTERN_LOOKUP = BUILDER
                .comment("Cache normal registry matches on immutable PatternIota instances until the pattern registry epoch changes.")
                .define("cacheNormalPatternLookup", true);
        HEX_JIT_CACHE_PER_WORLD_PATTERN_LOOKUP = BUILDER
                .comment("Cache deterministic per-world great-pattern matches on immutable PatternIota instances until the pattern registry epoch changes.")
                .define("cachePerWorldPatternLookup", true);
        HEX_JIT_FAST_NUMBER_LITERALS = BUILDER
                .comment("Use the verified zero-argument fast path for Hexcasting's built-in number-literal special handler.")
                .define("fastNumberLiterals", true);
        BUILDER.pop();
        BUILDER.pop();

    }

    // ---- stress values (formerly CMIStress) --------------------------------

    // IDs need to be used since configs load before block registration
    private static final Object2DoubleMap<ResourceLocation> DEFAULT_IMPACTS = new Object2DoubleOpenHashMap<>();
    private static final Object2DoubleMap<ResourceLocation> DEFAULT_CAPACITIES = new Object2DoubleOpenHashMap<>();

    private static final Map<ResourceLocation, ConfigValue<Double>> impacts = new HashMap<>();
    private static final Map<ResourceLocation, ConfigValue<Double>> capacities = new HashMap<>();

    /** Built lazily by {@link #build()} once block registration has populated the stress defaults. */
    public static ModConfigSpec SPEC;

    private ServerConfig() {}

    /**
     * Builds the config spec. Must be called after block registration so the
     * {@code impact}/{@code capacity} entries (fed by {@link #setImpact} /
     * {@link #setCapacity}) are present.
     */
    public static void build() {
        BUILDER.comment(".", SU, IMPACT_COMMENT).push("impact");
        DEFAULT_IMPACTS.forEach((id, value) -> impacts.put(id, BUILDER.define(id.getPath(), value)));
        BUILDER.pop();

        BUILDER.comment(".", SU, CAPACITY_COMMENT).push("capacity");
        DEFAULT_CAPACITIES.forEach((id, value) -> capacities.put(id, BUILDER.define(id.getPath(), value)));
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    // ---- runtime values (server-side authority, synced to clients) ----------

    public static double manaPerStress = 0.001;
    public static int manaPerBucket = 2048;
    public static int mediaPerBucket = 400000;
    public static int mediaConsumedPerTick = 50;
    public static int sourcePerBucket = 1000;
    public static double kineticStressTrickManaMultiplier = 2.0;
    public static int mistMaxRadius = 16;
    public static int mistFluidPerTick = 8;
    public static double mistBaseConcentration = 1.0;
    public static double condenseEfficiency = 5.0;
    public static int allayBurnerMistRadius = 4;
    public static int allayBurnerMistPerTick = 1;
    public static int fuelTankCapacity = 8;
    public static int fuelTankMaxBlocks = 4096;
    public static int fuelRodMaxRadius = 5;
    public static boolean fuelRodStrictStacking = true;
    public static double stormCorrectionHz = 5.0;
    public static int stormMaxCount = 65536;
    public static double stormGrowthPerSecond = 20.0;
    public static double stormWaveInterval = 60.0;
    public static double stormWaveFraction = 0.10;
    public static int stormWaveMaxSize = 256;
    public static double stormWaveDamage = 7.0;
    public static double stormWaveRange = 96.0;
    public static int stormChaseY = 128;
    public static long cypherMaxMedia = 6400000L;
    public static long trinketMaxMedia = 64000000L;
    public static long artifactMaxMedia = 640000000L;
    public static long batteryMaxMedia = 640000000L;
    public static int ysmApplyCrystalUnits = 1;
    public static int ysmExportCrystalUnits = 1;
    /** Tick spell cost in media; config values are expressed in dust units like Hexal. */
    public static long tickConstantCost = 1_000L;
    public static long tickCostPerTicked = 10L;
    public static int tickRandomTickIProb = 1365;
    private static volatile Set<ResourceLocation> tickAccelerateDenyList = Set.of(
            ResourceLocation.parse("hexcasting:impetus_look"), ResourceLocation.parse("create:deployer"));

    // ---- stress accessors (BlockStressValues providers) --------------------

    @Nullable
    public static DoubleSupplier getImpact(Block block) {
        ResourceLocation id = RegisteredObjectsHelper.getKeyOrThrow(block);
        ConfigValue<Double> value = impacts.get(id);
        return value == null ? null : value::get;
    }

    @Nullable
    public static DoubleSupplier getCapacity(Block block) {
        ResourceLocation id = RegisteredObjectsHelper.getKeyOrThrow(block);
        ConfigValue<Double> value = capacities.get(id);
        return value == null ? null : value::get;
    }

    // ---- static helpers for block registration -----------------------------

    public static <B extends Block, P> NonNullUnaryOperator<BlockBuilder<B, P>> setNoImpact() {
        return setImpact(0.0);
    }

    public static <B extends Block, P> NonNullUnaryOperator<BlockBuilder<B, P>> setImpact(double value) {
        return builder -> {
            assertFromCMI(builder);
            DEFAULT_IMPACTS.put(CreateManaIndustry.modLoc(builder.getName()), value);
            return builder;
        };
    }

    public static <B extends Block, P> NonNullUnaryOperator<BlockBuilder<B, P>> setCapacity(double value) {
        return builder -> {
            assertFromCMI(builder);
            DEFAULT_CAPACITIES.put(CreateManaIndustry.modLoc(builder.getName()), value);
            return builder;
        };
    }

    private static void assertFromCMI(BlockBuilder<?, ?> builder) {
        if (!builder.getOwner().getModid().equals(CreateManaIndustry.MODID)) {
            throw new IllegalStateException(
                    "Non-" + CreateManaIndustry.MODID + " blocks cannot be added to CMI's config.");
        }
    }

    private static final String SU = "[in Stress Units]";
    private static final String IMPACT_COMMENT =
            "Configure the individual stress impact of mechanical blocks. Note that this cost is doubled for every speed increase it receives.";
    private static final String CAPACITY_COMMENT = "Configure how much stress a source can accommodate for.";

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        // Only Loading/Reloading have a loaded backing config; Unloading would throw
        // "Cannot get config value before config is loaded" on every server stop.
        if (event.getConfig().getSpec() == SPEC
                && (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)) {
            manaPerStress = MANA_PER_STRESS.get();
            manaPerBucket = MANA_PER_BUCKET.get();
            mediaPerBucket = MEDIA_PER_BUCKET.get();
            mediaConsumedPerTick = MEDIA_CONSUMED_PER_TICK.get();
            sourcePerBucket = SOURCE_PER_BUCKET.get();
            kineticStressTrickManaMultiplier = KINETIC_STRESS_TRICK_MANA_MULTIPLIER.get();
            mistMaxRadius = MIST_MAX_RADIUS.get();
            mistFluidPerTick = MIST_FLUID_PER_TICK.get();
            mistBaseConcentration = MIST_BASE_CONCENTRATION.get();
            condenseEfficiency = CONDENSE_EFFICIENCY.get();
            allayBurnerMistRadius = ALLAY_BURNER_MIST_RADIUS.get();
            allayBurnerMistPerTick = ALLAY_BURNER_MIST_PER_TICK.get();
            fuelTankCapacity = FUEL_TANK_CAPACITY.get();
            fuelTankMaxBlocks = FUEL_TANK_MAX_BLOCKS.get();
            fuelRodMaxRadius = FUEL_ROD_MAX_RADIUS.get();
            fuelRodStrictStacking = FUEL_ROD_STRICT_STACKING.get();
            stormCorrectionHz = STORM_CORRECTION_HZ.get();
            packageGpuAuthority=PACKAGE_GPU_AUTHORITY.get();
            packageMainThreadBudgetMs=PACKAGE_MAIN_THREAD_BUDGET_MS.get();
            stormMaxCount = STORM_MAX_COUNT.get();
            stormGrowthPerSecond = STORM_GROWTH_PER_SECOND.get();
            stormWaveInterval = STORM_WAVE_INTERVAL.get();
            stormWaveFraction = STORM_WAVE_FRACTION.get();
            stormWaveMaxSize = STORM_WAVE_MAX_SIZE.get();
            stormWaveDamage = STORM_WAVE_DAMAGE.get();
            stormWaveRange = STORM_WAVE_RANGE.get();
            stormChaseY = STORM_CHASE_Y.get();
            cypherMaxMedia = CYPHER_MAX_MEDIA.get();
            trinketMaxMedia = TRINKET_MAX_MEDIA.get();
            artifactMaxMedia = ARTIFACT_MAX_MEDIA.get();
            batteryMaxMedia = BATTERY_MAX_MEDIA.get();
            ysmApplyCrystalUnits = YSM_APPLY_CRYSTALS.get();
            ysmExportCrystalUnits = YSM_EXPORT_CRYSTALS.get();
            refreshHexTickSettings();
            refreshHexJitSettings();
        }
    }

    private static void refreshHexTickSettings() {
        // Hexcasting's MediaConstants.DUST_UNIT is 10,000. Keep that unit conversion here
        // so the always-loaded server config does not directly depend on Hexcasting classes.
        tickConstantCost = (long) (HEX_TICK_CONSTANT_COST.get() * 10_000L);
        tickCostPerTicked = (long) (HEX_TICK_COST_PER_TICKED.get() * 10_000L);
        tickRandomTickIProb = HEX_TICK_RANDOM_TICK_I_PROB.get();

        Set<ResourceLocation> denyList = new HashSet<>();
        for (String rawId : HEX_TICK_ACCELERATE_DENY_LIST.get()) {
            ResourceLocation id = ResourceLocation.tryParse(rawId);
            if (id != null)
                denyList.add(id);
        }
        tickAccelerateDenyList = Set.copyOf(denyList);
    }

    private static boolean isValidResourceLocation(Object value) {
        return value instanceof String id && ResourceLocation.tryParse(id) != null;
    }

    public static boolean isHexTickAccelerateAllowed(ResourceLocation blockId) {
        return !tickAccelerateDenyList.contains(blockId);
    }

    /** Copies the loaded server config values into the optional JIT runtime. */
    public static void refreshHexJitSettings() {
        hexJitMode = HEX_JIT_MODE.get();
        hexJitThreshold = HEX_JIT_THRESHOLD.get();
        hexJitMaxUnits = HEX_JIT_MAX_UNITS.get();
        hexJitByteBudget = (long) HEX_JIT_BYTE_BUDGET_MIB.get() << 20;
        hexJitCompileActions = HEX_JIT_COMPILE_ACTIONS.get();
        hexJitSkipObservers = HEX_JIT_SKIP_OBSERVERS.get();
        hexJitCoalesceDecorations = HEX_JIT_COALESCE_DECORATIONS.get();
        hexJitBatchAddMotion = HEX_JIT_BATCH_ADD_MOTION.get();
        hexJitFastAddMotionArguments = HEX_JIT_FAST_ADD_MOTION_ARGUMENTS.get();
        hexJitMemoAddMotionNormalization = HEX_JIT_MEMO_ADD_MOTION_NORMALIZATION.get();
        hexJitFastTickAction = HEX_JIT_FAST_TICK_ACTION.get();
        hexJitCombineTickSideEffects = HEX_JIT_COMBINE_TICK_SIDE_EFFECTS.get();
        hexJitCacheMaxOpCount = HEX_JIT_CACHE_MAX_OP_COUNT.get();
        hexJitReuseTickUserData = HEX_JIT_REUSE_TICK_USER_DATA.get();
        hexJitBatchTickCounterWrites = HEX_JIT_BATCH_TICK_COUNTER_WRITES.get();
        hexJitCacheTickStackPop = HEX_JIT_CACHE_TICK_STACK_POP.get();
        hexJitReuseTickMediaScan = HEX_JIT_REUSE_TICK_MEDIA_SCAN.get();
        hexJitFastHexOPMediaPool = HEX_JIT_FAST_HEXOP_MEDIA_POOL.get();
        hexJitBatchTickPersonalMediaWrites = HEX_JIT_BATCH_TICK_PERSONAL_MEDIA_WRITES.get();
        hexJitCacheTickMediaHolder = HEX_JIT_CACHE_TICK_MEDIA_HOLDER.get();
        hexJitCacheTickMediaAvailability = HEX_JIT_CACHE_TICK_MEDIA_AVAILABILITY.get();
        hexJitDirectTickMediaPreflight = HEX_JIT_DIRECT_TICK_MEDIA_PREFLIGHT.get();
        hexJitDirectTickMediaExtraction = HEX_JIT_DIRECT_TICK_MEDIA_EXTRACTION.get();
        hexJitCacheTickChunk = HEX_JIT_CACHE_TICK_CHUNK.get();
        hexJitCacheTickRangeCheck = HEX_JIT_CACHE_TICK_RANGE_CHECK.get();
        hexJitCacheTickBlockEligibility = HEX_JIT_CACHE_TICK_BLOCK_ELIGIBILITY.get();
        hexJitCacheBuddingAmethystState = HEX_JIT_CACHE_BUDDING_AMETHYST_STATE.get();
        hexJitLoopSpecialization = HEX_JIT_LOOP_SPECIALIZATION.get();
        hexJitLoopTickDispatch = HEX_JIT_LOOP_TICK_DISPATCH.get();
        hexJitLoopTickBatch = HEX_JIT_LOOP_TICK_BATCH.get();
        hexJitCollectMetrics = HEX_JIT_COLLECT_METRICS.get();
        hexJitCoalesceEvalSounds = HEX_JIT_COALESCE_EVAL_SOUNDS.get();
        hexJitSkipEmptyPostExecution = HEX_JIT_SKIP_EMPTY_POST_EXECUTION.get();
        hexJitFastSpendMediaTrigger = HEX_JIT_FAST_SPEND_MEDIA_TRIGGER.get();
        hexJitFastBuddingAmethystRandomTick = HEX_JIT_FAST_BUDDING_AMETHYST_RANDOM_TICK.get();
        hexJitCacheActionResourceKeys = HEX_JIT_CACHE_ACTION_RESOURCE_KEYS.get();
        hexJitCacheActionTagMembership = HEX_JIT_CACHE_ACTION_TAG_MEMBERSHIP.get();
        hexJitCacheActionPrechecks = HEX_JIT_CACHE_ACTION_PRECHECKS.get();
        hexJitFastStackValidation = HEX_JIT_FAST_STACK_VALIDATION.get();
        hexJitCacheStackMetrics = HEX_JIT_CACHE_STACK_METRICS.get();
        hexJitCacheStackValidationResults = HEX_JIT_CACHE_STACK_VALIDATION_RESULTS.get();
        hexJitReuseFrameTail = HEX_JIT_REUSE_FRAME_TAIL.get();
        hexJitFastSpecialHandlerMath = HEX_JIT_FAST_SPECIAL_HANDLER_MATH.get();
        hexJitFastSpecialHandlerLookup = HEX_JIT_FAST_SPECIAL_HANDLER_LOOKUP.get();
        hexJitFastNumberLiterals = HEX_JIT_FAST_NUMBER_LITERALS.get();
        hexJitCacheNormalPatternLookup = HEX_JIT_CACHE_NORMAL_PATTERN_LOOKUP.get();
        hexJitCachePerWorldPatternLookup = HEX_JIT_CACHE_PER_WORLD_PATTERN_LOOKUP.get();
    }

    public static long packageMainThreadBudgetNanos() {
        return Math.round(packageMainThreadBudgetMs * 1_000_000.0);
    }
}
