package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.addldata.ADMediaHolder;
import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ExecutionClientView;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.GarbageIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.api.mod.HexStatistics;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.casting.PatternRegistryManifest;
import at.petrak.hexcasting.api.casting.PatternShapeMatch;
import com.iridium126.createmanaindustry.compat.hexcasting.OpTick;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import io.yukkuric.hexparse.hooks.GreatPatternUnlocker;
import io.yukkuric.hexparse.hooks.PatternMapper;
import io.yukkuric.hexparse.misc.CodeHelpers;
import io.yukkuric.hexparse.parsers.ParserMain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexJitReferenceSpellGameTests {
    private static final int EXPECTED_TICK_CALLS = 92_160;
    private static final int BENCHMARK_OP_LIMIT = 100_000;
    private static final ResourceLocation YJSP_MEDIA = ResourceLocation.parse("hexoverpowered:yjsp_media");
    private static final ResourceLocation TICK = ResourceLocation.parse("createmanaindustry:tick");
    private static final long WORLD_RANDOM_SEED = 0x5eed94720L;
    private static final String FISHERMAN_SPELL = "get_caster,get_caster,get_entity_look,num_3,mul,num_2,last_n_list,write/local,empty_list,duplicate,num_100(empty_list,eval/cc,num_4,fisherman,read/local,add,num_4,fisherman(add_motion)add,num_4,fisherman,duplicate,num_0,greater(num_1,sub,num_4,fisherman)()if,eval,num_4,fisherman,duplicate,eval)eval/cc,mask_v--vv,append,num_4,num_100,mul,write/local(empty_list,eval/cc,read/local,duplicate,num_0,greater(num_1,sub,write/local,rotate,duplicate,splat,eval,rotate_reverse)(mask_v-vv)if,eval,duplicate,eval)eval/cc,mask_v,const/null,write/local";
    private static final List<ReferenceSpell> REFERENCE_SPELLS = List.of(
            new ReferenceSpell("original-tick", """
                    num_2,num_9,pow,duplicate_n,num_2,num_9,pow,last_n_list((())deconstruct,singleton,rotate_reverse,add,add(swap)add)duplicate,singleton,write/local,eval((swap((swap,eval)eval/cc)swap(eval)add,add,eval)eval/cc)read/local,over,append,write/local,eval,singleton,read/local,num_0,index,eval,unappend,mask_v(splat(swap,eval)eval/cc,splat)add,swap,append,swap,num_2,num_9,pow,duplicate_n,num_2,num_9,pow,last_n_list,add,read/local,num_1,index,eval(rotate_reverse,over(swap,eval)eval/cc,rotate)num_100,num_80,add,singleton(num_-1(swap,num_1,add,duplicate(const/vec/0)splat,less(swap,const/vec/0,duplicate,eval)(mask_vv)if,eval)duplicate,eval)tuck,duplicate,num_0,num_7,slice,rotate_reverse,num_8,num_13,slice,add,add,rotate_reverse,num_14,num_25,slice,add,add,eval,mask_v,const/null,write/local
                    """, List.of(TICK, YJSP_MEDIA), EXPECTED_TICK_CALLS, BENCHMARK_OP_LIMIT),
            new ReferenceSpell("fisherman-loop", FISHERMAN_SPELL, List.of(), 0, BENCHMARK_OP_LIMIT)
    );

    private HexJitReferenceSpellGameTests() {}

    @GameTest(template = "hex_jit_test", timeoutTicks = 1200)
    public static void referenceSpellsOffVsAuto(GameTestHelper helper) throws Exception {
        helper.assertTrue(ModList.get().isLoaded("hexparse"), "HexParse is missing from the HexJIT test runtime");
        helper.assertTrue(ModList.get().isLoaded("hexoverpowered"), "Hex Overpowered is missing from the test runtime");

        ServerLevel level = helper.getLevel();
        var player = FakePlayerFactory.getMinecraft(level);
        configureHexOverpowered(player);
        BlockPos target = helper.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(target.getX() + 2.5, target.getY(), target.getZ() + 0.5);
        player.setYRot(0);
        player.setXRot(0);

        CodeHelpers.autoRefresh(level.getServer());
        GreatPatternUnlocker unlocker = GreatPatternUnlocker.get(level);
        for (ReferenceSpell referenceSpell : REFERENCE_SPELLS) {
            for (ResourceLocation patternId : referenceSpell.initialPatternsBottomToTop())
                unlocker.unlock(patternId.toString());
        }

        PatternIota yjspIota = greatPattern(YJSP_MEDIA);
        PatternIota tickIota = greatPattern(TICK);
        var envForMatching = new ReferenceSpellEnvironment(
                player, yjspIota.getPattern(), tickIota.getPattern(), BENCHMARK_OP_LIMIT);
        helper.assertTrue(PatternRegistryManifest.matchPattern(yjspIota.getPattern(), envForMatching)
                        instanceof PatternShapeMatch.PerWorld,
                "HexParse did not resolve yjsp_media to its registered great action");
        helper.assertTrue(PatternRegistryManifest.matchPattern(tickIota.getPattern(), envForMatching)
                        instanceof PatternShapeMatch.PerWorld,
                "createmanaindustry:tick is not registered as a great action");

        ServerConfig.HexJitMode oldMode = ServerConfig.hexJitMode;
        int oldThreshold = ServerConfig.hexJitThreshold;
        boolean oldCompileActions = ServerConfig.hexJitCompileActions;
        boolean oldSkipObservers = ServerConfig.hexJitSkipObservers;
        boolean oldCoalesce = ServerConfig.hexJitCoalesceDecorations;
        boolean oldBatchMotion = ServerConfig.hexJitBatchAddMotion;
        boolean oldFastMotion = ServerConfig.hexJitFastAddMotionArguments;
        boolean oldMemoMotion = ServerConfig.hexJitMemoAddMotionNormalization;
        boolean oldFastTickAction = ServerConfig.hexJitFastTickAction;
        boolean oldCombineTickSideEffects = ServerConfig.hexJitCombineTickSideEffects;
        boolean oldCacheMaxOpCount = ServerConfig.hexJitCacheMaxOpCount;
        boolean oldReuseTickUserData = ServerConfig.hexJitReuseTickUserData;
        boolean oldBatchTickCounterWrites = ServerConfig.hexJitBatchTickCounterWrites;
        boolean oldLoopFastTickCounter = ServerConfig.hexJitLoopFastTickCounter;
        boolean oldCacheTickStackPop = ServerConfig.hexJitCacheTickStackPop;
        boolean oldReuseTickMediaScan = ServerConfig.hexJitReuseTickMediaScan;
        boolean oldFastHexOPMediaPool = ServerConfig.hexJitFastHexOPMediaPool;
        boolean oldBatchTickPersonalMediaWrites = ServerConfig.hexJitBatchTickPersonalMediaWrites;
        boolean oldCacheTickMediaHolder = ServerConfig.hexJitCacheTickMediaHolder;
        boolean oldCacheTickMediaAvailability = ServerConfig.hexJitCacheTickMediaAvailability;
        boolean oldDirectTickMediaPreflight = ServerConfig.hexJitDirectTickMediaPreflight;
        boolean oldDirectTickMediaExtraction = ServerConfig.hexJitDirectTickMediaExtraction;
        boolean oldCacheTickChunk = ServerConfig.hexJitCacheTickChunk;
        boolean oldCacheTickRangeCheck = ServerConfig.hexJitCacheTickRangeCheck;
        boolean oldCacheTickBlockEligibility = ServerConfig.hexJitCacheTickBlockEligibility;
        boolean oldCacheBuddingAmethystState = ServerConfig.hexJitCacheBuddingAmethystState;
        boolean oldLoopSpecialization = ServerConfig.hexJitLoopSpecialization;
        boolean oldLoopTickDispatch = ServerConfig.hexJitLoopTickDispatch;
        boolean oldLoopTickBatch = ServerConfig.hexJitLoopTickBatch;
        boolean oldCollectMetrics = ServerConfig.hexJitCollectMetrics;
        boolean oldCoalesceEvalSounds = ServerConfig.hexJitCoalesceEvalSounds;
        boolean oldSkipEmptyPostExecution = ServerConfig.hexJitSkipEmptyPostExecution;
        boolean oldFastSpendMediaTrigger = ServerConfig.hexJitFastSpendMediaTrigger;
        boolean oldFastBuddingAmethystRandomTick = ServerConfig.hexJitFastBuddingAmethystRandomTick;
        boolean oldCacheActionResourceKeys = ServerConfig.hexJitCacheActionResourceKeys;
        boolean oldCacheActionTagMembership = ServerConfig.hexJitCacheActionTagMembership;
        boolean oldCacheActionPrechecks = ServerConfig.hexJitCacheActionPrechecks;
        boolean oldFastStack = ServerConfig.hexJitFastStackValidation;
        boolean oldCacheMetrics = ServerConfig.hexJitCacheStackMetrics;
        boolean oldCacheValidationResults = ServerConfig.hexJitCacheStackValidationResults;
        boolean oldReuseTail = ServerConfig.hexJitReuseFrameTail;
        boolean oldFastMath = ServerConfig.hexJitFastSpecialHandlerMath;
        boolean oldFastLookup = ServerConfig.hexJitFastSpecialHandlerLookup;
        boolean oldFastLiterals = ServerConfig.hexJitFastNumberLiterals;
        boolean oldPatternCache = ServerConfig.hexJitCacheNormalPatternLookup;
        boolean oldPerWorldPatternCache = ServerConfig.hexJitCachePerWorldPatternLookup;
        try {
            ServerConfig.hexJitThreshold = 2;
            // Match ServerConfig's defaults; keep this deterministic if other GameTests mutate globals.
            ServerConfig.hexJitCompileActions = false;
            ServerConfig.hexJitSkipObservers = true;
            ServerConfig.hexJitCoalesceDecorations = true;
            ServerConfig.hexJitBatchAddMotion = false;
            ServerConfig.hexJitFastAddMotionArguments = true;
            ServerConfig.hexJitMemoAddMotionNormalization = false;
            ServerConfig.hexJitFastTickAction = true;
            ServerConfig.hexJitCombineTickSideEffects = true;
            ServerConfig.hexJitCacheMaxOpCount = true;
            ServerConfig.hexJitReuseTickUserData = true;
            ServerConfig.hexJitBatchTickCounterWrites = false;
            ServerConfig.hexJitLoopFastTickCounter = true;
            ServerConfig.hexJitCacheTickStackPop = false;
            ServerConfig.hexJitReuseTickMediaScan = true;
            ServerConfig.hexJitFastHexOPMediaPool = true;
            ServerConfig.hexJitBatchTickPersonalMediaWrites = true;
            ServerConfig.hexJitCacheTickMediaHolder = true;
            ServerConfig.hexJitCacheTickMediaAvailability = true;
            ServerConfig.hexJitDirectTickMediaPreflight = true;
            ServerConfig.hexJitDirectTickMediaExtraction = true;
            ServerConfig.hexJitCacheTickChunk = true;
            ServerConfig.hexJitCacheTickRangeCheck = true;
            ServerConfig.hexJitCacheTickBlockEligibility = false;
            ServerConfig.hexJitCacheBuddingAmethystState = true;
            ServerConfig.hexJitLoopSpecialization = true;
            ServerConfig.hexJitLoopTickDispatch = true;
            ServerConfig.hexJitLoopTickBatch = true;
            ServerConfig.hexJitCollectMetrics = false;
            ServerConfig.hexJitCoalesceEvalSounds = false;
            ServerConfig.hexJitSkipEmptyPostExecution = true;
            ServerConfig.hexJitFastSpendMediaTrigger = true;
            ServerConfig.hexJitFastBuddingAmethystRandomTick = false;
            ServerConfig.hexJitCacheActionResourceKeys = false;
            ServerConfig.hexJitCacheActionTagMembership = true;
            ServerConfig.hexJitCacheActionPrechecks = false;
            ServerConfig.hexJitFastStackValidation = true;
            ServerConfig.hexJitCacheStackMetrics = true;
            ServerConfig.hexJitCacheStackValidationResults = true;
            ServerConfig.hexJitReuseFrameTail = true;
            ServerConfig.hexJitFastSpecialHandlerMath = true;
            ServerConfig.hexJitFastSpecialHandlerLookup = true;
            ServerConfig.hexJitFastNumberLiterals = true;
            ServerConfig.hexJitCacheNormalPatternLookup = true;
            ServerConfig.hexJitCachePerWorldPatternLookup = true;

            for (ReferenceSpell referenceSpell : REFERENCE_SPELLS) {
                Iota parsed = ParserMain.ParseCode(referenceSpell.source().strip(), player);
                helper.assertTrue(parsed instanceof ListIota,
                        "HexParse did not return a program list for " + referenceSpell.name());
                List<Iota> program = ((ListIota) parsed).getList();
                helper.assertTrue(!program.isEmpty(), referenceSpell.name() + " parsed to an empty spell");
                helper.assertTrue(program.stream().noneMatch(GarbageIota.class::isInstance),
                        referenceSpell.name() + " contains a garbage iota while parsing its embedded source");

                if (referenceSpell.name().equals("fisherman-loop"))
                    runFishermanSpell(helper, level, referenceSpell, program);
                else
                    runReferenceSpell(helper, player, target, referenceSpell, program, yjspIota, tickIota);
            }
            helper.succeed();
        } finally {
            ServerConfig.hexJitMode = oldMode;
            ServerConfig.hexJitThreshold = oldThreshold;
            ServerConfig.hexJitCompileActions = oldCompileActions;
            ServerConfig.hexJitSkipObservers = oldSkipObservers;
            ServerConfig.hexJitCoalesceDecorations = oldCoalesce;
            ServerConfig.hexJitBatchAddMotion = oldBatchMotion;
            ServerConfig.hexJitFastAddMotionArguments = oldFastMotion;
            ServerConfig.hexJitMemoAddMotionNormalization = oldMemoMotion;
            ServerConfig.hexJitFastTickAction = oldFastTickAction;
            ServerConfig.hexJitCombineTickSideEffects = oldCombineTickSideEffects;
            ServerConfig.hexJitCacheMaxOpCount = oldCacheMaxOpCount;
            ServerConfig.hexJitReuseTickUserData = oldReuseTickUserData;
            ServerConfig.hexJitBatchTickCounterWrites = oldBatchTickCounterWrites;
            ServerConfig.hexJitLoopFastTickCounter = oldLoopFastTickCounter;
            ServerConfig.hexJitCacheTickStackPop = oldCacheTickStackPop;
            ServerConfig.hexJitReuseTickMediaScan = oldReuseTickMediaScan;
            ServerConfig.hexJitFastHexOPMediaPool = oldFastHexOPMediaPool;
            ServerConfig.hexJitBatchTickPersonalMediaWrites = oldBatchTickPersonalMediaWrites;
            ServerConfig.hexJitCacheTickMediaHolder = oldCacheTickMediaHolder;
            ServerConfig.hexJitCacheTickMediaAvailability = oldCacheTickMediaAvailability;
            ServerConfig.hexJitDirectTickMediaPreflight = oldDirectTickMediaPreflight;
            ServerConfig.hexJitDirectTickMediaExtraction = oldDirectTickMediaExtraction;
            ServerConfig.hexJitCacheTickChunk = oldCacheTickChunk;
            ServerConfig.hexJitCacheTickRangeCheck = oldCacheTickRangeCheck;
            ServerConfig.hexJitCacheTickBlockEligibility = oldCacheTickBlockEligibility;
            ServerConfig.hexJitCacheBuddingAmethystState = oldCacheBuddingAmethystState;
            ServerConfig.hexJitLoopSpecialization = oldLoopSpecialization;
            ServerConfig.hexJitLoopTickDispatch = oldLoopTickDispatch;
            ServerConfig.hexJitLoopTickBatch = oldLoopTickBatch;
            ServerConfig.hexJitCollectMetrics = oldCollectMetrics;
            ServerConfig.hexJitCoalesceEvalSounds = oldCoalesceEvalSounds;
            ServerConfig.hexJitSkipEmptyPostExecution = oldSkipEmptyPostExecution;
            ServerConfig.hexJitFastSpendMediaTrigger = oldFastSpendMediaTrigger;
            ServerConfig.hexJitFastBuddingAmethystRandomTick = oldFastBuddingAmethystRandomTick;
            ServerConfig.hexJitCacheActionResourceKeys = oldCacheActionResourceKeys;
            ServerConfig.hexJitCacheActionTagMembership = oldCacheActionTagMembership;
            ServerConfig.hexJitCacheActionPrechecks = oldCacheActionPrechecks;
            ServerConfig.hexJitFastStackValidation = oldFastStack;
            ServerConfig.hexJitCacheStackMetrics = oldCacheMetrics;
            ServerConfig.hexJitCacheStackValidationResults = oldCacheValidationResults;
            ServerConfig.hexJitReuseFrameTail = oldReuseTail;
            ServerConfig.hexJitFastSpecialHandlerMath = oldFastMath;
            ServerConfig.hexJitFastSpecialHandlerLookup = oldFastLookup;
            ServerConfig.hexJitFastNumberLiterals = oldFastLiterals;
            ServerConfig.hexJitCacheNormalPatternLookup = oldPatternCache;
            ServerConfig.hexJitCachePerWorldPatternLookup = oldPerWorldPatternCache;
            HexJitRuntime.invalidate("restore post-test HexJIT settings");
        }
    }

    private static void runReferenceSpell(GameTestHelper helper,
                                          net.minecraft.server.level.ServerPlayer player,
                                          BlockPos target,
                                          ReferenceSpell referenceSpell,
                                          List<Iota> program,
                                          PatternIota yjspIota,
                                          PatternIota tickIota) throws Exception {
        ServerConfig.hexJitMode = ServerConfig.HexJitMode.OFF;
        HexJitRuntime.invalidate(referenceSpell.name() + " interpreter warmup");
        SpellRun interpretedWarmup = cast(helper, player, target, program, referenceSpell, yjspIota, tickIota);
        assertCompleted(helper, interpretedWarmup, target, referenceSpell, "OFF warmup");

        ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
        ServerConfig.hexJitCoalesceDecorations = true;
        HexJitRuntime.invalidate(referenceSpell.name() + " AUTO warmup");
        SpellRun jitWarmup = cast(helper, player, target, program, referenceSpell, yjspIota, tickIota);
        assertCompleted(helper, jitWarmup, target, referenceSpell, "AUTO warmup");
        assertEquivalent(helper, interpretedWarmup, jitWarmup, "warmup");

        SpellRun profiledOff = recordAndCast(helper, player, target, program, referenceSpell, yjspIota, tickIota,
                ServerConfig.HexJitMode.OFF, "off");
        SpellRun profiledAuto = recordAndCast(helper, player, target, program, referenceSpell, yjspIota, tickIota,
                ServerConfig.HexJitMode.AUTO, "auto");
        long diagnosticLoopTailHits = 0;
        long diagnosticLoopFrameHits = 0;
        long diagnosticLoopContinuationHits = 0;
        long diagnosticLoopTickDispatches = 0;
        long diagnosticLoopTickRuns = 0;
        long diagnosticLongestLoopTickRun = 0;
        long diagnosticLoopTickBatchFolds = 0;
        long diagnosticFoldedBuddingAmethystActions = 0;
        long diagnosticFoldedBuddingAmethystTickCalls = 0;
        long diagnosticTickSubstackSkips = 0;
        long diagnosticTickPrecheckHits = 0;
        long diagnosticEmptyPostExecutionSkips = 0;
        long diagnosticTickCounterWritesDeferred = 0;
        long diagnosticTickCounterWriteCommits = 0;
        long diagnosticTickMediaWritesDeferred = 0;
        long diagnosticTickRangeCheckCacheHits = 0;
        long diagnosticSpendMediaTriggerCacheHits = 0;
        if (referenceSpell.expectedTickCalls() > 0) {
            SpellRun metricsCast = castInModeWithMetrics(helper, player, target, program, referenceSpell,
                    yjspIota, tickIota, ServerConfig.HexJitMode.AUTO);
            assertCompleted(helper, metricsCast, target, referenceSpell, "metrics-on diagnostic cast");
            assertEquivalent(helper, profiledAuto, metricsCast, "metrics collection transparency");
            diagnosticLoopTailHits = ExecutionScope.lastFrameLoopTailHits();
            diagnosticLoopFrameHits = ExecutionScope.lastFrameLoopFrameHits();
            diagnosticLoopContinuationHits = ExecutionScope.lastFrameLoopContinuationHits();
            diagnosticLoopTickDispatches = ExecutionScope.lastLoopTickDispatches();
            diagnosticLoopTickRuns = ExecutionScope.lastLoopTickRuns();
            diagnosticLongestLoopTickRun = ExecutionScope.lastLongestLoopTickRun();
            diagnosticLoopTickBatchFolds = ExecutionScope.lastLoopTickBatchFolds();
            diagnosticFoldedBuddingAmethystActions = ExecutionScope.lastFoldedBuddingAmethystActions();
            diagnosticFoldedBuddingAmethystTickCalls =
                    ExecutionScope.lastFoldedBuddingAmethystTickCalls();
            diagnosticTickSubstackSkips = ExecutionScope.lastTickSubstackValidationSkips();
            diagnosticTickPrecheckHits = ExecutionScope.lastLoopTickActionPrecheckHits();
            diagnosticEmptyPostExecutionSkips = ExecutionScope.lastEmptyPostExecutionCallsSkipped();
            diagnosticTickCounterWritesDeferred = ExecutionScope.lastTickCounterWritesDeferred();
            diagnosticTickCounterWriteCommits = ExecutionScope.lastTickCounterWriteCommits();
            diagnosticTickMediaWritesDeferred = ExecutionScope.lastDeferredPersonalMediaWrites();
            diagnosticTickRangeCheckCacheHits = ExecutionScope.lastTickRangeCheckCacheHits();
            diagnosticSpendMediaTriggerCacheHits = ExecutionScope.lastSpendMediaTriggerCacheHits();
            helper.assertTrue(FastHexOPMediaPool.lastDirectExtractions() > 0,
                    "Tick media direct-pool path was not exercised");
            helper.assertTrue(diagnosticTickSubstackSkips == referenceSpell.expectedTickCalls(),
                    "Tick substack validation proof did not match the expected Tick count");
            helper.assertTrue(diagnosticTickPrecheckHits > 0
                            && diagnosticTickPrecheckHits < referenceSpell.expectedTickCalls(),
                    "Loop-specialized Tick action precheck cache did not reuse the cost modifier within Tick runs");
            helper.assertTrue(diagnosticTickRangeCheckCacheHits > 0,
                    "Budding Amethyst Tick range-check cache did not skip any repeated checks");
            helper.assertTrue(diagnosticLoopTickBatchFolds > 0,
                    "Loop Tick batch did not fold any consecutive Tick frames");
            helper.assertTrue(diagnosticFoldedBuddingAmethystActions > 0,
                    "Budding Amethyst loop action specialization did not run");
            System.out.println("HEXJIT_REPEATED_MEDIA preflights=" + ExecutionScope.lastRepeatedTickPreflights()
                    + " staffCallbacks=" + ExecutionScope.lastStaffTickCallbacks()
                    + " uniformTickSteps=" + ExecutionScope.lastUniformTickSteps()
                    + " quotedVectorCacheHits=" + ExecutionScope.lastQuotedVectorCacheHits()
                    + " quotedVectorFolds=" + ExecutionScope.lastQuotedVectorFolds()
                    + " pureQuoteRuns=" + ExecutionScope.lastPureQuoteRuns() + " pureReady=" + JitCompatibility.pureQuotesReady());
            System.out.println("HEXJIT_FRAME_DIAGNOSTICS " + ExecutionScope.lastFrameDiagnostics());
            System.out.println("HEXJIT_DIRECT_MEDIA spell=" + referenceSpell.name()
                    + " extractions=" + FastHexOPMediaPool.lastDirectExtractions());
            System.out.println("HEXJIT_TICK_SUBSTACK_VALIDATION spell=" + referenceSpell.name()
                    + " skipped=" + diagnosticTickSubstackSkips);
            System.out.println("HEXJIT_LOOP_TICK_PRECHECK spell=" + referenceSpell.name()
                    + " cacheHits=" + diagnosticTickPrecheckHits);
            System.out.println("HEXJIT_TICK_RANGE_CHECK spell=" + referenceSpell.name()
                    + " cacheHits=" + diagnosticTickRangeCheckCacheHits);
            System.out.println("HEXJIT_SPEND_MEDIA_TRIGGER_CACHE spell=" + referenceSpell.name()
                    + " cacheHits=" + diagnosticSpendMediaTriggerCacheHits);
        }
        assertEquivalent(helper, profiledOff, profiledAuto, "JFR profile");

        int steadySamples = Integer.getInteger("hexjit.steadySamples", 9);
        if (referenceSpell.expectedTickCalls() > 0 && steadySamples > 0) {
            for (int i = 0; i < 20; i++)
                castInMode(helper, player, target, program, referenceSpell, yjspIota, tickIota, ServerConfig.HexJitMode.AUTO);
            long[] steadyNanos = new long[steadySamples];
            long[] steadyCpuNanos = new long[steadySamples];
            var cpuBean = java.lang.management.ManagementFactory.getThreadMXBean();
            if (cpuBean.isCurrentThreadCpuTimeSupported()) cpuBean.setThreadCpuTimeEnabled(true);
            for (int i = 0; i < steadyNanos.length; i++) {
                long beforeCpu = cpuBean.getCurrentThreadCpuTime();
                var sample = castInMode(helper, player, target, program, referenceSpell, yjspIota, tickIota,
                        ServerConfig.HexJitMode.AUTO);
                steadyCpuNanos[i] = cpuBean.getCurrentThreadCpuTime() - beforeCpu;
                assertEquivalent(helper, profiledAuto, sample, "steady sample " + i);
                steadyNanos[i] = sample.elapsedNanos;
            }
            double steadyMs = median(steadyNanos) / 1_000_000.0;
            System.out.println("HEXJIT_STEADY spell=" + referenceSpell.name() + " warmups=20 medianMs=" + steadyMs
                    + " samplesMs=" + Arrays.stream(steadyNanos).mapToDouble(n -> n / 1_000_000.0).boxed().toList()
                    + " cpuMsIncludingSetup=" + Arrays.stream(steadyCpuNanos).mapToDouble(n -> n / 1_000_000.0).boxed().toList());
        }

        long[] offNanos = new long[3];
        long[] autoNanos = new long[3];
        SpellRun lastOff = profiledOff;
        SpellRun lastAuto = profiledAuto;
        for (int i = 0; i < offNanos.length; i++) {
            SpellRun off = castInMode(helper, player, target, program, referenceSpell, yjspIota, tickIota,
                    ServerConfig.HexJitMode.OFF);
            SpellRun auto = castInMode(helper, player, target, program, referenceSpell, yjspIota, tickIota,
                    ServerConfig.HexJitMode.AUTO);
            assertCompleted(helper, off, target, referenceSpell, "OFF sample " + i);
            assertCompleted(helper, auto, target, referenceSpell, "AUTO sample " + i);
            assertEquivalent(helper, off, auto, "sample " + i);
            offNanos[i] = off.elapsedNanos;
            autoNanos[i] = auto.elapsedNanos;
            lastOff = off;
            lastAuto = auto;
        }

        long[] precheckCacheOnNanos = new long[3];
        long[] precheckCacheOffNanos = new long[3];
        SpellRun autoWithoutPrecheckCache = lastAuto;
        for (int i = 0; i < precheckCacheOnNanos.length; i++) {
            ServerConfig.hexJitCacheActionPrechecks = true;
            SpellRun cached = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                    tickIota, ServerConfig.HexJitMode.AUTO);
            ServerConfig.hexJitCacheActionPrechecks = false;
            autoWithoutPrecheckCache = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                    tickIota, ServerConfig.HexJitMode.AUTO);
            assertEquivalent(helper, cached, autoWithoutPrecheckCache, "action precheck cache toggle " + i);
            precheckCacheOnNanos[i] = cached.elapsedNanos;
            precheckCacheOffNanos[i] = autoWithoutPrecheckCache.elapsedNanos;
        }
        ServerConfig.hexJitCacheActionPrechecks = false;

        long[] tickMediaScanOnNanos = new long[5];
        long[] tickMediaScanOffNanos = new long[5];
        for (int i = 0; i < tickMediaScanOnNanos.length; i++) {
            SpellRun reusedScan;
            SpellRun normalScan;
            if ((i & 1) == 0) {
                ServerConfig.hexJitReuseTickMediaScan = true;
                reusedScan = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitReuseTickMediaScan = false;
                normalScan = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            } else {
                ServerConfig.hexJitReuseTickMediaScan = false;
                normalScan = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitReuseTickMediaScan = true;
                reusedScan = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            }
            assertEquivalent(helper, reusedScan, normalScan, "tick media scan reuse toggle " + i);
            tickMediaScanOnNanos[i] = reusedScan.elapsedNanos;
            tickMediaScanOffNanos[i] = normalScan.elapsedNanos;
        }
        ServerConfig.hexJitReuseTickMediaScan = true;

        long[] tickUserDataReuseOnNanos = new long[5];
        long[] tickUserDataReuseOffNanos = new long[5];
        for (int i = 0; i < tickUserDataReuseOnNanos.length; i++) {
            SpellRun reusedUserData;
            SpellRun copiedUserData;
            if ((i & 1) == 0) {
                ServerConfig.hexJitReuseTickUserData = true;
                reusedUserData = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitReuseTickUserData = false;
                copiedUserData = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            } else {
                ServerConfig.hexJitReuseTickUserData = false;
                copiedUserData = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitReuseTickUserData = true;
                reusedUserData = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            }
            assertEquivalent(helper, reusedUserData, copiedUserData, "Tick user-data reuse toggle " + i);
            tickUserDataReuseOnNanos[i] = reusedUserData.elapsedNanos;
            tickUserDataReuseOffNanos[i] = copiedUserData.elapsedNanos;
        }
        ServerConfig.hexJitReuseTickUserData = true;

        long[] tickChunkCacheOnNanos = new long[5];
        long[] tickChunkCacheOffNanos = new long[5];
        for (int i = 0; i < tickChunkCacheOnNanos.length; i++) {
            SpellRun cachedChunk;
            SpellRun uncachedChunk;
            if ((i & 1) == 0) {
                ServerConfig.hexJitCacheTickChunk = true;
                cachedChunk = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitCacheTickChunk = false;
                uncachedChunk = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            } else {
                ServerConfig.hexJitCacheTickChunk = false;
                uncachedChunk = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitCacheTickChunk = true;
                cachedChunk = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            }
            assertEquivalent(helper, cachedChunk, uncachedChunk, "Tick chunk cache toggle " + i);
            tickChunkCacheOnNanos[i] = cachedChunk.elapsedNanos;
            tickChunkCacheOffNanos[i] = uncachedChunk.elapsedNanos;
        }
        ServerConfig.hexJitCacheTickChunk = true;

        long[] tickBlockEligibilityCacheOnNanos = new long[5];
        long[] tickBlockEligibilityCacheOffNanos = new long[5];
        for (int i = 0; i < tickBlockEligibilityCacheOnNanos.length; i++) {
            SpellRun cachedEligibility;
            SpellRun registryLookup;
            if ((i & 1) == 0) {
                ServerConfig.hexJitCacheTickBlockEligibility = true;
                cachedEligibility = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitCacheTickBlockEligibility = false;
                registryLookup = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            } else {
                ServerConfig.hexJitCacheTickBlockEligibility = false;
                registryLookup = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitCacheTickBlockEligibility = true;
                cachedEligibility = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            }
            assertEquivalent(helper, cachedEligibility, registryLookup, "Tick block eligibility cache toggle " + i);
            tickBlockEligibilityCacheOnNanos[i] = cachedEligibility.elapsedNanos;
            tickBlockEligibilityCacheOffNanos[i] = registryLookup.elapsedNanos;
        }
        ServerConfig.hexJitCacheTickBlockEligibility = false;

        long[] buddingStateCacheOnNanos = new long[3];
        long[] buddingStateCacheOffNanos = new long[3];
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < buddingStateCacheOnNanos.length; i++) {
                SpellRun cachedState;
                SpellRun liveState;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitCacheBuddingAmethystState = true;
                    cachedState = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitCacheBuddingAmethystState = false;
                    liveState = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitCacheBuddingAmethystState = false;
                    liveState = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitCacheBuddingAmethystState = true;
                    cachedState = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertEquivalent(helper, cachedState, liveState, "Budding Amethyst state cache toggle " + i);
                buddingStateCacheOnNanos[i] = cachedState.elapsedNanos;
                buddingStateCacheOffNanos[i] = liveState.elapsedNanos;
            }
        }
        ServerConfig.hexJitCacheBuddingAmethystState = true;

        long[] perWorldPatternCacheOnNanos = new long[5];
        long[] perWorldPatternCacheOffNanos = new long[5];
        for (int i = 0; i < perWorldPatternCacheOnNanos.length; i++) {
            SpellRun cachedPattern;
            SpellRun registryPatternLookup;
            if ((i & 1) == 0) {
                ServerConfig.hexJitCachePerWorldPatternLookup = true;
                cachedPattern = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitCachePerWorldPatternLookup = false;
                registryPatternLookup = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            } else {
                ServerConfig.hexJitCachePerWorldPatternLookup = false;
                registryPatternLookup = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitCachePerWorldPatternLookup = true;
                cachedPattern = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            }
            assertEquivalent(helper, cachedPattern, registryPatternLookup,
                    "per-world pattern and action cache toggle " + i);
            perWorldPatternCacheOnNanos[i] = cachedPattern.elapsedNanos;
            perWorldPatternCacheOffNanos[i] = registryPatternLookup.elapsedNanos;
        }
        ServerConfig.hexJitCachePerWorldPatternLookup = true;

        long[] normalPatternCacheOnNanos = new long[3];
        long[] normalPatternCacheOffNanos = new long[3];
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < normalPatternCacheOnNanos.length; i++) {
                SpellRun cachedMatch;
                SpellRun registryMatch;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitCacheNormalPatternLookup = true;
                    cachedMatch = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitCacheNormalPatternLookup = false;
                    registryMatch = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitCacheNormalPatternLookup = false;
                    registryMatch = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitCacheNormalPatternLookup = true;
                    cachedMatch = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertEquivalent(helper, cachedMatch, registryMatch, "Normal pattern match cache toggle " + i);
                normalPatternCacheOnNanos[i] = cachedMatch.elapsedNanos;
                normalPatternCacheOffNanos[i] = registryMatch.elapsedNanos;
            }
        }
        ServerConfig.hexJitCacheNormalPatternLookup = false;

        ServerConfig.hexJitCompileActions = true;
        for (int i = 0; i < 3; i++)
            castInMode(helper, player, target, program, referenceSpell, yjspIota,
                    tickIota, ServerConfig.HexJitMode.AUTO);
        ServerConfig.hexJitCompileActions = false;
        for (int i = 0; i < 3; i++)
            castInMode(helper, player, target, program, referenceSpell, yjspIota,
                    tickIota, ServerConfig.HexJitMode.AUTO);
        long[] compiledActionsOnNanos = new long[5];
        long[] compiledActionsOffNanos = new long[5];
        for (int i = 0; i < compiledActionsOnNanos.length; i++) {
            SpellRun compiledActions;
            SpellRun interpretedActions;
            if ((i & 1) == 0) {
                ServerConfig.hexJitCompileActions = true;
                compiledActions = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitCompileActions = false;
                interpretedActions = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            } else {
                ServerConfig.hexJitCompileActions = false;
                interpretedActions = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitCompileActions = true;
                compiledActions = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            }
            assertEquivalent(helper, compiledActions, interpretedActions, "compiled action toggle " + i);
            compiledActionsOnNanos[i] = compiledActions.elapsedNanos;
            compiledActionsOffNanos[i] = interpretedActions.elapsedNanos;
        }
        ServerConfig.hexJitCompileActions = false;

        long[] tickMediaHolderCacheOnNanos = new long[5];
        long[] tickMediaHolderCacheOffNanos = new long[5];
        for (int i = 0; i < tickMediaHolderCacheOnNanos.length; i++) {
            SpellRun cachedHolder;
            SpellRun resolvedHolder;
            if ((i & 1) == 0) {
                ServerConfig.hexJitCacheTickMediaHolder = true;
                cachedHolder = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitCacheTickMediaHolder = false;
                resolvedHolder = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            } else {
                ServerConfig.hexJitCacheTickMediaHolder = false;
                resolvedHolder = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitCacheTickMediaHolder = true;
                cachedHolder = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            }
            assertEquivalent(helper, cachedHolder, resolvedHolder, "Tick media holder cache toggle " + i);
            tickMediaHolderCacheOnNanos[i] = cachedHolder.elapsedNanos;
            tickMediaHolderCacheOffNanos[i] = resolvedHolder.elapsedNanos;
        }
        ServerConfig.hexJitCacheTickMediaHolder = true;

        long[] tickMediaAvailabilityCacheOnNanos = new long[5];
        long[] tickMediaAvailabilityCacheOffNanos = new long[5];
        for (int i = 0; i < tickMediaAvailabilityCacheOnNanos.length; i++) {
            SpellRun cachedAvailability;
            SpellRun queriedAvailability;
            if ((i & 1) == 0) {
                ServerConfig.hexJitCacheTickMediaAvailability = true;
                cachedAvailability = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitCacheTickMediaAvailability = false;
                queriedAvailability = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            } else {
                ServerConfig.hexJitCacheTickMediaAvailability = false;
                queriedAvailability = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitCacheTickMediaAvailability = true;
                cachedAvailability = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            }
            assertEquivalent(helper, cachedAvailability, queriedAvailability,
                    "Tick media availability cache toggle " + i);
            tickMediaAvailabilityCacheOnNanos[i] = cachedAvailability.elapsedNanos;
            tickMediaAvailabilityCacheOffNanos[i] = queriedAvailability.elapsedNanos;
        }
        ServerConfig.hexJitCacheTickMediaAvailability = true;

        long[] directTickMediaPreflightOnNanos = new long[5];
        long[] directTickMediaPreflightOffNanos = new long[5];
        for (int i = 0; i < directTickMediaPreflightOnNanos.length; i++) {
            SpellRun directPreflight;
            SpellRun environmentPreflight;
            if ((i & 1) == 0) {
                ServerConfig.hexJitDirectTickMediaPreflight = true;
                directPreflight = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitDirectTickMediaPreflight = false;
                environmentPreflight = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            } else {
                ServerConfig.hexJitDirectTickMediaPreflight = false;
                environmentPreflight = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
                ServerConfig.hexJitDirectTickMediaPreflight = true;
                directPreflight = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                        tickIota, ServerConfig.HexJitMode.AUTO);
            }
            assertEquivalent(helper, directPreflight, environmentPreflight,
                    "Tick direct media preflight toggle " + i);
            directTickMediaPreflightOnNanos[i] = directPreflight.elapsedNanos;
            directTickMediaPreflightOffNanos[i] = environmentPreflight.elapsedNanos;
        }
        ServerConfig.hexJitDirectTickMediaPreflight = true;

        long[] directTickMediaExtractionOnNanos = new long[3];
        long[] directTickMediaExtractionOffNanos = new long[3];
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < directTickMediaExtractionOnNanos.length; i++) {
                SpellRun directExtraction;
                SpellRun environmentExtraction;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitDirectTickMediaExtraction = true;
                    directExtraction = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitDirectTickMediaExtraction = false;
                    environmentExtraction = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitDirectTickMediaExtraction = false;
                    environmentExtraction = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitDirectTickMediaExtraction = true;
                    directExtraction = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertEquivalent(helper, directExtraction, environmentExtraction,
                        "Tick direct media extraction toggle " + i);
                directTickMediaExtractionOnNanos[i] = directExtraction.elapsedNanos;
                directTickMediaExtractionOffNanos[i] = environmentExtraction.elapsedNanos;
            }
        }
        ServerConfig.hexJitDirectTickMediaExtraction = true;

        long[] maxOpCountCacheOnNanos = new long[5];
        long[] maxOpCountCacheOffNanos = new long[5];
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < maxOpCountCacheOnNanos.length; i++) {
                SpellRun cachedLimit;
                SpellRun readLimit;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitCacheMaxOpCount = true;
                    cachedLimit = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitCacheMaxOpCount = false;
                    readLimit = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitCacheMaxOpCount = false;
                    readLimit = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitCacheMaxOpCount = true;
                    cachedLimit = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertEquivalent(helper, cachedLimit, readLimit, "maxOpCount cache toggle " + i);
                maxOpCountCacheOnNanos[i] = cachedLimit.elapsedNanos;
                maxOpCountCacheOffNanos[i] = readLimit.elapsedNanos;
            }
        }
        ServerConfig.hexJitCacheMaxOpCount = true;

        long[] loopSpecializationOnNanos = new long[3];
        long[] loopSpecializationOffNanos = new long[3];
        long[] loopSpecializationTailHits = {diagnosticLoopTailHits, 0, 0};
        long[] loopSpecializationFrameHits = {diagnosticLoopFrameHits, 0, 0};
        long[] loopSpecializationContinuationHits = {diagnosticLoopContinuationHits, 0, 0};
        long[] loopSpecializationTickDispatches = {diagnosticLoopTickDispatches, 0, 0};
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < loopSpecializationOnNanos.length; i++) {
                SpellRun specialized;
                SpellRun interpretedFrames;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitLoopSpecialization = true;
                    specialized = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitLoopSpecialization = false;
                    interpretedFrames = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitLoopSpecialization = false;
                    interpretedFrames = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitLoopSpecialization = true;
                    specialized = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertEquivalent(helper, specialized, interpretedFrames, "FrameEvaluate loop specialization " + i);
                loopSpecializationOnNanos[i] = specialized.elapsedNanos;
                loopSpecializationOffNanos[i] = interpretedFrames.elapsedNanos;
            }
        }
        ServerConfig.hexJitLoopSpecialization = true;
        if (referenceSpell.expectedTickCalls() > 0) {
            helper.assertTrue(Arrays.stream(loopSpecializationFrameHits).sum() > 0,
                    "Loop specialization did not reuse any immutable evaluation frames");
            helper.assertTrue(Arrays.stream(loopSpecializationTickDispatches).sum() > 0,
                    "Loop specialization did not directly dispatch any cached Tick patterns");
        }

        long[] loopTickDispatchOnNanos = new long[3];
        long[] loopTickDispatchOffNanos = new long[3];
        long[] loopTickDispatchHits = {diagnosticLoopTickDispatches, 0, 0};
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < loopTickDispatchOnNanos.length; i++) {
                SpellRun direct;
                SpellRun patternDispatch;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitLoopTickDispatch = true;
                    direct = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitLoopTickDispatch = false;
                    patternDispatch = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitLoopTickDispatch = false;
                    patternDispatch = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitLoopTickDispatch = true;
                    direct = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertEquivalent(helper, direct, patternDispatch, "Loop Tick dispatch toggle " + i);
                loopTickDispatchOnNanos[i] = direct.elapsedNanos;
                loopTickDispatchOffNanos[i] = patternDispatch.elapsedNanos;
            }
        }
        ServerConfig.hexJitLoopTickDispatch = true;
        if (referenceSpell.expectedTickCalls() > 0) {
            helper.assertTrue(Arrays.stream(loopTickDispatchHits).sum() > 0,
                    "Loop specialization did not directly dispatch any cached Tick patterns");
        }

        long[] loopTickBatchOnNanos = new long[3];
        long[] loopTickBatchOffNanos = new long[3];
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < loopTickBatchOnNanos.length; i++) {
                SpellRun batched;
                SpellRun unbatched;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitLoopTickBatch = true;
                    batched = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitLoopTickBatch = false;
                    unbatched = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitLoopTickBatch = false;
                    unbatched = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitLoopTickBatch = true;
                    batched = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertEquivalent(helper, batched, unbatched, "Loop Tick batch toggle " + i);
                loopTickBatchOnNanos[i] = batched.elapsedNanos;
                loopTickBatchOffNanos[i] = unbatched.elapsedNanos;
            }
        }
        ServerConfig.hexJitLoopTickBatch = true;

        long[] evalSoundCoalesceOnNanos = new long[5];
        long[] evalSoundCoalesceOffNanos = new long[5];
        long[] evalSoundCopiesSkipped = new long[5];
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < evalSoundCoalesceOnNanos.length; i++) {
                SpellRun coalesced;
                SpellRun upstream;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitCoalesceEvalSounds = true;
                    coalesced = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitCoalesceEvalSounds = false;
                    upstream = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitCoalesceEvalSounds = false;
                    upstream = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitCoalesceEvalSounds = true;
                    coalesced = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertEquivalent(helper, coalesced, upstream, "Unobserved eval sound coalescing " + i);
                evalSoundCoalesceOnNanos[i] = coalesced.elapsedNanos;
                evalSoundCoalesceOffNanos[i] = upstream.elapsedNanos;
            }
        }
        ServerConfig.hexJitCoalesceEvalSounds = true;
        if (referenceSpell.expectedTickCalls() > 0) {
            castInModeWithMetrics(helper, player, target, program, referenceSpell, yjspIota,
                    tickIota, ServerConfig.HexJitMode.AUTO);
            evalSoundCopiesSkipped[0] = ExecutionScope.lastEvalSoundCopiesSkipped();
        }
        if (referenceSpell.expectedTickCalls() > 0) {
            helper.assertTrue(Arrays.stream(evalSoundCopiesSkipped).sum() == 0,
                    "Staff callbacks must receive the correct metacast sound");
        }

        long[] emptyPostExecutionSkipOnNanos = new long[5];
        long[] emptyPostExecutionSkipOffNanos = new long[5];
        long[] emptyPostExecutionCallsSkipped = {diagnosticEmptyPostExecutionSkips, 0, 0, 0, 0};
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < emptyPostExecutionSkipOnNanos.length; i++) {
                SpellRun skipped;
                SpellRun called;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitSkipEmptyPostExecution = true;
                    skipped = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitSkipEmptyPostExecution = false;
                    called = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitSkipEmptyPostExecution = false;
                    called = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitSkipEmptyPostExecution = true;
                    skipped = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertEquivalent(helper, skipped, called, "Skipping empty post-execution " + i);
                emptyPostExecutionSkipOnNanos[i] = skipped.elapsedNanos;
                emptyPostExecutionSkipOffNanos[i] = called.elapsedNanos;
            }
        }
        ServerConfig.hexJitSkipEmptyPostExecution = true;
        if (referenceSpell.expectedTickCalls() > 0) {
            helper.assertTrue(Arrays.stream(emptyPostExecutionCallsSkipped).sum() == 0,
                    "Staff postExecution is not empty and must never be skipped");
        }

        long[] combineTickSideEffectsOnNanos = new long[5];
        long[] combineTickSideEffectsOffNanos = new long[5];
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < combineTickSideEffectsOnNanos.length; i++) {
                SpellRun combined;
                SpellRun separate;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitCombineTickSideEffects = true;
                    combined = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitCombineTickSideEffects = false;
                    separate = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitCombineTickSideEffects = false;
                    separate = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitCombineTickSideEffects = true;
                    combined = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertEquivalent(helper, combined, separate, "Combining Tick media and attempt effects " + i);
                combineTickSideEffectsOnNanos[i] = combined.elapsedNanos;
                combineTickSideEffectsOffNanos[i] = separate.elapsedNanos;
            }
        }
        ServerConfig.hexJitCombineTickSideEffects = true;

        long[] buddingGateOnNanos = new long[5];
        long[] buddingGateOffNanos = new long[5];
        long[] buddingGateTickCalls = new long[5];
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < buddingGateOnNanos.length; i++) {
                SpellRun gated;
                SpellRun stockGate;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitFastBuddingAmethystRandomTick = true;
                    gated = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitFastBuddingAmethystRandomTick = false;
                    stockGate = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitFastBuddingAmethystRandomTick = false;
                    stockGate = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitFastBuddingAmethystRandomTick = true;
                    gated = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertCompleted(helper, gated, target, referenceSpell, "Budding random gate on " + i);
                assertCompleted(helper, stockGate, target, referenceSpell, "Budding random gate off " + i);
                helper.assertTrue(gated.image.equals(stockGate.image)
                                && gated.resolution.equals(stockGate.resolution)
                                && gated.opsConsumed == stockGate.opsConsumed
                                && gated.personalMedia == stockGate.personalMedia
                                && Float.compare(gated.playerHealth, stockGate.playerHealth) == 0,
                        "Budding random gate changed the cast result apart from world-side RNG outcomes: gated="
                                + gated + ", stock=" + stockGate);
                buddingGateOnNanos[i] = gated.elapsedNanos;
                buddingGateOffNanos[i] = stockGate.elapsedNanos;
            }
        }
        ServerConfig.hexJitFastBuddingAmethystRandomTick = false;
        if (referenceSpell.expectedTickCalls() > 0) {
            ServerConfig.hexJitFastBuddingAmethystRandomTick = true;
            castInModeWithMetrics(helper, player, target, program, referenceSpell, yjspIota,
                    tickIota, ServerConfig.HexJitMode.AUTO);
            buddingGateTickCalls[0] = ExecutionScope.lastBuddingAmethystRandomTickCalls();
            helper.assertTrue(buddingGateTickCalls[0] > 20 && buddingGateTickCalls[0] < 125,
                    "Budding geometric random gate produced implausible event count: "
                            + buddingGateTickCalls[0]);
            ServerConfig.hexJitFastBuddingAmethystRandomTick = false;
        }

        long[] spendMediaTriggerOnNanos = new long[5];
        long[] spendMediaTriggerOffNanos = new long[5];
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < spendMediaTriggerOnNanos.length; i++) {
                SpellRun optimizedTrigger;
                SpellRun upstreamTrigger;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitFastSpendMediaTrigger = true;
                    optimizedTrigger = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitFastSpendMediaTrigger = false;
                    upstreamTrigger = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitFastSpendMediaTrigger = false;
                    upstreamTrigger = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitFastSpendMediaTrigger = true;
                    optimizedTrigger = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertEquivalent(helper, upstreamTrigger, optimizedTrigger, "Spend Media trigger specialization " + i);
                spendMediaTriggerOnNanos[i] = optimizedTrigger.elapsedNanos;
                spendMediaTriggerOffNanos[i] = upstreamTrigger.elapsedNanos;
            }
        }
        ServerConfig.hexJitFastSpendMediaTrigger = true;

        long[] tickMediaWritesBatchedOnNanos = new long[5];
        long[] tickMediaWritesBatchedOffNanos = new long[5];
        long[] tickMediaWritesDeferred = {diagnosticTickMediaWritesDeferred, 0, 0, 0, 0};
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < tickMediaWritesBatchedOnNanos.length; i++) {
                SpellRun batched;
                SpellRun immediate;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitBatchTickPersonalMediaWrites = true;
                    batched = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitBatchTickPersonalMediaWrites = false;
                    immediate = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitBatchTickPersonalMediaWrites = false;
                    immediate = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitBatchTickPersonalMediaWrites = true;
                    batched = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertCompleted(helper, batched, target, referenceSpell, "Batch Tick personal-media writes on " + i);
                assertCompleted(helper, immediate, target, referenceSpell, "Batch Tick personal-media writes off " + i);
                assertEquivalent(helper, immediate, batched, "Batch Tick personal-media writes " + i);
                tickMediaWritesBatchedOnNanos[i] = batched.elapsedNanos;
                tickMediaWritesBatchedOffNanos[i] = immediate.elapsedNanos;
            }
        }
        ServerConfig.hexJitBatchTickPersonalMediaWrites = true;
        if (referenceSpell.expectedTickCalls() > 0)
            helper.assertTrue(Arrays.stream(tickMediaWritesDeferred).sum() > 0,
                    "Tick personal-media write batching did not defer any HexOP holder writes");

        long[] tickCounterWritesBatchedOnNanos = new long[5];
        long[] tickCounterWritesBatchedOffNanos = new long[5];
        long[] tickCounterWritesDeferred = {diagnosticTickCounterWritesDeferred, 0, 0, 0, 0};
        long[] tickCounterWriteCommits = {diagnosticTickCounterWriteCommits, 0, 0, 0, 0};
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < tickCounterWritesBatchedOnNanos.length; i++) {
                SpellRun batched;
                SpellRun immediate;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitBatchTickCounterWrites = true;
                    batched = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitBatchTickCounterWrites = false;
                    immediate = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitBatchTickCounterWrites = false;
                    immediate = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitBatchTickCounterWrites = true;
                    batched = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertCompleted(helper, batched, target, referenceSpell, "Batch Tick counter writes on " + i);
                assertCompleted(helper, immediate, target, referenceSpell, "Batch Tick counter writes off " + i);
                assertEquivalent(helper, immediate, batched, "Batch Tick counter writes " + i);
                tickCounterWritesBatchedOnNanos[i] = batched.elapsedNanos;
                tickCounterWritesBatchedOffNanos[i] = immediate.elapsedNanos;
            }
        }
        if (referenceSpell.expectedTickCalls() > 0
                && Arrays.stream(tickCounterWritesDeferred).sum() == 0) {
            ServerConfig.hexJitBatchTickCounterWrites = true;
            SpellRun counterBatchDiagnostic = castInModeWithMetrics(helper, player, target, program,
                    referenceSpell, yjspIota, tickIota, ServerConfig.HexJitMode.AUTO);
            assertCompleted(helper, counterBatchDiagnostic, target, referenceSpell,
                    "Tick counter-write batching diagnostic");
            tickCounterWritesDeferred[0] = ExecutionScope.lastTickCounterWritesDeferred();
            tickCounterWriteCommits[0] = ExecutionScope.lastTickCounterWriteCommits();
        }
        ServerConfig.hexJitBatchTickCounterWrites = false;
        if (referenceSpell.expectedTickCalls() > 0) {
            helper.assertTrue(Arrays.stream(tickCounterWritesDeferred).sum() > 0,
                    "Tick counter write batching did not defer any counter updates");
            helper.assertTrue(Arrays.stream(tickCounterWriteCommits).sum()
                            < Arrays.stream(tickCounterWritesDeferred).sum(),
                    "Tick counter write batching did not combine multiple updates into fewer NBT commits");
        }

        long[] loopFastTickCounterOnNanos = new long[5];
        long[] loopFastTickCounterOffNanos = new long[5];
        long[] loopFastTickCounterUpdates = new long[5];
        if (referenceSpell.expectedTickCalls() > 0) {
            ServerConfig.hexJitBatchTickCounterWrites = false;
            for (int i = 0; i < loopFastTickCounterOnNanos.length; i++) {
                SpellRun fast;
                SpellRun ordinary;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitLoopFastTickCounter = true;
                    fast = castInModeWithMetrics(helper, player, target, program, referenceSpell,
                            yjspIota, tickIota, ServerConfig.HexJitMode.AUTO);
                    loopFastTickCounterUpdates[i] = ExecutionScope.lastFoldedTickCounterFastUpdates();
                    ServerConfig.hexJitLoopFastTickCounter = false;
                    ordinary = castInMode(helper, player, target, program, referenceSpell,
                            yjspIota, tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitLoopFastTickCounter = false;
                    ordinary = castInMode(helper, player, target, program, referenceSpell,
                            yjspIota, tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitLoopFastTickCounter = true;
                    fast = castInModeWithMetrics(helper, player, target, program, referenceSpell,
                            yjspIota, tickIota, ServerConfig.HexJitMode.AUTO);
                    loopFastTickCounterUpdates[i] = ExecutionScope.lastFoldedTickCounterFastUpdates();
                }
                assertCompleted(helper, fast, target, referenceSpell, "Loop fast Tick counter on " + i);
                assertCompleted(helper, ordinary, target, referenceSpell, "Loop fast Tick counter off " + i);
                assertEquivalent(helper, ordinary, fast, "Loop fast Tick counter " + i);
                loopFastTickCounterOnNanos[i] = fast.elapsedNanos;
                loopFastTickCounterOffNanos[i] = ordinary.elapsedNanos;
            }
            ServerConfig.hexJitBatchTickCounterWrites = false;
            ServerConfig.hexJitLoopFastTickCounter = true;
            helper.assertTrue(Arrays.stream(loopFastTickCounterUpdates).sum() > 0,
                    "Loop fast Tick counter path was not used");
        }

        long tickStackPopCacheOnNanos = 0;
        long tickStackPopCacheOffNanos = 0;
        long tickStackPopCacheHits = 0;
        if (referenceSpell.expectedTickCalls() > 0) {
            ServerConfig.hexJitCacheTickStackPop = true;
            SpellRun cachedStackPop = castInModeWithMetrics(helper, player, target, program, referenceSpell,
                    yjspIota, tickIota, ServerConfig.HexJitMode.AUTO);
            tickStackPopCacheHits = ExecutionScope.lastTickStackPopCacheHits();
            ServerConfig.hexJitCacheTickStackPop = false;
            SpellRun ordinaryStackPop = castInMode(helper, player, target, program, referenceSpell,
                    yjspIota, tickIota, ServerConfig.HexJitMode.AUTO);
            assertCompleted(helper, cachedStackPop, target, referenceSpell, "Tick stack-pop cache on");
            assertCompleted(helper, ordinaryStackPop, target, referenceSpell, "Tick stack-pop cache off");
            assertEquivalent(helper, ordinaryStackPop, cachedStackPop, "Tick stack-pop cache");
            tickStackPopCacheOnNanos = cachedStackPop.elapsedNanos;
            tickStackPopCacheOffNanos = ordinaryStackPop.elapsedNanos;
        }
        ServerConfig.hexJitCacheTickStackPop = false;

        long[] tickRangeCheckCacheOnNanos = new long[3];
        long[] tickRangeCheckCacheOffNanos = new long[3];
        if (referenceSpell.expectedTickCalls() > 0) {
            for (int i = 0; i < tickRangeCheckCacheOnNanos.length; i++) {
                SpellRun cachedRange;
                SpellRun checkedRange;
                if ((i & 1) == 0) {
                    ServerConfig.hexJitCacheTickRangeCheck = true;
                    cachedRange = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitCacheTickRangeCheck = false;
                    checkedRange = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    ServerConfig.hexJitCacheTickRangeCheck = false;
                    checkedRange = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    ServerConfig.hexJitCacheTickRangeCheck = true;
                    cachedRange = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertCompleted(helper, cachedRange, target, referenceSpell, "Tick range-check cache on " + i);
                assertCompleted(helper, checkedRange, target, referenceSpell, "Tick range-check cache off " + i);
                assertEquivalent(helper, checkedRange, cachedRange, "Tick range-check cache " + i);
                tickRangeCheckCacheOnNanos[i] = cachedRange.elapsedNanos;
                tickRangeCheckCacheOffNanos[i] = checkedRange.elapsedNanos;
            }
        }
        ServerConfig.hexJitCacheTickRangeCheck = true;

        if (referenceSpell.expectedTickCalls() > 0) {
            for (String feature : new String[] {
                    "hexJitSkipObservers", "hexJitCoalesceDecorations", "hexJitCoalesceStaffSounds", "hexJitFastTickAction",
                    "hexJitFastHexOPMediaPool", "hexJitCacheActionResourceKeys",
                    "hexJitCacheActionTagMembership", "hexJitFastStackValidation",
                    "hexJitCacheStackMetrics", "hexJitCacheStackValidationResults",
                    "hexJitReuseFrameTail", "hexJitFastSpecialHandlerMath",
                    "hexJitFastSpecialHandlerLookup", "hexJitFastNumberLiterals"
            }) {
                FeatureAblation ablation = measureReferenceAblation(helper, player, target, program,
                        referenceSpell, yjspIota, tickIota, feature, offNanos);
                System.out.println("HEXJIT_ABLATION spell=" + referenceSpell.name()
                        + " flag=" + feature
                        + " enabledAUTOms=" + median(ablation.enabledNanos()) / 1_000_000.0
                        + " disabledAUTOms=" + median(ablation.disabledNanos()) / 1_000_000.0
                        + " enabledRatio=" + median(ablation.enabledNanos()) / (double) median(offNanos)
                        + " disabledRatio=" + median(ablation.disabledNanos()) / (double) median(offNanos));
            }
        }

        System.out.println("HEXJIT_REFERENCE_SPELL name=" + referenceSpell.name()
                + " programIotas=" + program.size()
                + " tickCalls=" + lastAuto.storedTicks
                + " yjspCalls=" + interpretedWarmup.yjspCalls
                + " ops=" + lastAuto.opsConsumed
                + " OFF_medianMs=" + median(offNanos) / 1_000_000.0
                + " AUTO_medianMs=" + median(autoNanos) / 1_000_000.0
                + " AUTO_ratio=" + median(autoNanos) / (double) median(offNanos)
                + " actionPrecheckCacheOnMs=" + median(precheckCacheOnNanos) / 1_000_000.0
                + " actionPrecheckCacheOffMs=" + median(precheckCacheOffNanos) / 1_000_000.0
                + " tickMediaScanReuseOnMs=" + median(tickMediaScanOnNanos) / 1_000_000.0
                + " tickMediaScanReuseOffMs=" + median(tickMediaScanOffNanos) / 1_000_000.0
                + " tickUserDataReuseOnMs=" + median(tickUserDataReuseOnNanos) / 1_000_000.0
                + " tickUserDataReuseOffMs=" + median(tickUserDataReuseOffNanos) / 1_000_000.0
                + " tickChunkCacheOnMs=" + median(tickChunkCacheOnNanos) / 1_000_000.0
                + " tickChunkCacheOffMs=" + median(tickChunkCacheOffNanos) / 1_000_000.0
                + " tickBlockEligibilityCacheOnMs=" + median(tickBlockEligibilityCacheOnNanos) / 1_000_000.0
                + " tickBlockEligibilityCacheOffMs=" + median(tickBlockEligibilityCacheOffNanos) / 1_000_000.0
                + " buddingStateCacheOnMs=" + median(buddingStateCacheOnNanos) / 1_000_000.0
                + " buddingStateCacheOffMs=" + median(buddingStateCacheOffNanos) / 1_000_000.0
                + " perWorldPatternCacheOnMs=" + median(perWorldPatternCacheOnNanos) / 1_000_000.0
                + " perWorldPatternCacheOffMs=" + median(perWorldPatternCacheOffNanos) / 1_000_000.0
                + " normalPatternCacheOnMs=" + median(normalPatternCacheOnNanos) / 1_000_000.0
                + " normalPatternCacheOffMs=" + median(normalPatternCacheOffNanos) / 1_000_000.0
                + " compileActionsOnMs=" + median(compiledActionsOnNanos) / 1_000_000.0
                + " compileActionsOffMs=" + median(compiledActionsOffNanos) / 1_000_000.0
                + " tickMediaHolderCacheOnMs=" + median(tickMediaHolderCacheOnNanos) / 1_000_000.0
                + " tickMediaHolderCacheOffMs=" + median(tickMediaHolderCacheOffNanos) / 1_000_000.0
                + " tickMediaAvailabilityCacheOnMs=" + median(tickMediaAvailabilityCacheOnNanos) / 1_000_000.0
                + " tickMediaAvailabilityCacheOffMs=" + median(tickMediaAvailabilityCacheOffNanos) / 1_000_000.0
                + " directTickMediaPreflightOnMs=" + median(directTickMediaPreflightOnNanos) / 1_000_000.0
                + " directTickMediaPreflightOffMs=" + median(directTickMediaPreflightOffNanos) / 1_000_000.0
                + " directTickMediaExtractionOnMs=" + median(directTickMediaExtractionOnNanos) / 1_000_000.0
                + " directTickMediaExtractionOffMs=" + median(directTickMediaExtractionOffNanos) / 1_000_000.0
                + " maxOpCountCacheOnMs=" + median(maxOpCountCacheOnNanos) / 1_000_000.0
                + " maxOpCountCacheOffMs=" + median(maxOpCountCacheOffNanos) / 1_000_000.0
                + " loopSpecializationOnMs=" + median(loopSpecializationOnNanos) / 1_000_000.0
                + " loopSpecializationOffMs=" + median(loopSpecializationOffNanos) / 1_000_000.0
                + " loopTailCacheHits=" + Arrays.stream(loopSpecializationTailHits).sum()
                + " loopFrameCacheHits=" + Arrays.stream(loopSpecializationFrameHits).sum()
                + " loopContinuationCacheHits=" + Arrays.stream(loopSpecializationContinuationHits).sum()
                + " loopTickDispatches=" + Arrays.stream(loopSpecializationTickDispatches).sum()
                + " loopTickRuns=" + diagnosticLoopTickRuns
                + " longestLoopTickRun=" + diagnosticLongestLoopTickRun
                + " loopTickBatchFolds=" + diagnosticLoopTickBatchFolds
                + " foldedBuddingAmethystActions=" + diagnosticFoldedBuddingAmethystActions
                + " foldedBuddingAmethystTickCalls=" + diagnosticFoldedBuddingAmethystTickCalls
                + " loopTickDispatchOnMs=" + median(loopTickDispatchOnNanos) / 1_000_000.0
                + " loopTickDispatchOffMs=" + median(loopTickDispatchOffNanos) / 1_000_000.0
                + " loopTickDispatchHits=" + Arrays.stream(loopTickDispatchHits).sum()
                + " loopTickBatchOnMs=" + median(loopTickBatchOnNanos) / 1_000_000.0
                + " loopTickBatchOffMs=" + median(loopTickBatchOffNanos) / 1_000_000.0
                + " evalSoundCoalesceOnMs=" + median(evalSoundCoalesceOnNanos) / 1_000_000.0
                + " evalSoundCoalesceOffMs=" + median(evalSoundCoalesceOffNanos) / 1_000_000.0
                + " evalSoundCopiesSkipped=" + Arrays.stream(evalSoundCopiesSkipped).sum()
                + " emptyPostExecutionSkipOnMs=" + median(emptyPostExecutionSkipOnNanos) / 1_000_000.0
                + " emptyPostExecutionSkipOffMs=" + median(emptyPostExecutionSkipOffNanos) / 1_000_000.0
                + " emptyPostExecutionCallsSkipped=" + Arrays.stream(emptyPostExecutionCallsSkipped).sum()
                + " combineTickSideEffectsOnMs=" + median(combineTickSideEffectsOnNanos) / 1_000_000.0
                + " combineTickSideEffectsOffMs=" + median(combineTickSideEffectsOffNanos) / 1_000_000.0
                + " buddingRandomGateOnMs=" + median(buddingGateOnNanos) / 1_000_000.0
                + " buddingRandomGateOffMs=" + median(buddingGateOffNanos) / 1_000_000.0
                + " buddingRandomTickCalls=" + Arrays.toString(buddingGateTickCalls)
                + " spendMediaTriggerOnMs=" + median(spendMediaTriggerOnNanos) / 1_000_000.0
                + " spendMediaTriggerOffMs=" + median(spendMediaTriggerOffNanos) / 1_000_000.0
                + " spendMediaTriggerCacheHits=" + diagnosticSpendMediaTriggerCacheHits
                + " tickMediaWriteBatchOnMs=" + median(tickMediaWritesBatchedOnNanos) / 1_000_000.0
                + " tickMediaWriteBatchOffMs=" + median(tickMediaWritesBatchedOffNanos) / 1_000_000.0
                + " tickMediaWritesDeferred=" + Arrays.stream(tickMediaWritesDeferred).sum()
                + " tickCounterWriteBatchOnMs=" + median(tickCounterWritesBatchedOnNanos) / 1_000_000.0
                + " tickCounterWriteBatchOffMs=" + median(tickCounterWritesBatchedOffNanos) / 1_000_000.0
                + " tickCounterWritesDeferred=" + Arrays.stream(tickCounterWritesDeferred).sum()
                + " tickCounterWriteCommits=" + Arrays.stream(tickCounterWriteCommits).sum()
                + " loopFastTickCounterOnMs=" + median(loopFastTickCounterOnNanos) / 1_000_000.0
                + " loopFastTickCounterOffMs=" + median(loopFastTickCounterOffNanos) / 1_000_000.0
                + " loopFastTickCounterUpdates=" + Arrays.stream(loopFastTickCounterUpdates).sum()
                + " tickStackPopCacheOnMs=" + tickStackPopCacheOnNanos / 1_000_000.0
                + " tickStackPopCacheOffMs=" + tickStackPopCacheOffNanos / 1_000_000.0
                + " tickStackPopCacheHits=" + tickStackPopCacheHits
                + " tickRangeCheckCacheOnMs=" + median(tickRangeCheckCacheOnNanos) / 1_000_000.0
                + " tickRangeCheckCacheOffMs=" + median(tickRangeCheckCacheOffNanos) / 1_000_000.0
                + " tickRangeCheckCacheHits=" + diagnosticTickRangeCheckCacheHits
                + " OFF_particles=" + interpretedWarmup.particleCalls
                + " AUTO_particles=" + jitWarmup.particleCalls
                + " status=" + HexJitRuntime.status());
    }

    private static FeatureAblation measureReferenceAblation(
            GameTestHelper helper,
            net.minecraft.server.level.ServerPlayer player,
            BlockPos target,
            List<Iota> program,
            ReferenceSpell referenceSpell,
            PatternIota yjspIota,
            PatternIota tickIota,
            String fieldName,
            long[] offNanos) throws ReflectiveOperationException {
        java.lang.reflect.Field field = ServerConfig.class.getField(fieldName);
        boolean original = field.getBoolean(null);
        int samples = fieldName.equals("hexJitCoalesceStaffSounds")
                ? Math.max(1, Integer.getInteger("hexjit.soundAblationSamples", 5)) : 5;
        long[] enabledNanos = new long[samples];
        long[] disabledNanos = new long[samples];
        try {
            for (int i = 0; i < enabledNanos.length; i++) {
                SpellRun enabled;
                SpellRun disabled;
                if ((i & 1) == 0) {
                    field.setBoolean(null, true);
                    enabled = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    field.setBoolean(null, false);
                    disabled = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                } else {
                    field.setBoolean(null, false);
                    disabled = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    field.setBoolean(null, true);
                    enabled = castInMode(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                }
                assertCompleted(helper, enabled, target, referenceSpell,
                        fieldName + " enabled ablation " + i);
                assertCompleted(helper, disabled, target, referenceSpell,
                        fieldName + " disabled ablation " + i);
                assertEquivalent(helper, enabled, disabled, fieldName + " ablation " + i);
                enabledNanos[i] = enabled.elapsedNanos();
                disabledNanos[i] = disabled.elapsedNanos();
            }
            if (fieldName.equals("hexJitCoalesceStaffSounds")) {
                for (boolean enabled : List.of(true, false)) {
                    field.setBoolean(null, enabled);
                    var diagnostic = castInModeWithMetrics(helper, player, target, program, referenceSpell, yjspIota,
                            tickIota, ServerConfig.HexJitMode.AUTO);
                    assertCompleted(helper, diagnostic, target, referenceSpell, "Staff sound ablation counters");
                    System.out.println("HEXJIT_STAFF_SOUND_COUNTS enabled=" + enabled
                            + " platformCalls=" + ExecutionScope.lastSoundsEmitted()
                            + " skipped=" + ExecutionScope.lastSoundsCoalesced()
                            + " pureQuoteRuns=" + ExecutionScope.lastPureQuoteRuns());
                }
                long[] pairedSavings = new long[samples];
                for (int i = 0; i < samples; i++) pairedSavings[i] = disabledNanos[i] - enabledNanos[i];
                System.out.println("HEXJIT_STAFF_SOUND_SAMPLES pairs=" + samples
                        + " medianPairedSavingMs=" + median(pairedSavings) / 1_000_000.0
                        + " enabledMs=" + Arrays.stream(enabledNanos).mapToDouble(n -> n / 1_000_000.0).boxed().toList()
                        + " disabledMs=" + Arrays.stream(disabledNanos).mapToDouble(n -> n / 1_000_000.0).boxed().toList());
            }
        } finally {
            field.setBoolean(null, original);
        }
        return new FeatureAblation(enabledNanos, disabledNanos);
    }

    private static void runFishermanSpell(GameTestHelper helper, ServerLevel level,
                                          ReferenceSpell referenceSpell, List<Iota> program)
            throws ReflectiveOperationException {
        ServerConfig.hexJitBatchAddMotion = true;
        LivingEntity caster = Objects.requireNonNull(net.minecraft.world.entity.EntityType.ARMOR_STAND.create(level));
        BlockPos casterPos = helper.absolutePos(new BlockPos(1, 4, 1));
        caster.setPos(casterPos.getX() + 0.5, casterPos.getY(), casterPos.getZ() + 0.5);
        caster.setYRot(0);
        caster.setXRot(0);
        helper.assertTrue(level.addFreshEntity(caster), "Could not add the fisherman spell test caster");

        ServerConfig.hexJitThreshold = 2;
        HexJitRuntime.invalidate("fisherman-loop interpreter run");
        boolean oldCollectMetrics = ServerConfig.hexJitCollectMetrics;
        FishermanRun interpreted;
        FishermanRun compiled;
        try {
            ServerConfig.hexJitCollectMetrics = true;
            interpreted = castFishermanSpell(helper, level, caster, referenceSpell, program,
                    ServerConfig.HexJitMode.OFF);
            ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
            HexJitRuntime.invalidate("fisherman-loop AUTO run");
            compiled = castFishermanSpell(helper, level, caster, referenceSpell, program,
                    ServerConfig.HexJitMode.AUTO);
        } finally {
            ServerConfig.hexJitCollectMetrics = oldCollectMetrics;
        }

        helper.assertTrue(sameFishermanState(interpreted, compiled), "fisherman-loop changed under HexJIT\nOFF="
                + interpreted + "\nAUTO=" + compiled);
        helper.assertTrue("EVALUATED:true".equals(interpreted.resolution())
                        && interpreted.trace().stream().noneMatch(line -> line.startsWith("mishap:")),
                "fisherman-loop mishapped: resolution=" + interpreted.resolution());
        helper.assertTrue(interpreted.mediaChecks() >= 1_000 && interpreted.nonzeroMediaChecks() > 0
                        && (interpreted.nonzeroMediaChecks() & 1) == 0
                        && interpreted.externalState().contains(":hurtMarked=true:"),
                "fisherman-loop did not exercise repeated media checks and motion: " + interpreted);
        helper.assertTrue(interpreted.opsConsumed() <= referenceSpell.opLimit(),
                "fisherman-loop exceeded the reference spell op limit: " + interpreted.opsConsumed());
        helper.assertTrue(ExecutionScope.lastMotionPushes() > 1_000 && ExecutionScope.lastMotionWrites() == 1,
                "fisherman-loop Add Motion batching did not combine ordered writes");
        helper.assertTrue(compiled.particleCalls() < interpreted.particleCalls(),
                "fisherman-loop did not coalesce duplicate particle sprays");
        for (String feature : new String[] {
                "hexJitBatchAddMotion", "hexJitFastAddMotionArguments",
                "hexJitMemoAddMotionNormalization"
        }) {
            FeatureAblation ablation = measureFishermanAblation(helper, level, caster, referenceSpell,
                    program, feature);
            System.out.println("HEXJIT_ABLATION spell=" + referenceSpell.name()
                    + " flag=" + feature
                    + " enabledAUTOms=" + median(ablation.enabledNanos()) / 1_000_000.0
                    + " disabledAUTOms=" + median(ablation.disabledNanos()) / 1_000_000.0
                    + " enabledRatio=" + median(ablation.enabledNanos()) / (double) interpreted.elapsedNanos()
                    + " disabledRatio=" + median(ablation.disabledNanos()) / (double) interpreted.elapsedNanos());
        }
        System.out.println("HEXJIT_REFERENCE_SPELL name=" + referenceSpell.name()
                + " iotas=" + program.size() + " mediaChecks=" + interpreted.mediaChecks()
                + " nonzeroCostChecks=" + interpreted.nonzeroMediaChecks()
                + " ops=" + interpreted.opsConsumed() + " resolution=" + interpreted.resolution());
        caster.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
    }

    private static FeatureAblation measureFishermanAblation(
            GameTestHelper helper,
            ServerLevel level,
            LivingEntity caster,
            ReferenceSpell referenceSpell,
            List<Iota> program,
            String fieldName) throws ReflectiveOperationException {
        java.lang.reflect.Field field = ServerConfig.class.getField(fieldName);
        boolean original = field.getBoolean(null);
        long[] enabledNanos = new long[5];
        long[] disabledNanos = new long[5];
        try {
            for (int i = 0; i < enabledNanos.length; i++) {
                FishermanRun enabled;
                FishermanRun disabled;
                if ((i & 1) == 0) {
                    field.setBoolean(null, true);
                    enabled = castFishermanSpell(helper, level, caster, referenceSpell, program,
                            ServerConfig.HexJitMode.AUTO);
                    field.setBoolean(null, false);
                    disabled = castFishermanSpell(helper, level, caster, referenceSpell, program,
                            ServerConfig.HexJitMode.AUTO);
                } else {
                    field.setBoolean(null, false);
                    disabled = castFishermanSpell(helper, level, caster, referenceSpell, program,
                            ServerConfig.HexJitMode.AUTO);
                    field.setBoolean(null, true);
                    enabled = castFishermanSpell(helper, level, caster, referenceSpell, program,
                            ServerConfig.HexJitMode.AUTO);
                }
                helper.assertTrue(sameFishermanState(enabled, disabled),
                        fieldName + " changed fisherman-loop state: enabled=" + enabled + ", disabled=" + disabled);
                enabledNanos[i] = enabled.elapsedNanos();
                disabledNanos[i] = disabled.elapsedNanos();
            }
        } finally {
            field.setBoolean(null, original);
        }
        return new FeatureAblation(enabledNanos, disabledNanos);
    }

    private static boolean sameFishermanState(FishermanRun off, FishermanRun auto) {
        List<String> offTrace = off.trace().stream().filter(line -> !line.equals("particles")).toList();
        List<String> autoTrace = auto.trace().stream().filter(line -> !line.equals("particles")).toList();
        return off.image().equals(auto.image()) && offTrace.equals(autoTrace)
                && off.resolution().equals(auto.resolution()) && off.randomState() == auto.randomState()
                && off.externalState().equals(auto.externalState())
                && off.mediaChecks() == auto.mediaChecks()
                && off.nonzeroMediaChecks() == auto.nonzeroMediaChecks()
                && off.continuationState().equals(auto.continuationState())
                && off.continuationSteps() == auto.continuationSteps();
    }

    private static FishermanRun castFishermanSpell(GameTestHelper helper,
                                                   ServerLevel level,
                                                   LivingEntity caster,
                                                   ReferenceSpell referenceSpell,
                                                   List<Iota> program,
                                                   ServerConfig.HexJitMode mode) {
        ServerConfig.hexJitMode = mode;
        caster.setDeltaMovement(Vec3.ZERO);
        caster.hurtMarked = false;
        var env = new HexJitGameTests.TestEnvironment(level, caster);
        env.limit = referenceSpell.opLimit();
        env.remainingMedia = Long.MAX_VALUE / 4;
        env.getWorld().random.setSeed(9128374L);
        CastingVM vm = new CastingVM(new CastingImage(TreeList.empty(), 0, TreeList.empty(),
                false, false, 0, new CompoundTag()), env);
        long startedNanos = System.nanoTime();
        ExecutionClientView view = vm.queueExecuteAndWrapIotas(program, level);
        long elapsedNanos = System.nanoTime() - startedNanos;
        Vec3 motion = caster.getDeltaMovement();
        String externalState = motion.x + "," + motion.y + "," + motion.z
                + ":hurtMarked=" + caster.hurtMarked + ":observerMotionHash=" + env.observerMotionHash
                + ":mediaRemaining=" + env.remainingMedia;
        return new FishermanRun(CastingImage.Companion.getCODEC().encodeStart(NbtOps.INSTANCE, vm.getImage()).getOrThrow(),
                List.copyOf(env.trace), view.getResolutionType() + ":" + view.isStackClear(),
                env.getWorld().random.nextLong(), externalState, env.mediaChecks, env.nonzeroMediaChecks,
                env.particleCalls,
                SpellContinuation.getCODEC().encodeStart(NbtOps.INSTANCE, env.lastContinuation).getOrThrow(),
                env.continuationSteps, vm.getImage().getOpsConsumed(), elapsedNanos);
    }

    private static void configureHexOverpowered(net.minecraft.server.level.ServerPlayer player) throws Exception {
        Class<?> forgeConfig = Class.forName("io.yukkuric.hexop.forge.HexOPConfigForge");
        Object config = forgeConfig.getField("INSTANCE").get(null);
        setHexOpConfigValue(forgeConfig, config, "cfg_EnablesPersonalMediaPool", true);
        // HexOP's IntValue upper bound overflows when its source uses (int) 1e10.
        // This test cap remains within that bound while exceeding yjsp_media's 1.14514B target.
        setHexOpConfigValue(forgeConfig, config, "cfg_PersonalMediaMax", 1_300_000_000);
        setHexOpConfigValue(forgeConfig, config, "cfg_PersonalMediaAfterEnlightened", false);

        Class<?> attributes = Class.forName("io.yukkuric.hexop.HexOPAttributes");
        @SuppressWarnings("unchecked")
        Holder<Attribute> mediaMax = (Holder<Attribute>) attributes.getMethod("getPERSONAL_MEDIA_MAX").invoke(null);
        AttributeInstance instance = player.getAttribute(mediaMax);
        if (instance == null) throw new IllegalStateException("FakePlayer lacks HexOP's personal media max attribute");
        instance.setBaseValue(1_300_000_000.0);

        Class<?> api = Class.forName("io.yukkuric.hexop.HexOPConfig");
        boolean enabled = (boolean) api.getMethod("EnablesPersonalMediaPool").invoke(null);
        int maxMedia = (int) api.getMethod("PersonalMediaMax").invoke(null);
        boolean requiresEnlightenment = (boolean) api.getMethod("PersonalMediaAfterEnlightened").invoke(null);
        if (!enabled || maxMedia < 1_145_140_000 || requiresEnlightenment)
            throw new IllegalStateException("HexOP test media settings were not applied: enabled=" + enabled
                    + ", max=" + maxMedia + ", afterEnlightened=" + requiresEnlightenment);
        System.out.println("HEXJIT_MEDIA_CONFIG enabled=" + enabled + " max=" + maxMedia
                + " afterEnlightened=" + requiresEnlightenment);
    }

    private static void setHexOpConfigValue(Class<?> configClass, Object config, String fieldName, Object value)
            throws ReflectiveOperationException {
        Object configValue = configClass.getField(fieldName).get(config);
        configValue.getClass().getMethod("set", Object.class).invoke(configValue, value);
    }

    private static PatternIota greatPattern(ResourceLocation id) {
        Iota pattern = PatternMapper.mapPatternWorld.get(id.toString());
        if (!(pattern instanceof PatternIota result))
            throw new IllegalStateException("HexParse has no scrambled pattern for " + id);
        return result;
    }

    private static SpellRun castInMode(GameTestHelper helper,
                                       net.minecraft.server.level.ServerPlayer player,
                                       BlockPos target,
                                       List<Iota> program,
                                       ReferenceSpell referenceSpell,
                                       PatternIota yjsp,
                                       PatternIota tick,
                                       ServerConfig.HexJitMode mode) {
        return castInMode(helper, player, target, program, referenceSpell, yjsp, tick, mode, false);
    }

    private static SpellRun castInModeWithMetrics(GameTestHelper helper,
                                       net.minecraft.server.level.ServerPlayer player,
                                       BlockPos target,
                                       List<Iota> program,
                                       ReferenceSpell referenceSpell,
                                       PatternIota yjsp,
                                       PatternIota tick,
                                       ServerConfig.HexJitMode mode) {
        return castInMode(helper, player, target, program, referenceSpell, yjsp, tick, mode, true);
    }

    private static SpellRun castInMode(GameTestHelper helper,
                                       net.minecraft.server.level.ServerPlayer player,
                                       BlockPos target,
                                       List<Iota> program,
                                       ReferenceSpell referenceSpell,
                                       PatternIota yjsp,
                                       PatternIota tick,
                                       ServerConfig.HexJitMode mode,
                                       boolean collectMetrics) {
        boolean previousMetrics = ServerConfig.hexJitCollectMetrics;
        ServerConfig.hexJitMode = mode;
        ServerConfig.hexJitCollectMetrics = collectMetrics;
        try {
            return cast(helper, player, target, program, referenceSpell, yjsp, tick, false);
        } finally {
            ServerConfig.hexJitCollectMetrics = previousMetrics;
        }
    }

    private static SpellRun recordAndCast(GameTestHelper helper,
                                          net.minecraft.server.level.ServerPlayer player,
                                          BlockPos target,
                                          List<Iota> program,
                                          ReferenceSpell referenceSpell,
                                          PatternIota yjsp,
                                          PatternIota tick,
                                          ServerConfig.HexJitMode mode,
                                          String label) throws Exception {
        ServerConfig.hexJitMode = mode;
        String output = System.getProperty("hexjit.jfr.dir");
        if (output == null || output.isBlank()) throw new IOException("GameTest profile output directory is unset");
        Path path = Path.of(output).resolve(referenceSpell.name() + "-" + label + ".jfr");
        Files.createDirectories(path.getParent());
        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.setName("HexJIT " + referenceSpell.name() + " " + mode);
            recording.setToDisk(true);
            recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));
            int profileCasts = referenceSpell.expectedTickCalls() > 0
                    && mode == ServerConfig.HexJitMode.AUTO ? 4 : 1;
            SpellRun result = null;
            recording.start();
            try {
                for (int i = 0; i < profileCasts; i++)
                    result = cast(helper, player, target, program, referenceSpell, yjsp, tick, false, recording);
            } finally {
                recording.stop();
            }
            recording.dump(path);
            System.out.println("HEXJIT_JFR path=" + path.toAbsolutePath() + " mode=" + mode
                    + " casts=" + profileCasts + " lastCastMs=" + result.elapsedNanos / 1_000_000.0);
            return result;
        }
    }

    private static SpellRun cast(GameTestHelper helper,
                                 net.minecraft.server.level.ServerPlayer player,
                                 BlockPos target,
                                 List<Iota> program,
                                 ReferenceSpell referenceSpell,
                                 PatternIota yjsp,
                                 PatternIota tick) {
        return cast(helper, player, target, program, referenceSpell, yjsp, tick, true);
    }

    private static SpellRun cast(GameTestHelper helper,
                                 net.minecraft.server.level.ServerPlayer player,
                                 BlockPos target,
                                 List<Iota> program,
                                 ReferenceSpell referenceSpell,
                                 PatternIota yjsp,
                                 PatternIota tick,
                                 boolean trackActionCalls) {
        return cast(helper, player, target, program, referenceSpell, yjsp, tick, trackActionCalls, null);
    }

    private static SpellRun cast(GameTestHelper helper,
                                 net.minecraft.server.level.ServerPlayer player,
                                 BlockPos target,
                                 List<Iota> program,
                                 ReferenceSpell referenceSpell,
                                 PatternIota yjsp,
                                 PatternIota tick,
                                 boolean trackActionCalls,
                                 Recording recording) {
        ServerLevel level = helper.getLevel();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    level.setBlock(target.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        level.setBlock(target, Blocks.BUDDING_AMETHYST.defaultBlockState(), 3);
        player.setPos(target.getX() + 2.5, target.getY(), target.getZ() + 0.5);
        player.setHealth(player.getMaxHealth());
        level.random.setSeed(WORLD_RANDOM_SEED);

        Iota coordinate = new Vec3Iota(Vec3.atCenterOf(target));
        List<Iota> initialStack = new ArrayList<>();
        for (ResourceLocation patternId : referenceSpell.initialPatternsBottomToTop())
            initialStack.add(greatPattern(patternId));
        initialStack.add(coordinate);
        TreeList<Iota> stack = TreeList.from(initialStack);
        if (stack.getLast() != coordinate)
            throw new IllegalStateException("The test stack must have the target coordinate on top");
        for (int i = 0; i < referenceSpell.initialPatternsBottomToTop().size(); i++) {
            ResourceLocation expectedId = referenceSpell.initialPatternsBottomToTop().get(i);
            PatternIota actual = (PatternIota) stack.get(i);
            if (!actual.getPattern().equals(greatPattern(expectedId).getPattern()))
                throw new IllegalStateException("Initial stack pattern order changed for " + referenceSpell.name());
        }

        ReferenceSpellEnvironment observerEnv = trackActionCalls
                ? new ReferenceSpellEnvironment(player, yjsp.getPattern(), tick.getPattern(), referenceSpell.opLimit()) : null;
        StaffCastEnv env = observerEnv != null ? observerEnv
                : new BenchmarkSpellEnvironment(player, referenceSpell.opLimit());
        CastingImage initial = new CastingImage(stack, 0, TreeList.empty(), false, false, 0, new CompoundTag());
        CastingVM vm = new CastingVM(initial, env);
        int mediaUsedBefore = player.getStats().getValue(Stats.CUSTOM.get(HexStatistics.MEDIA_USED));
        long start = System.nanoTime();
        ExecutionClientView view;
        boolean startRecording = recording != null && recording.getState() == RecordingState.NEW;
        if (startRecording) recording.start();
        try {
            view = vm.queueExecuteAndWrapIotas(program, level);
        } finally {
            if (startRecording) recording.stop();
        }
        long elapsed = System.nanoTime() - start;
        CastingImage image = vm.getImage();
        int storedTicks = vm.getImage().getUserData().getCompound(OpTick.TAG_TIMES_TICKED).getInt(target.toShortString());
        String result = view.getResolutionType() + ":stackClear=" + view.isStackClear();
        List<net.minecraft.world.level.block.state.BlockState> areaStates = new ArrayList<>(27);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    areaStates.add(level.getBlockState(target.offset(x, y, z)));
                }
            }
        }
        long randomState = level.random.nextLong();
        long personalMedia = readPersonalMedia(player);
        int mediaUsed = player.getStats().getValue(Stats.CUSTOM.get(HexStatistics.MEDIA_USED)) - mediaUsedBefore;
        return new SpellRun(image, result, level.getBlockState(target), areaStates, randomState,
                storedTicks, observerEnv == null ? 0 : observerEnv.tickCalls,
                observerEnv == null ? 0 : observerEnv.yjspCalls,
                observerEnv == null ? 0 : observerEnv.particleCalls, vm.getImage().getOpsConsumed(),
                observerEnv == null ? "[]" : observerEnv.errors.toString(), player.getHealth(), personalMedia,
                mediaUsed, elapsed);
    }

    private static long readPersonalMedia(net.minecraft.server.level.ServerPlayer player) {
        try {
            Class<?> type = Class.forName("io.yukkuric.hexop.personal_mana.PersonalManaHolder");
            Object holder = type.getMethod("get", net.minecraft.world.entity.player.Player.class).invoke(null, player);
            if (!(holder instanceof ADMediaHolder media))
                throw new IllegalStateException("HexOP did not return a personal media holder");
            return media.withdrawMedia(-1, true);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not read HexOP's personal media pool", error);
        }
    }

    private static void assertCompleted(GameTestHelper helper, SpellRun result, BlockPos target,
                                        ReferenceSpell referenceSpell, String label) {
        helper.assertTrue(result.errors.equals("[]"), label + " had errors after " + result.tickCalls
                + " Tick and " + result.yjspCalls + " yjsp_media calls, ops=" + result.opsConsumed + ": " + result.errors);
        helper.assertTrue(!result.resolution.startsWith(ResolvedPatternType.ERRORED.toString()),
                label + " ended in an errored resolution: " + result.resolution);
        helper.assertTrue(result.tickCalls == 0 || result.tickCalls == referenceSpell.expectedTickCalls(),
                label + " ran P2 " + result.tickCalls + " times, expected " + referenceSpell.expectedTickCalls());
        helper.assertTrue(result.storedTicks == referenceSpell.expectedTickCalls(),
                label + " wrote " + result.storedTicks + " ticks to userdata, expected "
                        + referenceSpell.expectedTickCalls());
        helper.assertTrue(result.opsConsumed <= referenceSpell.opLimit(),
                label + " exceeded the GameTest benchmark operation budget: " + result.opsConsumed);
        helper.assertTrue(result.finalBlock.is(Blocks.BUDDING_AMETHYST),
                label + " changed the budding amethyst target at " + target + ": " + result.finalBlock);
    }

    private static void assertEquivalent(GameTestHelper helper, SpellRun off, SpellRun auto, String label) {
        helper.assertTrue(off.image.equals(auto.image), label + " changed the final CastingImage\nOFF=" + off + "\nAUTO=" + auto);
        helper.assertTrue(off.resolution.equals(auto.resolution), label + " changed the resolution\nOFF=" + off + "\nAUTO=" + auto);
        helper.assertTrue(off.finalBlock.equals(auto.finalBlock) && off.areaStates.equals(auto.areaStates),
                label + " changed the final target area\nOFF=" + off + "\nAUTO=" + auto);
        helper.assertTrue(off.randomState == auto.randomState,
                label + " changed the world RNG state\nOFF=" + off.randomState + "\nAUTO=" + auto.randomState);
        helper.assertTrue(off.tickCalls == auto.tickCalls && off.yjspCalls == auto.yjspCalls
                        && off.storedTicks == auto.storedTicks,
                label + " changed the number of Tick or yjsp_media actions");
        helper.assertTrue(Float.compare(off.playerHealth, auto.playerHealth) == 0,
                label + " changed caster health: OFF=" + off.playerHealth + ", AUTO=" + auto.playerHealth);
        helper.assertTrue(off.personalMedia == auto.personalMedia,
                label + " changed HexOP personal media: OFF=" + off.personalMedia + ", AUTO=" + auto.personalMedia);
        helper.assertTrue(off.mediaUsed == auto.mediaUsed,
                label + " changed the MEDIA_USED statistic: OFF=" + off.mediaUsed + ", AUTO=" + auto.mediaUsed);
        helper.assertTrue(auto.particleCalls <= off.particleCalls,
                label + " increased emitted particle sprays: OFF=" + off.particleCalls + ", AUTO=" + auto.particleCalls);
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private record SpellRun(CastingImage image, String resolution, net.minecraft.world.level.block.state.BlockState finalBlock,
                            List<net.minecraft.world.level.block.state.BlockState> areaStates,
                            long randomState, int storedTicks, long tickCalls, long yjspCalls, long particleCalls,
                            long opsConsumed, String errors, float playerHealth, long personalMedia,
                            int mediaUsed, long elapsedNanos) {}

    private record FishermanRun(net.minecraft.nbt.Tag image, List<String> trace, String resolution,
                                long randomState, String externalState, long mediaChecks,
                                long nonzeroMediaChecks, long particleCalls,
                                net.minecraft.nbt.Tag continuationState, long continuationSteps,
                                long opsConsumed, long elapsedNanos) {}

    private record FeatureAblation(long[] enabledNanos, long[] disabledNanos) {}

    private static class BenchmarkSpellEnvironment extends StaffCastEnv
            implements TickStateReuseEnvironment, StableMaxOpCountEnvironment {
        private final int opLimit;

        private BenchmarkSpellEnvironment(net.minecraft.server.level.ServerPlayer player, int opLimit) {
            super(player, InteractionHand.MAIN_HAND);
            this.opLimit = Math.max(super.maxOpCount(), opLimit);
        }

        @Override
        public boolean isEnlightened() {
            return true;
        }

        @Override
        public int maxOpCount() {
            return opLimit;
        }
    }

    private static final class ReferenceSpellEnvironment extends BenchmarkSpellEnvironment {
        private final HexPattern yjspPattern;
        private final HexPattern tickPattern;
        private final List<String> errors = new ArrayList<>();
        private long tickCalls;
        private long yjspCalls;
        private long particleCalls;

        private ReferenceSpellEnvironment(net.minecraft.server.level.ServerPlayer player,
                                          HexPattern yjspPattern,
                                          HexPattern tickPattern,
                                          int opLimit) {
            super(player, opLimit);
            this.yjspPattern = yjspPattern;
            this.tickPattern = tickPattern;
        }

        @Override
        public void postExecution(CastResult result) {
            super.postExecution(result);
            if (result.getCast() instanceof PatternIota pattern && pattern.getPattern().equals(tickPattern)) tickCalls++;
            if (result.getCast() instanceof PatternIota pattern && pattern.getPattern().equals(yjspPattern)) yjspCalls++;
            if (result.getResolutionType() == ResolvedPatternType.ERRORED)
                errors.add(result.getSideEffects().toString());
        }

        @Override
        public void produceParticles(ParticleSpray spray, FrozenPigment pigment) {
            particleCalls++;
            super.produceParticles(spray, pigment);
        }

        @Override
        public void printMessage(Component message) {
            errors.add(message.getString());
            super.printMessage(message);
        }
    }

    private record ReferenceSpell(String name,
                                  String source,
                                  List<ResourceLocation> initialPatternsBottomToTop,
                                  int expectedTickCalls,
                                  int opLimit) {
        private ReferenceSpell {
            if (name.isBlank()) throw new IllegalArgumentException("Reference spell name cannot be blank");
            if (expectedTickCalls < 0) throw new IllegalArgumentException("Expected Tick calls cannot be negative");
            if (opLimit < 1) throw new IllegalArgumentException("Reference spell op limit must be positive");
            initialPatternsBottomToTop = List.copyOf(initialPatternsBottomToTop);
        }
    }

}
