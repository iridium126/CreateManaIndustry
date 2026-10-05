package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.*;
import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.arithmetic.*;
import at.petrak.hexcasting.api.casting.arithmetic.engine.*;
import at.petrak.hexcasting.api.casting.arithmetic.operator.Operator;
import at.petrak.hexcasting.api.casting.eval.*;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.vm.*;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.casting.math.*;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.*;
import at.petrak.hexcasting.common.lib.HexBlocks;
import at.petrak.hexcasting.common.casting.actions.math.SpecialHandlerNumberLiteral;
import at.petrak.hexcasting.common.casting.actions.stack.SpecialHandlerMask;
import at.petrak.hexcasting.common.casting.PatternRegistryManifest;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.iridium126.createmanaindustry.compat.hexcasting.HexCompat;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.iridium126.createmanaindustry.infrastructure.concurrent.CMIThreadFactory;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexJitGameTests {
    private static volatile Object blackhole;
    private static final CMIThreadFactory TEST_THREADS = CMIThreadFactory.daemonFactory("hexjit-test-isolation");
    private static final String FULL_FEATURE_BENCH_CASE = "AUTO_FULL_FEATURE_SET";
    private static boolean hasFullObserverOptimization(String name) {
        return name.equals(FULL_FEATURE_BENCH_CASE) || name.startsWith("AUTO_FULL_FEATURES_");
    }

    @GameTest(template = "hex_jit_test", timeoutTicks = 1200)
    public static void differentialAndBenchmark(GameTestHelper helper) throws Exception {
        // Force all seven guarded targets through transformation before checking readiness.
        for (String name : List.of("api.casting.arithmetic.engine.ArithmeticEngine",
                "api.casting.arithmetic.engine.ArithmeticEngine$OpCandidates",
                "api.casting.eval.vm.FrameEvaluate", "api.casting.eval.vm.CastingVM",
                "api.casting.eval.CastingEnvironment", "api.casting.iota.PatternIota",
                "api.casting.math.HexDir", "common.casting.PatternRegistryManifest"))
            Class.forName("at.petrak.hexcasting." + name);
        Class.forName("net.minecraft.world.entity.Entity");
        Class.forName("at.petrak.hexcasting.common.casting.actions.spells.OpAddMotion");
        var env = new TestEnvironment(helper.getLevel());
        CastingVM.empty(env).queueExecuteAndWrapIotas(List.of(), helper.getLevel());
        helper.assertTrue(JitCompatibility.ready(), "Hex JIT Mixin compatibility gate failed: " + JitCompatibility.status());
        helper.assertTrue(JitCompatibility.frameTailCacheReady(),
                "FrameEvaluate tail target failed verification: " + JitCompatibility.status());
        helper.assertTrue(JitCompatibility.specialHandlerMathReady(),
                "Special-handler math target failed verification: " + JitCompatibility.status());
        helper.assertTrue(JitCompatibility.specialHandlerLookupReady(),
                "Special-handler lookup target failed verification: " + JitCompatibility.status());
        helper.assertTrue(JitCompatibility.fastNumberLiteralReady(),
                "Number-literal target failed verification: " + JitCompatibility.status());
        verifyStackValidation(helper);
        verifyCompoundTagCopy(helper);
        verifyHexDirMath(helper);
        verifyAddMotionNormalizationCache(helper);
        var ordinaryMatch = PatternRegistryManifest.matchPattern(JitTestAddon.ORDINARY, env);
        helper.assertTrue(ordinaryMatch instanceof PatternShapeMatch.Normal,
                "Test add-on action pattern collided: " + ordinaryMatch.getClass().getName());
        var ordinaryKey = ((PatternShapeMatch.Normal) ordinaryMatch).key;
        Action ordinaryAction = IXplatAbstractions.INSTANCE.getActionRegistry().get(ordinaryKey).action();
        helper.assertTrue(JitTestAddon.isOrdinaryAction(ordinaryAction),
                "Test add-on ordinary action was overridden");
        helper.assertTrue(ActionSites.registeredAction(ordinaryKey) == ordinaryAction,
                "Registered-action lookup cache lost the add-on action identity");
        ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
        ServerConfig.hexJitCacheNormalPatternLookup = true;
        PatternIota cachedPattern = math(JitTestAddon.ORDINARY);
        Scenario normalCacheScenario = s("normal pattern cache", List.of(), cachedPattern);
        Snapshot normalCacheFirst = run(helper, normalCacheScenario, ServerConfig.HexJitMode.AUTO);
        var normalCacheEpochField = PatternIota.class.getDeclaredField("cmi$normalPatternEpoch");
        var normalCacheKeyField = PatternIota.class.getDeclaredField("cmi$normalPatternKey");
        normalCacheEpochField.setAccessible(true);
        normalCacheKeyField.setAccessible(true);
        long firstPatternEpoch = normalCacheEpochField.getLong(cachedPattern);
        helper.assertTrue(firstPatternEpoch == HexJitRuntime.generation()
                        && normalCacheKeyField.get(cachedPattern) == ordinaryKey,
                "Normal add-on pattern was not cached against the active registry epoch");
        HexJitRuntime.invalidate("normal-pattern cache epoch test");
        Snapshot normalCacheAfterInvalidation = run(helper, normalCacheScenario, ServerConfig.HexJitMode.AUTO);
        helper.assertTrue(sameState(normalCacheFirst, normalCacheAfterInvalidation)
                        && normalCacheEpochField.getLong(cachedPattern) == HexJitRuntime.generation(),
                "Normal pattern cache survived a registry epoch change");
        ServerConfig.hexJitCacheNormalPatternLookup = false;
        Snapshot normalPatternUncached = run(helper, normalCacheScenario, ServerConfig.HexJitMode.AUTO);
        helper.assertTrue(sameState(normalCacheFirst, normalPatternUncached),
                "Disabling normal pattern lookup caching changed an add-on Action result");
        ServerConfig.hexJitCacheNormalPatternLookup = true;
        var throwingMatch = PatternRegistryManifest.matchPattern(JitTestAddon.THROWING, env);
        helper.assertTrue(throwingMatch instanceof PatternShapeMatch.Normal
                        && JitTestAddon.isThrowingAction(IXplatAbstractions.INSTANCE.getActionRegistry()
                        .get(((PatternShapeMatch.Normal) throwingMatch).key).action()),
                "Test add-on throwing action was overridden: " + throwingMatch.getClass().getName());
        helper.assertTrue(PatternRegistryManifest.matchPattern(JitTestAddon.DYNAMIC, env) instanceof PatternShapeMatch.Special,
                "Test add-on dynamic handler pattern collided");
        ServerConfig.hexJitThreshold = 2;
        ServerConfig.hexJitCompileActions = true;
        ServerConfig.hexJitCoalesceDecorations = true;
        ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
        ServerConfig.hexJitFastAddMotionArguments = false;
        ServerConfig.hexJitFastStackValidation = false;
        ServerConfig.hexJitReuseFrameTail = false;
        HexJitRuntime.invalidate("differential warmup");
        verifyWorldMutationParity(helper);
        List<Scenario> corpus = corpus();
        for (int pass = 0; pass < 10; pass++) for (Scenario scenario : corpus) run(helper, scenario, ServerConfig.HexJitMode.AUTO);
        helper.runAfterDelay(10, () -> {
            try {
                for (int pass = 0; pass < 3; pass++) for (Scenario scenario : corpus) {
                    Snapshot baseline = run(helper, scenario, ServerConfig.HexJitMode.OFF);
                    Snapshot jit = run(helper, scenario, ServerConfig.HexJitMode.AUTO);
                    helper.assertTrue(sameState(baseline, jit), "Differential mismatch: " + scenario.name + "\n" + baseline + "\n" + jit);
                }
                ServerConfig.hexJitFastStackValidation = true;
                for (Scenario scenario : corpus) {
                    Snapshot baseline = run(helper, scenario, ServerConfig.HexJitMode.OFF);
                    Snapshot validated = run(helper, scenario, ServerConfig.HexJitMode.AUTO);
                    helper.assertTrue(sameState(baseline, validated), "Indexed stack validation mismatch: "
                            + scenario.name + "\n" + baseline + "\n" + validated);
                }
                ServerConfig.hexJitCacheStackMetrics = true;
                for (Scenario scenario : corpus) {
                    Snapshot baseline = run(helper, scenario, ServerConfig.HexJitMode.OFF);
                    Snapshot cached = run(helper, scenario, ServerConfig.HexJitMode.AUTO);
                    helper.assertTrue(sameState(baseline, cached), "Cached stack metrics mismatch: "
                            + scenario.name + "\n" + baseline + "\n" + cached);
                }
                ServerConfig.hexJitCacheStackMetrics = false;
                ServerConfig.hexJitFastStackValidation = false;
                ServerConfig.hexJitReuseFrameTail = true;
                for (Scenario scenario : corpus) {
                    Snapshot baseline = run(helper, scenario, ServerConfig.HexJitMode.OFF);
                    Snapshot reusedTail = run(helper, scenario, ServerConfig.HexJitMode.AUTO);
                    helper.assertTrue(sameState(baseline, reusedTail), "FrameEvaluate tail reuse mismatch: "
                            + scenario.name + "\n" + baseline + "\n" + reusedTail);
                }
                ServerConfig.hexJitReuseFrameTail = false;
                ServerConfig.hexJitFastNumberLiterals = true;
                for (Scenario scenario : corpus) {
                    Snapshot baseline = run(helper, scenario, ServerConfig.HexJitMode.OFF);
                    Snapshot fastNumbers = run(helper, scenario, ServerConfig.HexJitMode.AUTO);
                    helper.assertTrue(sameState(baseline, fastNumbers), "Number-literal fast path mismatch: "
                            + scenario.name + "\n" + baseline + "\n" + fastNumbers);
                }
                ServerConfig.hexJitFastNumberLiterals = false;
                helper.assertTrue(HexJitRuntime.status().matches("(?s).*compiledHits=[1-9][0-9]*.*"), "No compiled execution: " + HexJitRuntime.status());
                helper.assertTrue(ActionSites.compiledHits() > 0, "PatternIota did not dispatch a compiled add-on Action");
                ServerConfig.hexJitFastSpecialHandlerLookup = true;
                Scenario dynamicScenario = corpus.stream().filter(s -> s.name.equals("dynamic special handler"))
                        .findFirst().orElseThrow();
                Snapshot dynamicBaseline = run(helper, dynamicScenario, ServerConfig.HexJitMode.OFF);
                Snapshot dynamicCached = run(helper, dynamicScenario, ServerConfig.HexJitMode.AUTO);
                helper.assertTrue(sameState(dynamicBaseline, dynamicCached),
                        "Factory table cache skipped or reordered an environment-dependent handler match\n"
                                + dynamicBaseline + "\n" + dynamicCached);
                ServerConfig.hexJitFastSpecialHandlerLookup = false;
                ServerConfig.hexJitSkipObservers = true;
                long observerStart = TestEnvironment.SKIPPABLE_CALLS.get();
                Snapshot observerBaseline = run(helper, corpus.stream().filter(s -> s.name.equals("addon action and continuation"))
                        .findFirst().orElseThrow(), ServerConfig.HexJitMode.OFF);
                long baselineObserverCalls = TestEnvironment.SKIPPABLE_CALLS.get() - observerStart;
                Snapshot observerJit = run(helper, corpus.stream().filter(s -> s.name.equals("addon action and continuation"))
                        .findFirst().orElseThrow(), ServerConfig.HexJitMode.AUTO);
                long jitObserverCalls = TestEnvironment.SKIPPABLE_CALLS.get() - observerStart - baselineObserverCalls;
                helper.assertTrue(sameState(observerBaseline, observerJit), "Stateful observer was skipped on a compiled Action");
                helper.assertTrue(baselineObserverCalls > jitObserverCalls, "Explicitly skippable observer was not omitted");
                ServerConfig.hexJitSkipObservers = false;
                overloadCache(helper);
                HexJitRuntime.invalidate("test special-handler cache invalidation");
                ServerConfig.hexJitFastSpecialHandlerLookup = true;
                Snapshot dynamicAfterInvalidation = run(helper, dynamicScenario, ServerConfig.HexJitMode.AUTO);
                helper.assertTrue(sameState(dynamicBaseline, dynamicAfterInvalidation),
                        "Special-handler factory table changed behavior after cache invalidation\n"
                                + dynamicBaseline + "\n" + dynamicAfterInvalidation);
                ServerConfig.hexJitFastSpecialHandlerLookup = false;
                benchmark(helper);
                HexJitRuntime.invalidate("test reload");
                for (Scenario scenario : corpus) helper.assertTrue(sameState(run(helper, scenario, ServerConfig.HexJitMode.OFF),
                        run(helper, scenario, ServerConfig.HexJitMode.AUTO)), "Reload mismatch: " + scenario.name);
                System.out.println("HEXJIT_VALIDATION " + HexJitRuntime.status());
                helper.succeed();
            } catch (Throwable error) { helper.fail(error.toString()); }
            finally { ServerConfig.refreshHexJitSettings(); }
        });
    }

    private record Scenario(String name, List<Iota> stack, List<Iota> program, int opLimit) {}
    private record Snapshot(Tag image, List<String> trace, String resolution, long randomState, String externalState,
                            long mediaChecks, long nonzeroMediaChecks, long particleCalls,
                            Tag continuationState, long continuationSteps) {}
    private record DropSnapshot(String item, long px, long py, long pz, long vx, long vy, long vz) {}
    private record BlockSnapshot(BlockPos relativePos, BlockState state, CompoundTag blockEntity) {}
    private record WorldSnapshot(List<BlockSnapshot> blocks, List<DropSnapshot> drops) {}
    private record WorldCastSnapshot(Snapshot cast, WorldSnapshot world) {}
    private record SpellBenchCase(String name, ServerConfig.HexJitMode mode, boolean compileActions,
                                  boolean coalesceDecorations, boolean batchAddMotion,
                                  boolean fastAddMotionArguments, boolean fastStackValidation,
                                  boolean reuseFrameTail, boolean fastSpecialHandlerMath,
                                  boolean fastSpecialHandlerLookup, boolean fastNumberLiterals,
                                  boolean cacheStackMetrics) {
        private SpellBenchCase(String name, ServerConfig.HexJitMode mode, boolean compileActions,
                               boolean coalesceDecorations, boolean batchAddMotion,
                               boolean fastAddMotionArguments, boolean fastStackValidation,
                               boolean reuseFrameTail, boolean fastSpecialHandlerMath) {
            this(name, mode, compileActions, coalesceDecorations, batchAddMotion, fastAddMotionArguments,
                    fastStackValidation, reuseFrameTail, fastSpecialHandlerMath, false, false, false);
        }
        private SpellBenchCase(String name, ServerConfig.HexJitMode mode, boolean compileActions,
                               boolean coalesceDecorations, boolean batchAddMotion,
                               boolean fastAddMotionArguments, boolean fastStackValidation,
                               boolean reuseFrameTail, boolean fastSpecialHandlerMath,
                               boolean fastSpecialHandlerLookup) {
            this(name, mode, compileActions, coalesceDecorations, batchAddMotion, fastAddMotionArguments,
                    fastStackValidation, reuseFrameTail, fastSpecialHandlerMath, fastSpecialHandlerLookup, false, false);
        }
        private SpellBenchCase(String name, ServerConfig.HexJitMode mode, boolean compileActions,
                               boolean coalesceDecorations, boolean batchAddMotion,
                               boolean fastAddMotionArguments, boolean fastStackValidation,
                               boolean reuseFrameTail, boolean fastSpecialHandlerMath,
                               boolean fastSpecialHandlerLookup, boolean fastNumberLiterals) {
            this(name, mode, compileActions, coalesceDecorations, batchAddMotion, fastAddMotionArguments,
                    fastStackValidation, reuseFrameTail, fastSpecialHandlerMath, fastSpecialHandlerLookup,
                    fastNumberLiterals, false);
        }
    }
    private static PatternIota p(Holder<ActionRegistryEntry> entry) { return new PatternIota(entry.value().prototype()); }
    private static PatternIota math(HexPattern pattern) { return new PatternIota(pattern); }
    private static Scenario s(String name, List<Iota> stack, Iota... program) { return new Scenario(name, stack, List.of(program), 100000); }

    private static List<Scenario> corpus() {
        var add = math(Arithmetic.ADD);
        var body = new ListIota(List.of(p(HexActions.ESCAPE), new DoubleIota(1), add));
        var cases = new ArrayList<Scenario>();
        cases.add(s("empty", List.of()));
        cases.add(s("add", List.of(new DoubleIota(1), new DoubleIota(2)), add));
        cases.add(s("vector", List.of(new Vec3Iota(new Vec3(1, 2, 3)), new Vec3Iota(new Vec3(-1, -2, -3))), add));
        cases.add(s("underflow", List.of(new DoubleIota(1)), add));
        cases.add(s("divide zero", List.of(new DoubleIota(1), new DoubleIota(0)), math(Arithmetic.DIV)));
        cases.add(s("invalid overload", List.of(new NullIota(), new NullIota()), add));
        cases.add(s("parentheses", List.of(), p(HexActions.OPEN_PAREN), add, new DoubleIota(4), p(HexActions.CLOSE_PAREN)));
        cases.add(s("escape", List.of(), p(HexActions.ESCAPE), add));
        cases.add(s("simulate", List.of(new DoubleIota(1), new DoubleIota(2)), p(HexActions.SIMULATE), add));
        cases.add(s("simulate failure", List.of(), p(HexActions.SIMULATE), add));
        cases.add(s("hermes", List.of(new DoubleIota(5), body), p(HexActions.EVAL)));
        cases.add(s("empty hermes", List.of(new ListIota(List.of())), p(HexActions.EVAL)));
        cases.add(s("thoth", List.of(new ListIota(List.of(new DoubleIota(1), new DoubleIota(2))), body),
                math(HexPattern.fromAngleString("waaddw", HexDir.EAST))));
        cases.add(s("random", List.of(), p(HexActions.RANDOM), p(HexActions.RANDOM), add));
        cases.add(s("fisherman depth zero", List.of(new DoubleIota(11), new DoubleIota(0)), p(HexActions.FISHERMAN)));
        cases.add(s("fisherman depth four", List.of(new DoubleIota(11), new NullIota(), new BooleanIota(true),
                new Vec3Iota(Vec3.ZERO), new DoubleIota(17), new DoubleIota(4)), p(HexActions.FISHERMAN)));
        cases.add(s("fisherman negative", List.of(new DoubleIota(11), new DoubleIota(22), new DoubleIota(33),
                new DoubleIota(-1)), p(HexActions.FISHERMAN)));
        cases.add(s("fisherman fractional", List.of(new DoubleIota(11), new DoubleIota(0.5)), p(HexActions.FISHERMAN)));
        cases.add(s("fisherman out of range", List.of(new DoubleIota(11), new DoubleIota(7)), p(HexActions.FISHERMAN)));
        cases.add(s("fisherman underflow", List.of(new DoubleIota(1)), p(HexActions.FISHERMAN)));
        cases.add(s("addon action and continuation", List.of(), math(JitTestAddon.ORDINARY), math(JitTestAddon.ORDINARY)));
        cases.add(s("addon action in parens", List.of(), p(HexActions.OPEN_PAREN), math(JitTestAddon.ORDINARY), p(HexActions.CLOSE_PAREN)));
        cases.add(s("addon exception", List.of(), math(JitTestAddon.THROWING)));
        cases.add(s("dynamic special handler", List.of(), math(JitTestAddon.DYNAMIC), math(JitTestAddon.DYNAMIC), add));
        var repeatedDynamic = math(JitTestAddon.DYNAMIC);
        cases.add(s("repeated dynamic special handler", List.of(new ListIota(List.of(repeatedDynamic, repeatedDynamic))),
                p(HexActions.EVAL)));
        cases.add(s("addon iota", List.of(), new JitTestAddon.ExecutableIota()));
        cases.add(s("charon", List.of(new ListIota(List.of(p(HexActions.HALT), add))), p(HexActions.EVAL)));
        cases.add(s("continuation capture", List.of(new ListIota(List.of())), p(HexActions.EVAL$CC)));
        cases.add(new Scenario("op limit", List.of(new DoubleIota(1), body), List.of(p(HexActions.EVAL)), 1));
        for (double value : new double[] {-0.0, Double.MIN_VALUE, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY})
            cases.add(s("floating " + value, List.of(new DoubleIota(value), new DoubleIota(-0.0)), add));
        return cases;
    }

    private static Snapshot run(GameTestHelper helper, Scenario scenario, ServerConfig.HexJitMode mode) {
        ServerConfig.hexJitMode = mode;
        var env = new TestEnvironment(helper.getLevel());
        env.limit = scenario.opLimit;
        env.getWorld().random.setSeed(9128374L);
        CastingVM vm = new CastingVM(new CastingImage(TreeList.from(scenario.stack), 0, TreeList.empty(),
                false, false, 0, new CompoundTag()), env);
        ExecutionClientView view = vm.queueExecuteAndWrapIotas(scenario.program, helper.getLevel());
        Snapshot snapshot = new Snapshot(CastingImage.Companion.getCODEC().encodeStart(NbtOps.INSTANCE, vm.getImage()).getOrThrow(),
                List.copyOf(env.trace), view.getResolutionType() + ":" + view.isStackClear(), env.getWorld().random.nextLong(), "-",
                env.mediaChecks, env.nonzeroMediaChecks, env.particleCalls,
                encodeContinuation(env.lastContinuation), env.continuationSteps);
        if (scenario.name.contains("continuation") && snapshot.continuationSteps == 0) {
            throw new AssertionError("Continuation scenario did not capture any returned continuation: " + scenario.name);
        }
        return snapshot;
    }

    private static boolean sameState(Snapshot left, Snapshot right) {
        List<String> leftTrace = left.trace.stream().filter(line -> !line.equals("particles")).toList();
        List<String> rightTrace = right.trace.stream().filter(line -> !line.equals("particles")).toList();
        return left.image.equals(right.image) && leftTrace.equals(rightTrace) && left.resolution.equals(right.resolution)
                && left.randomState == right.randomState && left.externalState.equals(right.externalState)
                && left.mediaChecks == right.mediaChecks && left.nonzeroMediaChecks == right.nonzeroMediaChecks
                && left.continuationState.equals(right.continuationState)
                && left.continuationSteps == right.continuationSteps;
    }

    private static void overloadCache(GameTestHelper helper) throws Throwable {
        int[] predicates = {0, 0};
        Operator first = new Operator(1, args -> { predicates[0]++; return args.iterator().next() instanceof DoubleIota; }) {
            @Override public OperationResult operate(CastingEnvironment env, CastingImage image, SpellContinuation continuation) {
                return new OperationResult(image.withUsedOps(7), List.of(), continuation, HexEvalSounds.NOTHING.get());
            }
        };
        Operator second = new Operator(1, args -> { predicates[1]++; return args.iterator().next() instanceof BooleanIota; }) {
            @Override public OperationResult operate(CastingEnvironment env, CastingImage image, SpellContinuation continuation) {
                return new OperationResult(image.withUsedOps(11), List.of(), continuation, HexEvalSounds.NOTHING.get());
            }
        };
        Arithmetic doubleExtension = new Arithmetic() {
            public String arithName() { return "test double extension"; }
            public Iterable<HexPattern> opTypes() { return List.of(Arithmetic.ADD); }
            public Operator getOperator(HexPattern pattern) { return first; }
        };
        Arithmetic booleanExtension = new Arithmetic() {
            public String arithName() { return "test boolean extension"; }
            public Iterable<HexPattern> opTypes() { return List.of(Arithmetic.ADD); }
            public Operator getOperator(HexPattern pattern) { return second; }
        };
        var engine = new ArithmeticEngine(List.of(doubleExtension, booleanExtension));
        var doubleImage = new CastingImage(TreeList.from(List.<Iota>of(new DoubleIota(4))), 0, TreeList.empty(), false, false, 0, new CompoundTag());
        var booleanImage = new CastingImage(TreeList.from(List.<Iota>of(new BooleanIota(true))), 0, TreeList.empty(), false, false, 0, new CompoundTag());
        ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
        for (int i = 0; i < 50; i++) {
            var doubleResult = engine.run(Arithmetic.ADD, new TestEnvironment(helper.getLevel()), doubleImage, SpellContinuation.Done.INSTANCE);
            var booleanResult = engine.run(Arithmetic.ADD, new TestEnvironment(helper.getLevel()), booleanImage, SpellContinuation.Done.INSTANCE);
            helper.assertTrue(doubleResult.getNewImage().getOpsConsumed() == 7, "First add-on overload was bypassed");
            helper.assertTrue(booleanResult.getNewImage().getOpsConsumed() == 11, "Type switch reused the wrong add-on overload");
        }
        helper.assertTrue(predicates[0] == 2 && predicates[1] == 1,
                "Ordered first-match cache semantics changed across type switches: " + Arrays.toString(predicates));
        ServerConfig.hexJitMode = ServerConfig.HexJitMode.OFF;
        engine.run(Arithmetic.ADD, new TestEnvironment(helper.getLevel()), doubleImage, SpellContinuation.Done.INSTANCE);
        engine.run(Arithmetic.ADD, new TestEnvironment(helper.getLevel()), booleanImage, SpellContinuation.Done.INSTANCE);
        helper.assertTrue(predicates[0] == 2 && predicates[1] == 1, "JIT did not populate the original engine cache for both type tuples");
    }

    private static void benchmark(GameTestHelper helper) throws Throwable {
        var engine = HexArithmetics.getEngine();
        var env = new TestEnvironment(helper.getLevel());
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        benchmarkCold(engine, env, bean);
        benchmarkColdPattern(helper, bean);
        HexJitRuntime.invalidate("hot arithmetic benchmark");
        ServerConfig.hexJitThreshold = 2;
        for (int depth : new int[] {2, 64, 1024}) {
            var values = new ArrayList<Iota>();
            for (int i = 0; i < depth; i++) values.add(new DoubleIota(i));
            var image = new CastingImage(TreeList.from(values), 0, TreeList.empty(), false, false, 0, new CompoundTag());
            // Alternate rounds to avoid always measuring the same mode first.
            for (int round = 0; round < 6; round++) {
                ServerConfig.hexJitMode = (round & 1) == 0 ? ServerConfig.HexJitMode.OFF : ServerConfig.HexJitMode.AUTO;
                for (int i = 0; i < 10000; i++) blackhole = engine.run(Arithmetic.ADD, env, image, SpellContinuation.Done.INSTANCE);
                long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start = System.nanoTime();
                for (int i = 0; i < 50000; i++) blackhole = engine.run(Arithmetic.ADD, env, image, SpellContinuation.Done.INSTANCE);
                long elapsed = System.nanoTime() - start;
                long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
                System.out.println("HEXJIT_BENCH depth=" + depth + " mode=" + ServerConfig.hexJitMode + " round=" + round
                        + " ns/op=" + elapsed / 50000.0 + " bytes/op=" + bytes / 50000.0);
            }
        }
        benchmarkReferenceSpell(helper, bean);
    }

    private static void benchmarkColdPattern(GameTestHelper helper, com.sun.management.ThreadMXBean bean) {
        List<Iota> program = List.of(p(HexActions.EMPTY_LIST));
        ServerLevel level = helper.getLevel();
        var env = new TestEnvironment(level, null, false);
        String[] variants = {"none", "fastStackValidation", "reuseFrameTail", "stackAndFrameTail"};
        long[][] elapsed = new long[variants.length][8];
        long[][] allocated = new long[variants.length][8];
        for (int round = 0; round < 8; round++) {
            for (int offset = 0; offset < variants.length; offset++) {
                int variant = (round + offset) % variants.length;
                ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
                ServerConfig.hexJitCompileActions = false;
                ServerConfig.hexJitCoalesceDecorations = false;
                ServerConfig.hexJitBatchAddMotion = false;
                ServerConfig.hexJitFastAddMotionArguments = false;
                ServerConfig.hexJitFastStackValidation = (variant & 1) != 0;
                ServerConfig.hexJitReuseFrameTail = (variant & 2) != 0;
                for (int i = 0; i < 256; i++) runSinglePattern(env, program, level);
                long beforeBytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start = System.nanoTime();
                for (int i = 0; i < 1024; i++) runSinglePattern(env, program, level);
                elapsed[variant][round] = (System.nanoTime() - start) / 1024;
                allocated[variant][round] = (bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeBytes) / 1024;
            }
        }
        for (int variant = 0; variant < variants.length; variant++) {
            System.out.println("HEXJIT_COLD_PATTERN program=empty_list mode=AUTO variant=" + variants[variant]
                    + " medianNs/cast=" + median(elapsed[variant])
                    + " medianBytes/cast=" + median(allocated[variant]));
        }
        ServerConfig.hexJitFastAddMotionArguments = false;
        ServerConfig.hexJitFastStackValidation = false;
        ServerConfig.hexJitReuseFrameTail = false;
        ServerConfig.hexJitFastSpecialHandlerMath = false;
    }

    private static void runSinglePattern(TestEnvironment env, List<Iota> program, ServerLevel level) {
        CastingVM vm = new CastingVM(new CastingImage(TreeList.empty(), 0, TreeList.empty(),
                false, false, 0, new CompoundTag()), env);
        blackhole = vm.queueExecuteAndWrapIotas(program, level);
    }

    private static final String REFERENCE_SPELL_TEXT = "get_caster,get_caster,get_entity_look,num_3,mul,num_2,last_n_list,write/local,empty_list,duplicate,num_100(empty_list,eval/cc,num_4,fisherman,read/local,add,num_4,fisherman(add_motion)add,num_4,fisherman,duplicate,num_0,greater(num_1,sub,num_4,fisherman)()if,eval,num_4,fisherman,duplicate,eval)eval/cc,mask_v--vv,append,num_4,num_100,mul,write/local(empty_list,eval/cc,read/local,duplicate,num_0,greater(num_1,sub,write/local,rotate,duplicate,splat,eval,rotate_reverse)(mask_v-vv)if,eval,duplicate,eval)eval/cc,mask_v,const/null,write/local";

    private static void benchmarkReferenceSpell(GameTestHelper helper, com.sun.management.ThreadMXBean bean) {
        List<Iota> program = parseReferenceSpell();
        var caster = Objects.requireNonNull(net.minecraft.world.entity.EntityType.ARMOR_STAND.create(helper.getLevel()));
        BlockPos casterPos = helper.absolutePos(new BlockPos(1, 2, 1));
        caster.setPos(casterPos.getX() + 0.5, casterPos.getY(), casterPos.getZ() + 0.5);
        caster.setYRot(0);
        caster.setXRot(0);
        helper.assertTrue(helper.getLevel().addFreshEntity(caster), "Could not add the isolated benchmark caster");
        var patternEnv = new TestEnvironment(helper.getLevel(), caster);
        for (double value : new double[] {0, 1, 2, 3, 4, 100}) {
            var match = PatternRegistryManifest.matchPatternToSpecialHandler(numberPattern(value), patternEnv);
            helper.assertTrue(match != null && match.getFirst() instanceof SpecialHandlerNumberLiteral number
                            && number.getX() == value,
                    "Benchmark numeric token resolved to the wrong number handler: " + value + " -> " + match);
        }
        verifyMask(helper, patternEnv, "v--vv", List.of(false, true, true, false, false));
        verifyMask(helper, patternEnv, "v-vv", List.of(false, true, false, false));
        ServerConfig.hexJitThreshold = 2;
        ServerConfig.hexJitCoalesceDecorations = true;
        ServerConfig.hexJitBatchAddMotion = true;
        ServerConfig.hexJitFastAddMotionArguments = false;
        ServerConfig.hexJitFastStackValidation = false;
        ServerConfig.hexJitFastSpecialHandlerMath = false;
        HexJitRuntime.invalidate("reference spell benchmark");
        helper.assertTrue(JitCompatibility.motionBatchingReady(),
                "Entity motion batching target failed verification: " + JitCompatibility.status());
        helper.assertTrue(JitCompatibility.fastAddMotionReady(),
                "Add Motion argument target failed verification: " + JitCompatibility.status());
        helper.assertTrue(JitCompatibility.fastStackValidationReady(),
                "Iota stack validation target failed verification: " + JitCompatibility.status());

        // Run once in the isolated GameTest world before measuring, and compare the
        // interpreter and compiled paths from a fresh caster with the same state.
        Snapshot baseline = runReferenceSpell(helper, program, ServerConfig.HexJitMode.OFF, caster);
        ServerConfig.hexJitCoalesceDecorations = false;
        ServerConfig.hexJitBatchAddMotion = false;
        ServerConfig.hexJitFastAddMotionArguments = true;
        Snapshot fastArguments = runReferenceSpell(helper, program, ServerConfig.HexJitMode.AUTO, caster);
        helper.assertTrue(sameState(baseline, fastArguments), "Fast Add Motion arguments changed spell state\n"
                + baseline + "\n" + fastArguments);
        ServerConfig.hexJitFastAddMotionArguments = false;
        ServerConfig.hexJitFastStackValidation = true;
        Snapshot fastValidation = runReferenceSpell(helper, program, ServerConfig.HexJitMode.AUTO, caster);
        helper.assertTrue(sameState(baseline, fastValidation), "Indexed stack validation changed spell state\n"
                + baseline + "\n" + fastValidation);
        ServerConfig.hexJitFastStackValidation = false;
        ServerConfig.hexJitFastAddMotionArguments = true;
        ServerConfig.hexJitFastStackValidation = true;
        ServerConfig.hexJitReuseFrameTail = true;
        Snapshot fastBoth = runReferenceSpell(helper, program, ServerConfig.HexJitMode.AUTO, caster);
        helper.assertTrue(sameState(baseline, fastBoth), "Combined Hex JIT fast paths changed spell state\n"
                + baseline + "\n" + fastBoth);
        ServerConfig.hexJitFastSpecialHandlerMath = true;
        Snapshot fastSpecialMath = runReferenceSpell(helper, program, ServerConfig.HexJitMode.AUTO, caster);
        helper.assertTrue(sameState(baseline, fastSpecialMath), "Special-handler math optimization changed spell state\n"
                + baseline + "\n" + fastSpecialMath);
        ServerConfig.hexJitFastSpecialHandlerMath = false;
        ServerConfig.hexJitFastAddMotionArguments = false;
        ServerConfig.hexJitFastStackValidation = false;
        ServerConfig.hexJitReuseFrameTail = false;
        ServerConfig.hexJitCoalesceDecorations = true;
        ServerConfig.hexJitBatchAddMotion = true;
        Snapshot compiled = runReferenceSpell(helper, program, ServerConfig.HexJitMode.AUTO, caster);
        helper.assertTrue(sameState(baseline, compiled), "Reference spell changed under JIT\n" + baseline + "\n" + compiled);
        helper.assertTrue(ExecutionScope.lastMotionPushes() > 1000 && ExecutionScope.lastMotionWrites() == 1,
                "Add Motion batching did not collapse ordered writes: pushes=" + ExecutionScope.lastMotionPushes()
                        + ", writes=" + ExecutionScope.lastMotionWrites());
        helper.assertTrue(compiled.particleCalls < baseline.particleCalls,
                "Identical AddMotion particle sprays were not coalesced: baseline=" + baseline.particleCalls
                        + ", JIT=" + compiled.particleCalls);
        ServerConfig.hexJitCompileActions = true;
        ServerConfig.hexJitSkipObservers = true;
        ServerConfig.hexJitFastAddMotionArguments = true;
        ServerConfig.hexJitMemoAddMotionNormalization = true;
        ServerConfig.hexJitFastStackValidation = true;
        ServerConfig.hexJitCacheStackMetrics = true;
        ServerConfig.hexJitReuseFrameTail = true;
        ServerConfig.hexJitFastSpecialHandlerMath = true;
        ServerConfig.hexJitFastSpecialHandlerLookup = true;
        ServerConfig.hexJitFastNumberLiterals = true;
        Snapshot fullyEnabled = runReferenceSpell(helper, program, ServerConfig.HexJitMode.AUTO, caster);
        helper.assertTrue(sameState(baseline, fullyEnabled),
                "Full Hex JIT feature set changed reference spell state\n" + baseline + "\n" + fullyEnabled);
        CompoundTag seededUserData = new CompoundTag();
        seededUserData.putString("fixture", "preserve-me");
        CompoundTag nestedUserData = new CompoundTag();
        nestedUserData.putIntArray("values", new int[] {3, 5, 8, 13});
        seededUserData.put("nested", nestedUserData);
        CompoundTag originalUserData = seededUserData.copy();
        Snapshot seededBaseline = runReferenceSpell(helper, program, ServerConfig.HexJitMode.OFF, caster, seededUserData);
        Snapshot seededFull = runReferenceSpell(helper, program, ServerConfig.HexJitMode.AUTO, caster, seededUserData);
        helper.assertTrue(sameState(seededBaseline, seededFull),
                "Full Hex JIT feature set changed a reference spell with nested userData\n"
                        + seededBaseline + "\n" + seededFull);
        helper.assertTrue(seededUserData.equals(originalUserData),
                "Reference spell differential test mutated its caller-owned userData");
        Snapshot insufficientMotionMediaBaseline = runReferenceSpell(helper, program,
                ServerConfig.HexJitMode.OFF, caster, new CompoundTag(), 0L);
        Snapshot insufficientMotionMediaJit = runReferenceSpell(helper, program,
                ServerConfig.HexJitMode.AUTO, caster, new CompoundTag(), 0L);
        helper.assertTrue(sameState(insufficientMotionMediaBaseline, insufficientMotionMediaJit)
                        && insufficientMotionMediaBaseline.resolution.startsWith("ERRORED:")
                        && insufficientMotionMediaBaseline.nonzeroMediaChecks > 0,
                "Fast Add Motion changed the insufficient-media mishap or remaining spell state\n"
                        + insufficientMotionMediaBaseline + "\n" + insufficientMotionMediaJit);
        ServerConfig.hexJitCompileActions = false;
        ServerConfig.hexJitSkipObservers = false;
        ServerConfig.hexJitFastAddMotionArguments = false;
        ServerConfig.hexJitMemoAddMotionNormalization = true;
        ServerConfig.hexJitFastStackValidation = false;
        ServerConfig.hexJitCacheStackMetrics = true;
        ServerConfig.hexJitReuseFrameTail = false;
        ServerConfig.hexJitFastSpecialHandlerMath = false;
        ServerConfig.hexJitFastSpecialHandlerLookup = false;
        ServerConfig.hexJitFastNumberLiterals = true;
        helper.assertTrue("EVALUATED:true".equals(baseline.resolution)
                        && baseline.trace.stream().noneMatch(line -> line.startsWith("mishap:")),
                "Reference spell mishapped in the test context: resolution=" + baseline.resolution
                        + ", mishaps=" + baseline.trace.stream().filter(line -> line.startsWith("mishap:")).toList());
        long mediaChecks = baseline.mediaChecks;
        helper.assertTrue(mediaChecks >= 1000,
                "Reference spell did not exercise its repeated action loop: media checks=" + mediaChecks);
        helper.assertTrue(baseline.nonzeroMediaChecks > 0 && (baseline.nonzeroMediaChecks & 1) == 0
                        && baseline.externalState.contains(":hurtMarked=true:"),
                "Reference spell did not apply its repeated motion effect: costs=" + baseline.nonzeroMediaChecks
                        + ", caster=" + baseline.externalState);
        // The correctness pass above deliberately uses a low threshold to exercise add-on call sites.
        // Start the performance matrix from a fresh cache with the server's default hot threshold.
        ServerConfig.hexJitThreshold = 64;
        HexJitRuntime.invalidate("reference spell benchmark matrix");
        System.out.println("HEXJIT_SPELL_ANALYSIS workload=reference_fisherman_loop iotas=" + program.size()
                + " mediaChecks=" + mediaChecks + " nonzeroCostChecks=" + baseline.nonzeroMediaChecks + " resolution=" + baseline.resolution
                + " mishaps=" + baseline.trace.stream().filter(line -> line.startsWith("mishap:")).toList()
                + " syntheticCaster=" + baseline.externalState);

        int warmupCasts = Integer.getInteger("cmi.hexjit.benchmarkWarmup", 6);
        int measuredCasts = Integer.getInteger("cmi.hexjit.benchmarkMeasuredCasts", 24);
        List<SpellBenchCase> cases = List.of(
                new SpellBenchCase("OFF", ServerConfig.HexJitMode.OFF, false, false, false, false, false, false, false),
                new SpellBenchCase("AUTO_ARITHMETIC", ServerConfig.HexJitMode.AUTO, false, false, false, false, false, false, false),
                new SpellBenchCase("AUTO_FAST_ADD_MOTION_ARGUMENTS", ServerConfig.HexJitMode.AUTO,
                        false, false, false, true, false, false, false),
                new SpellBenchCase("AUTO_FAST_STACK_VALIDATION", ServerConfig.HexJitMode.AUTO,
                        false, false, false, false, true, false, false),
                new SpellBenchCase("AUTO_FAST_FRAME_TAIL", ServerConfig.HexJitMode.AUTO,
                        false, false, false, false, false, true, false),
                new SpellBenchCase("AUTO_FAST_SPECIAL_HANDLER_MATH", ServerConfig.HexJitMode.AUTO,
                        false, false, false, false, false, false, true),
                new SpellBenchCase("AUTO_STACK_AND_FRAME_TAIL", ServerConfig.HexJitMode.AUTO,
                        false, false, false, false, true, true, false),
                new SpellBenchCase("AUTO_ALL_FAST_PATHS", ServerConfig.HexJitMode.AUTO,
                        false, false, false, true, true, true, false),
                new SpellBenchCase("AUTO_ALL_NO_NORMALIZATION", ServerConfig.HexJitMode.AUTO,
                        false, false, false, true, true, true, false),
                new SpellBenchCase("AUTO_ALL_PLUS_SPECIAL_HANDLER_LOOKUP_CACHE", ServerConfig.HexJitMode.AUTO,
                        false, false, false, true, true, true, false, true),
                new SpellBenchCase("AUTO_ALL_PLUS_NUMBER_LITERAL", ServerConfig.HexJitMode.AUTO,
                        false, false, false, true, true, true, false, false, true),
                new SpellBenchCase("AUTO_ALL_PLUS_STACK_METRIC_CACHE", ServerConfig.HexJitMode.AUTO,
                        false, false, false, true, true, true, false, false, false, true),
                new SpellBenchCase("AUTO_STACK_CACHE_PLUS_SPECIAL_MATH", ServerConfig.HexJitMode.AUTO,
                        false, false, false, true, true, true, true, false, false, true),
                new SpellBenchCase("AUTO_STACK_CACHE_PLUS_NUMBER_LITERAL", ServerConfig.HexJitMode.AUTO,
                        false, false, false, true, true, true, false, false, true, true),
                new SpellBenchCase("AUTO_STACK_CACHE_PLUS_BOTH_MATH_AND_NUMBER", ServerConfig.HexJitMode.AUTO,
                        false, false, false, true, true, true, true, false, true, true),
                new SpellBenchCase("AUTO_COMPILE_ACTIONS", ServerConfig.HexJitMode.AUTO,
                        true, false, false, false, false, false, false),
                new SpellBenchCase("AUTO_ALL_WITH_ACTION_JIT", ServerConfig.HexJitMode.AUTO,
                        true, false, false, true, true, true, false),
                new SpellBenchCase("AUTO_ALL_PLUS_MOTION_BATCH", ServerConfig.HexJitMode.AUTO,
                        false, false, true, true, true, true, false),
                new SpellBenchCase("AUTO_ALL_PLUS_DECORATION_COALESCING", ServerConfig.HexJitMode.AUTO,
                        false, true, false, true, true, true, false),
                new SpellBenchCase("AUTO_ALL_PLUS_BOTH_BATCHES", ServerConfig.HexJitMode.AUTO,
                        false, true, true, true, true, true, false),
                new SpellBenchCase("AUTO_ALL_PLUS_SPECIAL_HANDLER_MATH", ServerConfig.HexJitMode.AUTO,
                        false, false, false, true, true, true, true),
                new SpellBenchCase("AUTO_FULL_FEATURES_NO_ACTION_JIT", ServerConfig.HexJitMode.AUTO,
                        false, true, true, true, true, true, true, true, true, true),
                new SpellBenchCase("AUTO_FULL_FEATURES_NO_BATCHING", ServerConfig.HexJitMode.AUTO,
                        true, false, false, true, true, true, true, true, true, true),
                new SpellBenchCase("AUTO_FULL_FEATURES_NO_MOTION_BATCH", ServerConfig.HexJitMode.AUTO,
                        true, true, false, true, true, true, true, true, true, true),
                new SpellBenchCase("AUTO_FULL_FEATURES_NO_DECORATION_COALESCING", ServerConfig.HexJitMode.AUTO,
                        true, false, true, true, true, true, true, true, true, true),
                new SpellBenchCase("AUTO_FULL_FEATURES_NO_NORMAL_PATTERN_CACHE", ServerConfig.HexJitMode.AUTO,
                        true, true, true, true, true, true, true, true, true, true),
                new SpellBenchCase(FULL_FEATURE_BENCH_CASE, ServerConfig.HexJitMode.AUTO,
                        true, true, true, true, true, true, true, true, true, true));
        String selectedCases = System.getProperty("cmi.hexjit.benchmarkCases");
        if (selectedCases != null && !selectedCases.isBlank()) {
            List<String> requested = List.of(selectedCases.split(","));
            Map<String, SpellBenchCase> byName = new HashMap<>();
            for (SpellBenchCase benchCase : cases) byName.put(benchCase.name, benchCase);
            cases = requested.stream().map(byName::get).filter(java.util.Objects::nonNull).toList();
            if (cases.size() != requested.size() || new HashSet<>(requested).size() != requested.size()) {
                throw new IllegalArgumentException("Unknown or duplicate Hex JIT benchmark case: " + selectedCases);
            }
        }
        if (warmupCasts < 0 || measuredCasts < 1) {
            throw new IllegalArgumentException("Invalid Hex JIT benchmark counts: warmup=" + warmupCasts
                    + ", measured=" + measuredCasts);
        }
        long[][] elapsed = new long[cases.size()][measuredCasts];
        long[][] allocated = new long[cases.size()][measuredCasts];
        long[] warmupElapsed = new long[cases.size()];
        long[] particleCalls = new long[cases.size()];
        long[] lastCastParticleCalls = new long[1];
        for (int cast = 0; cast < warmupCasts; cast++) for (int offset = 0; offset < cases.size(); offset++) {
            int index = (cast + offset) % cases.size();
            SpellBenchCase benchCase = cases.get(index);
            ServerConfig.hexJitMode = benchCase.mode;
            ServerConfig.hexJitCompileActions = benchCase.compileActions;
            ServerConfig.hexJitSkipObservers = hasFullObserverOptimization(benchCase.name);
            ServerConfig.hexJitCoalesceDecorations = benchCase.coalesceDecorations;
            ServerConfig.hexJitBatchAddMotion = benchCase.batchAddMotion;
            ServerConfig.hexJitFastAddMotionArguments = benchCase.fastAddMotionArguments;
            ServerConfig.hexJitMemoAddMotionNormalization = !benchCase.name.equals("AUTO_ALL_NO_NORMALIZATION");
            ServerConfig.hexJitFastStackValidation = benchCase.fastStackValidation;
            ServerConfig.hexJitCacheStackMetrics = benchCase.cacheStackMetrics;
            ServerConfig.hexJitReuseFrameTail = benchCase.reuseFrameTail;
            ServerConfig.hexJitFastSpecialHandlerMath = benchCase.fastSpecialHandlerMath;
            ServerConfig.hexJitFastSpecialHandlerLookup = benchCase.fastSpecialHandlerLookup;
            ServerConfig.hexJitFastNumberLiterals = benchCase.fastNumberLiterals;
            ServerConfig.hexJitCacheNormalPatternLookup = !benchCase.name.equals("AUTO_FULL_FEATURES_NO_NORMAL_PATTERN_CACHE");
            long start = System.nanoTime();
            blackhole = benchmarkReferenceCast(helper, program, benchCase.mode, caster, lastCastParticleCalls);
            warmupElapsed[index] += System.nanoTime() - start;
        }
        for (int cast = 0; cast < measuredCasts; cast++) {
            for (int offset = 0; offset < cases.size(); offset++) {
                int index = (cast + offset) % cases.size();
                SpellBenchCase benchCase = cases.get(index);
                ServerConfig.hexJitMode = benchCase.mode;
                ServerConfig.hexJitCompileActions = benchCase.compileActions;
                ServerConfig.hexJitSkipObservers = hasFullObserverOptimization(benchCase.name);
                ServerConfig.hexJitCoalesceDecorations = benchCase.coalesceDecorations;
                ServerConfig.hexJitBatchAddMotion = benchCase.batchAddMotion;
                ServerConfig.hexJitFastAddMotionArguments = benchCase.fastAddMotionArguments;
                ServerConfig.hexJitMemoAddMotionNormalization = !benchCase.name.equals("AUTO_ALL_NO_NORMALIZATION");
                ServerConfig.hexJitFastStackValidation = benchCase.fastStackValidation;
                ServerConfig.hexJitCacheStackMetrics = benchCase.cacheStackMetrics;
                ServerConfig.hexJitReuseFrameTail = benchCase.reuseFrameTail;
                ServerConfig.hexJitFastSpecialHandlerMath = benchCase.fastSpecialHandlerMath;
                ServerConfig.hexJitFastSpecialHandlerLookup = benchCase.fastSpecialHandlerLookup;
                ServerConfig.hexJitFastNumberLiterals = benchCase.fastNumberLiterals;
                ServerConfig.hexJitCacheNormalPatternLookup = !benchCase.name.equals("AUTO_FULL_FEATURES_NO_NORMAL_PATTERN_CACHE");
                long beforeBytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start = System.nanoTime();
                blackhole = benchmarkReferenceCast(helper, program, benchCase.mode, caster, lastCastParticleCalls);
                elapsed[index][cast] = System.nanoTime() - start;
                allocated[index][cast] = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeBytes;
                particleCalls[index] += lastCastParticleCalls[0];
            }
        }
        for (int index = 0; index < cases.size(); index++) {
            SpellBenchCase benchCase = cases.get(index);
            System.out.println("HEXJIT_SPELL_BENCH workload=reference_fisherman_loop mode=" + benchCase.name
                    + " compileActions=" + benchCase.compileActions + " coalesceDecorations=" + benchCase.coalesceDecorations
                    + " batchAddMotion=" + benchCase.batchAddMotion
                    + " fastAddMotionArguments=" + benchCase.fastAddMotionArguments
                    + " memoAddMotionNormalization=" + !benchCase.name.equals("AUTO_ALL_NO_NORMALIZATION")
                    + " fastStackValidation=" + benchCase.fastStackValidation
                    + " cacheStackMetrics=" + benchCase.cacheStackMetrics
                    + " reuseFrameTail=" + benchCase.reuseFrameTail
                    + " fastSpecialHandlerMath=" + benchCase.fastSpecialHandlerMath
                    + " fastSpecialHandlerLookup=" + benchCase.fastSpecialHandlerLookup
                    + " fastNumberLiterals=" + benchCase.fastNumberLiterals
                    + " cacheNormalPatternLookup=" + !benchCase.name.equals("AUTO_FULL_FEATURES_NO_NORMAL_PATTERN_CACHE")
                    + " skipDeclaredObservers=" + hasFullObserverOptimization(benchCase.name)
                    + " warmupCasts=" + warmupCasts
                    + " warmupNs/cast=" + warmupElapsed[index] / (double) warmupCasts
                    + " measuredCasts=" + measuredCasts + " meanNs/cast=" + average(elapsed[index])
                    + " medianNs/cast=" + median(elapsed[index]) + " p95Ns/cast=" + percentile(elapsed[index], 0.95)
                    + " meanBytes/cast=" + average(allocated[index]) + " medianBytes/cast=" + median(allocated[index])
                    + " p95Bytes/cast=" + percentile(allocated[index], 0.95)
                    + " emittedParticles/cast=" + particleCalls[index] / (double) measuredCasts);
        }
        ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
        ServerConfig.hexJitCompileActions = true;
        ServerConfig.hexJitSkipObservers = false;
        System.out.println("HEXJIT_SPELL_JIT_STATS " + HexJitRuntime.status());
        caster.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
    }

    private static double average(long[] samples) {
        long total = 0;
        for (long sample : samples) total += sample;
        return total / (double) samples.length;
    }

    private static double median(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return (sorted.length & 1) == 0 ? (sorted[middle - 1] + sorted[middle]) / 2.0 : sorted[middle];
    }

    private static long percentile(long[] samples, double percentile) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1)];
    }

    private static Snapshot runReferenceSpell(GameTestHelper helper, List<Iota> program, ServerConfig.HexJitMode mode,
                                              LivingEntity caster) {
        return runReferenceSpell(helper, program, mode, caster, new CompoundTag(), Long.MAX_VALUE / 4);
    }

    private static Snapshot runReferenceSpell(GameTestHelper helper, List<Iota> program, ServerConfig.HexJitMode mode,
                                              LivingEntity caster, CompoundTag initialUserData) {
        return runReferenceSpell(helper, program, mode, caster, initialUserData, Long.MAX_VALUE / 4);
    }

    private static Snapshot runReferenceSpell(GameTestHelper helper, List<Iota> program, ServerConfig.HexJitMode mode,
                                              LivingEntity caster, CompoundTag initialUserData, long initialMedia) {
        ServerConfig.hexJitMode = mode;
        caster.setDeltaMovement(Vec3.ZERO);
        caster.hurtMarked = false;
        var env = new TestEnvironment(helper.getLevel(), caster);
        env.remainingMedia = initialMedia;
        env.getWorld().random.setSeed(9128374L);
        CastingVM vm = new CastingVM(new CastingImage(TreeList.empty(), 0, TreeList.empty(),
                false, false, 0, initialUserData.copy()), env);
        ExecutionClientView view = vm.queueExecuteAndWrapIotas(program, helper.getLevel());
        Vec3 finalMotion = caster.getDeltaMovement();
        String motion = finalMotion.x + "," + finalMotion.y + "," + finalMotion.z
                + ":hurtMarked=" + caster.hurtMarked + ":observerMotionHash=" + env.observerMotionHash
                + ":mediaRemaining=" + env.remainingMedia;
        return new Snapshot(CastingImage.Companion.getCODEC().encodeStart(NbtOps.INSTANCE, vm.getImage()).getOrThrow(),
                List.copyOf(env.trace), view.getResolutionType() + ":" + view.isStackClear(), env.getWorld().random.nextLong(), motion,
                env.mediaChecks, env.nonzeroMediaChecks, env.particleCalls,
                encodeContinuation(env.lastContinuation), env.continuationSteps);
    }

    private static ExecutionClientView benchmarkReferenceCast(GameTestHelper helper, List<Iota> program,
                                                               ServerConfig.HexJitMode mode, LivingEntity caster,
                                                               long[] particleCalls) {
        ServerConfig.hexJitMode = mode;
        caster.setDeltaMovement(Vec3.ZERO);
        caster.hurtMarked = false;
        var env = new TestEnvironment(helper.getLevel(), caster, false);
        env.getWorld().random.setSeed(9128374L);
        CastingVM vm = new CastingVM(new CastingImage(TreeList.empty(), 0, TreeList.empty(),
                false, false, 0, new CompoundTag()), env);
        ExecutionClientView result = vm.queueExecuteAndWrapIotas(program, helper.getLevel());
        particleCalls[0] = env.particleCalls;
        return result;
    }

    private static Tag encodeContinuation(SpellContinuation continuation) {
        return SpellContinuation.getCODEC().encodeStart(NbtOps.INSTANCE, continuation).getOrThrow();
    }

    private static void verifyMask(GameTestHelper helper, TestEnvironment env, String mask, List<Boolean> expected) {
        var match = PatternRegistryManifest.matchPatternToSpecialHandler(maskPattern(mask), env);
        helper.assertTrue(match != null && match.getFirst() instanceof SpecialHandlerMask,
                "Benchmark mask did not resolve to the mask handler: " + mask);
        var decoded = ((SpecialHandlerMask) match.getFirst()).getMask();
        var actual = new ArrayList<Boolean>(decoded.size());
        for (int i = 0; i < decoded.size(); i++) actual.add(decoded.getBoolean(i));
        helper.assertTrue(actual.equals(expected), "Benchmark mask encoding mismatch: " + mask + " -> " + actual);
    }

    private static void verifyHexDirMath(GameTestHelper helper) {
        HexDir[] directions = HexDir.values();
        HexAngle[] angles = HexAngle.values();
        ServerConfig.hexJitFastSpecialHandlerMath = true;
        for (HexDir direction : directions) {
            for (HexAngle angle : angles) {
                HexDir expected = directions[Math.floorMod(direction.ordinal() + angle.ordinal(), directions.length)];
                helper.assertTrue(direction.rotatedBy(angle) == expected,
                        "Cached direction rotation changed " + direction + " * " + angle);
            }
            for (HexDir other : directions) {
                HexAngle expected = angles[Math.floorMod(direction.ordinal() - other.ordinal(), angles.length)];
                helper.assertTrue(direction.angleFrom(other) == expected,
                        "Cached angle lookup changed " + direction + " - " + other);
            }
        }
        ServerConfig.hexJitFastSpecialHandlerMath = false;
    }

    private static void verifyAddMotionNormalizationCache(GameTestHelper helper) throws InterruptedException {
        Vec3 input = new Vec3(1, 2, 3);
        Vec3 normalized = input.normalize();
        helper.assertTrue(AddMotionNormalizationCache.activeCache() == null,
                "Add Motion normalization cache leaked across casts");
        AddMotionNormalizationCache.beginCast();
        AddMotionNormalizationCache.Cache outer = AddMotionNormalizationCache.activeCache();
        outer.remember(input, normalized);
        helper.assertTrue(outer.cached(input) == normalized,
                "Add Motion normalization cache missed an identical input");
        AtomicReference<AddMotionNormalizationCache.Cache> offThreadCache = new AtomicReference<>();
        Thread cacheReader = TEST_THREADS.newThread(() -> offThreadCache.set(AddMotionNormalizationCache.activeCache()));
        cacheReader.start();
        cacheReader.join();
        helper.assertTrue(offThreadCache.get() == null,
                "Server-thread Add Motion cache leaked into another thread");
        AddMotionNormalizationCache.beginCast();
        AddMotionNormalizationCache.endCast();
        helper.assertTrue(AddMotionNormalizationCache.activeCache().cached(input) == normalized,
                "Nested cast ended the outer normalization cache lifetime");
        AddMotionNormalizationCache.endCast();
        helper.assertTrue(AddMotionNormalizationCache.activeCache() == null,
                "Add Motion normalization cache survived its outer cast");

        try (ExecutionScope scope = ExecutionScope.enter(false)) {
            AtomicReference<ExecutionScope> offThreadScope = new AtomicReference<>();
            Thread scopeReader = TEST_THREADS.newThread(() -> offThreadScope.set(ExecutionScope.current()));
            scopeReader.start();
            scopeReader.join();
            helper.assertTrue(ExecutionScope.current() == scope && offThreadScope.get() == null,
                    "Server execution scope was not isolated by thread");
        }
    }

    private static void verifyWorldMutationParity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos conjurePos = helper.absolutePos(new BlockPos(1, 4, 1));
        BlockPos breakPos = helper.absolutePos(new BlockPos(2, 4, 1));
        Vec3Iota conjureTarget = new Vec3Iota(Vec3.atCenterOf(conjurePos));
        Vec3Iota breakTarget = new Vec3Iota(Vec3.atCenterOf(breakPos));

        enableFullFeatureSet();
        level.setBlock(conjurePos, Blocks.AIR.defaultBlockState(), 3);
        WorldCastSnapshot placedByInterpreter = castWorldSpell(helper, conjurePos, conjureTarget,
                HexActions.CONJURE_BLOCK, ServerConfig.HexJitMode.OFF);
        level.setBlock(conjurePos, Blocks.AIR.defaultBlockState(), 3);
        WorldCastSnapshot placedByJit = castWorldSpell(helper, conjurePos, conjureTarget,
                HexActions.CONJURE_BLOCK, ServerConfig.HexJitMode.AUTO);
        helper.assertTrue(sameState(placedByInterpreter.cast, placedByJit.cast)
                        && placedByInterpreter.world.equals(placedByJit.world),
                "Conjure Block world state differs between interpreter and full JIT\n"
                        + placedByInterpreter + "\n" + placedByJit);
        helper.assertTrue(placedByInterpreter.cast.resolution.startsWith("EVALUATED:")
                        && blockAt(placedByInterpreter, BlockPos.ZERO).state.is(HexBlocks.CONJURED_BLOCK.get()),
                "Conjure Block parity case did not successfully place its block: " + placedByInterpreter);

        level.setBlock(conjurePos, Blocks.AIR.defaultBlockState(), 3);
        WorldCastSnapshot inventoryPlaceInterpreter = castWorldSpell(helper, conjurePos, conjureTarget,
                HexActions.PLACE_BLOCK, ServerConfig.HexJitMode.OFF, Long.MAX_VALUE / 4,
                new ItemStack(Blocks.STONE, 2));
        level.setBlock(conjurePos, Blocks.AIR.defaultBlockState(), 3);
        WorldCastSnapshot inventoryPlaceJit = castWorldSpell(helper, conjurePos, conjureTarget,
                HexActions.PLACE_BLOCK, ServerConfig.HexJitMode.AUTO, Long.MAX_VALUE / 4,
                new ItemStack(Blocks.STONE, 2));
        helper.assertTrue(sameState(inventoryPlaceInterpreter.cast, inventoryPlaceJit.cast)
                        && inventoryPlaceInterpreter.world.equals(inventoryPlaceJit.world),
                "Inventory-backed Place Block world state or consumption differs between interpreter and full JIT\n"
                        + inventoryPlaceInterpreter + "\n" + inventoryPlaceJit);
        helper.assertTrue(inventoryPlaceInterpreter.cast.resolution.startsWith("EVALUATED:")
                        && blockAt(inventoryPlaceInterpreter, BlockPos.ZERO).state.is(Blocks.STONE)
                        && inventoryPlaceInterpreter.cast.externalState.endsWith("[1]"),
                "Inventory-backed Place Block parity case did not place the block and consume one item: "
                        + inventoryPlaceInterpreter);
        level.setBlock(conjurePos, Blocks.AIR.defaultBlockState(), 3);

        level.setBlock(conjurePos, Blocks.AIR.defaultBlockState(), 3);
        WorldCastSnapshot insufficientMediaInterpreter = castWorldSpell(helper, conjurePos, conjureTarget,
                HexActions.CONJURE_BLOCK, ServerConfig.HexJitMode.OFF, 0);
        level.setBlock(conjurePos, Blocks.AIR.defaultBlockState(), 3);
        WorldCastSnapshot insufficientMediaJit = castWorldSpell(helper, conjurePos, conjureTarget,
                HexActions.CONJURE_BLOCK, ServerConfig.HexJitMode.AUTO, 0);
        helper.assertTrue(sameState(insufficientMediaInterpreter.cast, insufficientMediaJit.cast)
                        && insufficientMediaInterpreter.world.equals(insufficientMediaJit.world),
                "Insufficient-media payment or world state differs between interpreter and full JIT\n"
                        + insufficientMediaInterpreter + "\n" + insufficientMediaJit);
        helper.assertTrue(insufficientMediaInterpreter.cast.resolution.startsWith("ERRORED:")
                        && insufficientMediaInterpreter.cast.externalState.startsWith("0:0:")
                        && blockAt(insufficientMediaInterpreter, BlockPos.ZERO).state.isAir(),
                "Zero-media spell should error without changing the world: " + insufficientMediaInterpreter);

        level.setBlock(conjurePos, Blocks.AIR.defaultBlockState(), 3);
        WorldCastSnapshot simulatedInterpreter = castWorldSpell(helper, conjurePos, conjureTarget,
                HexActions.CONJURE_BLOCK, ServerConfig.HexJitMode.OFF, Long.MAX_VALUE / 4, true);
        level.setBlock(conjurePos, Blocks.AIR.defaultBlockState(), 3);
        WorldCastSnapshot simulatedJit = castWorldSpell(helper, conjurePos, conjureTarget,
                HexActions.CONJURE_BLOCK, ServerConfig.HexJitMode.AUTO, Long.MAX_VALUE / 4, true);
        helper.assertTrue(sameState(simulatedInterpreter.cast, simulatedJit.cast)
                        && simulatedInterpreter.world.equals(simulatedJit.world),
                "Simulated Conjure Block differs between interpreter and full JIT\n"
                        + simulatedInterpreter + "\n" + simulatedJit);
        helper.assertTrue(simulatedInterpreter.cast.resolution.startsWith("SIMULATED:")
                        && simulatedInterpreter.cast.externalState.startsWith(Long.MAX_VALUE / 4 + ":0:")
                        && blockAt(simulatedInterpreter, BlockPos.ZERO).state.isAir()
                        && simulatedInterpreter.cast.particleCalls == 0,
                "Simulated Conjure Block must not consume media, emit particles, or change the world: "
                        + simulatedInterpreter);

        long partialMedia = HexCompat.getDustMediaAmount() / 2;
        level.setBlock(conjurePos, Blocks.AIR.defaultBlockState(), 3);
        WorldCastSnapshot partiallyPaidInterpreter = castWorldSpell(helper, conjurePos, conjureTarget,
                HexActions.CONJURE_BLOCK, ServerConfig.HexJitMode.OFF, partialMedia);
        level.setBlock(conjurePos, Blocks.AIR.defaultBlockState(), 3);
        WorldCastSnapshot partiallyPaidJit = castWorldSpell(helper, conjurePos, conjureTarget,
                HexActions.CONJURE_BLOCK, ServerConfig.HexJitMode.AUTO, partialMedia);
        helper.assertTrue(sameState(partiallyPaidInterpreter.cast, partiallyPaidJit.cast)
                        && partiallyPaidInterpreter.world.equals(partiallyPaidJit.world),
                "Partial insufficient-media payment or world state differs between interpreter and full JIT\n"
                        + partiallyPaidInterpreter + "\n" + partiallyPaidJit);
        helper.assertTrue(partiallyPaidInterpreter.cast.resolution.startsWith("ERRORED:")
                        && partiallyPaidInterpreter.cast.externalState.startsWith("0:" + partialMedia + ":")
                        && partiallyPaidInterpreter.world.blocks.stream()
                        .filter(block -> block.relativePos.equals(BlockPos.ZERO))
                        .allMatch(block -> block.state.isAir()),
                "Partial payment must be consumed before the spell errors, without placing a block: "
                        + partiallyPaidInterpreter);

        discardNearbyDrops(level, breakPos);
        level.setBlock(breakPos, Blocks.DIRT.defaultBlockState(), 3);
        WorldCastSnapshot brokenByInterpreter = castWorldSpell(helper, breakPos, breakTarget,
                HexActions.BREAK_BLOCK, ServerConfig.HexJitMode.OFF);
        discardNearbyDrops(level, breakPos);
        level.setBlock(breakPos, Blocks.DIRT.defaultBlockState(), 3);
        WorldCastSnapshot brokenByJit = castWorldSpell(helper, breakPos, breakTarget,
                HexActions.BREAK_BLOCK, ServerConfig.HexJitMode.AUTO);
        helper.assertTrue(sameState(brokenByInterpreter.cast, brokenByJit.cast)
                        && brokenByInterpreter.world.equals(brokenByJit.world),
                "Break Block world state or drops differ between interpreter and full JIT\n"
                        + brokenByInterpreter + "\n" + brokenByJit);
        helper.assertTrue(brokenByInterpreter.cast.resolution.startsWith("EVALUATED:")
                        && blockAt(brokenByInterpreter, BlockPos.ZERO).state.isAir()
                        && !brokenByInterpreter.world.drops.isEmpty(),
                "Break Block parity case did not break the block and capture its drops: " + brokenByInterpreter);

        discardNearbyDrops(level, breakPos);
        level.setBlock(conjurePos, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(breakPos, Blocks.AIR.defaultBlockState(), 3);
        ServerConfig.hexJitCompileActions = true;
        ServerConfig.hexJitSkipObservers = false;
        ServerConfig.hexJitCoalesceDecorations = true;
        ServerConfig.hexJitBatchAddMotion = false;
        ServerConfig.hexJitFastAddMotionArguments = false;
        ServerConfig.hexJitMemoAddMotionNormalization = true;
        ServerConfig.hexJitFastStackValidation = false;
        ServerConfig.hexJitCacheStackMetrics = true;
        ServerConfig.hexJitReuseFrameTail = false;
        ServerConfig.hexJitFastSpecialHandlerMath = false;
        ServerConfig.hexJitFastSpecialHandlerLookup = false;
        ServerConfig.hexJitFastNumberLiterals = true;
        ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
    }

    private static void enableFullFeatureSet() {
        ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
        ServerConfig.hexJitCompileActions = true;
        ServerConfig.hexJitSkipObservers = true;
        ServerConfig.hexJitCoalesceDecorations = true;
        ServerConfig.hexJitBatchAddMotion = true;
        ServerConfig.hexJitFastAddMotionArguments = true;
        ServerConfig.hexJitMemoAddMotionNormalization = true;
        ServerConfig.hexJitFastStackValidation = true;
        ServerConfig.hexJitCacheStackMetrics = true;
        ServerConfig.hexJitReuseFrameTail = true;
        ServerConfig.hexJitFastSpecialHandlerMath = true;
        ServerConfig.hexJitFastSpecialHandlerLookup = true;
        ServerConfig.hexJitFastNumberLiterals = true;
    }

    private static WorldCastSnapshot castWorldSpell(GameTestHelper helper, BlockPos pos, Vec3Iota target,
                                                      Holder<ActionRegistryEntry> action,
                                                      ServerConfig.HexJitMode mode) {
        return castWorldSpell(helper, pos, target, action, mode, Long.MAX_VALUE / 4);
    }

    private static WorldCastSnapshot castWorldSpell(GameTestHelper helper, BlockPos pos, Vec3Iota target,
                                                      Holder<ActionRegistryEntry> action,
                                                      ServerConfig.HexJitMode mode, long initialMedia) {
        return castWorldSpell(helper, pos, target, action, mode, initialMedia, false, new ItemStack[0]);
    }

    private static WorldCastSnapshot castWorldSpell(GameTestHelper helper, BlockPos pos, Vec3Iota target,
                                                      Holder<ActionRegistryEntry> action,
                                                      ServerConfig.HexJitMode mode, long initialMedia,
                                                      boolean simulateNext) {
        return castWorldSpell(helper, pos, target, action, mode, initialMedia, simulateNext, new ItemStack[0]);
    }

    private static WorldCastSnapshot castWorldSpell(GameTestHelper helper, BlockPos pos, Vec3Iota target,
                                                      Holder<ActionRegistryEntry> action,
                                                      ServerConfig.HexJitMode mode, long initialMedia,
                                                      ItemStack... usableStacks) {
        return castWorldSpell(helper, pos, target, action, mode, initialMedia, false, usableStacks);
    }

    private static WorldCastSnapshot castWorldSpell(GameTestHelper helper, BlockPos pos, Vec3Iota target,
                                                      Holder<ActionRegistryEntry> action,
                                                      ServerConfig.HexJitMode mode, long initialMedia,
                                                      boolean simulateNext, ItemStack... usableStacks) {
        ServerConfig.hexJitMode = mode;
        TestEnvironment env = new TestEnvironment(helper.getLevel(), null, false);
        env.captureContinuations = true;
        env.remainingMedia = initialMedia;
        for (ItemStack stack : usableStacks) env.usableStacks.add(stack.copy());
        env.getWorld().random.setSeed(0x434d495f4a4954L);
        CastingVM vm = new CastingVM(new CastingImage(TreeList.from(List.<Iota>of(target)), 0, TreeList.empty(),
                false, simulateNext, 0, new CompoundTag()), env);
        ExecutionClientView view = vm.queueExecuteAndWrapIotas(
                List.of(new PatternIota(action.value().prototype())), helper.getLevel());
        List<BlockSnapshot> blocks = nearbyBlockSnapshots(helper.getLevel(), pos);
        List<DropSnapshot> drops = nearbyDrops(helper.getLevel(), pos);
        Snapshot cast = new Snapshot(CastingImage.Companion.getCODEC()
                .encodeStart(NbtOps.INSTANCE, vm.getImage()).getOrThrow(), List.copyOf(env.trace),
                view.getResolutionType() + ":" + view.isStackClear(),
                env.getWorld().random.nextLong(), env.remainingMedia + ":" + env.consumedMedia + ":"
                        + env.usableStacks.stream().map(ItemStack::getCount).toList(),
                env.mediaChecks, env.nonzeroMediaChecks, env.particleCalls,
                encodeContinuation(env.lastContinuation), env.continuationSteps);
        return new WorldCastSnapshot(cast, new WorldSnapshot(blocks, drops));
    }

    private static BlockSnapshot blockAt(WorldCastSnapshot snapshot, BlockPos relativePos) {
        return snapshot.world.blocks.stream().filter(block -> block.relativePos.equals(relativePos))
                .findFirst().orElseThrow();
    }

    private static List<BlockSnapshot> nearbyBlockSnapshots(ServerLevel level, BlockPos center) {
        var snapshots = new ArrayList<BlockSnapshot>(27);
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            BlockPos relative = new BlockPos(x, y, z);
            BlockPos pos = center.offset(relative);
            var blockEntity = level.getBlockEntity(pos);
            CompoundTag data = blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
            snapshots.add(new BlockSnapshot(relative, level.getBlockState(pos), data));
        }
        return List.copyOf(snapshots);
    }

    private static List<DropSnapshot> nearbyDrops(ServerLevel level, BlockPos pos) {
        AABB bounds = new AABB(pos).inflate(2.0);
        return level.getEntitiesOfClass(ItemEntity.class, bounds).stream().map(item -> {
            Vec3 relative = item.position().subtract(Vec3.atLowerCornerOf(pos));
            Vec3 motion = item.getDeltaMovement();
            return new DropSnapshot(item.getItem().save(level.registryAccess()).toString(),
                    Double.doubleToRawLongBits(relative.x), Double.doubleToRawLongBits(relative.y),
                    Double.doubleToRawLongBits(relative.z), Double.doubleToRawLongBits(motion.x),
                    Double.doubleToRawLongBits(motion.y), Double.doubleToRawLongBits(motion.z));
        }).sorted(Comparator.comparing(DropSnapshot::item).thenComparingLong(DropSnapshot::px)
                .thenComparingLong(DropSnapshot::py).thenComparingLong(DropSnapshot::pz)).toList();
    }

    private static void discardNearbyDrops(ServerLevel level, BlockPos pos) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0))) item.discard();
    }

    private static void verifyStackValidation(GameTestHelper helper) {
        List<List<Iota>> candidates = List.of(
                List.of(),
                List.of(new BooleanIota(true)),
                List.of(new DoubleIota(-0.0), new NullIota()),
                List.of(new ListIota(List.of(new DoubleIota(1), new JitTestAddon.ExecutableIota())),
                        math(Arithmetic.ADD), new Vec3Iota(Vec3.ZERO)));
        for (List<Iota> candidate : candidates) {
            boolean expected = IotaType.isTooLargeToSerialize(candidate);
            helper.assertTrue(IotaStackValidation.isTooLarge(candidate) == expected,
                    "Indexed immutable-list validation changed the result for " + candidate);
            TreeList<Iota> tree = TreeList.from(candidate);
            helper.assertTrue(IotaStackValidation.isTooLarge(tree) == IotaType.isTooLargeToSerialize(tree),
                    "Indexed TreeList validation changed the result for " + candidate);
        }
        int maxSerializedSize = HexIotaTypes.MAX_SERIALIZATION_TOTAL;
        for (int count : new int[] {31, 32, 33, 63, 64, 65, maxSerializedSize - 2, maxSerializedSize - 1}) {
            List<Iota> candidate = Collections.nCopies(count, new DoubleIota(1));
            TreeList<Iota> tree = TreeList.from(candidate);
            boolean expected = IotaType.isTooLargeToSerialize(tree);
            helper.assertTrue(IotaStackValidation.isTooLarge(tree) == expected,
                    "Direct TreeList2 scan changed the result at size " + count);
            var cache = new IotaStackValidation.MetricCache();
            helper.assertTrue(IotaStackValidation.isTooLarge(tree, cache) == expected
                            && IotaStackValidation.isTooLarge(tree, cache) == expected,
                    "Cached TreeList2 metrics changed the result at size " + count);
            if (count > 32 && count < 1024) {
                helper.assertTrue((Object) tree instanceof TreeList2Access,
                        "Verified TreeList2 accessor was not applied at size " + count);
            }
        }
        var extensionIotas = new ArrayList<Iota>(Collections.nCopies(64, new DoubleIota(1)));
        extensionIotas.set(33, new JitTestAddon.ExecutableIota());
        TreeList<Iota> extensionTree = TreeList.from(extensionIotas);
        helper.assertTrue(IotaStackValidation.isTooLarge(extensionTree)
                        == IotaType.isTooLargeToSerialize(extensionTree),
                "Direct TreeList2 scan changed custom add-on Iota metrics");
        var extensionCache = new IotaStackValidation.MetricCache();
        for (int pass = 0; pass < 3; pass++) {
            helper.assertTrue(IotaStackValidation.isTooLarge(extensionTree, extensionCache)
                            == IotaType.isTooLargeToSerialize(extensionTree),
                    "Cached TreeList2 scan changed custom add-on Iota metrics");
        }
        TreeList<Iota> evolving = TreeList.from(Collections.nCopies(192, new DoubleIota(1)));
        var evolvingCache = new IotaStackValidation.MetricCache();
        for (int i = 0; i < 64; i++) {
            boolean expected = IotaType.isTooLargeToSerialize(evolving);
            helper.assertTrue(IotaStackValidation.isTooLarge(evolving, evolvingCache) == expected,
                    "Cached metrics changed while an immutable TreeList2 root evolved at step " + i);
            evolving = evolving.appended(new DoubleIota(i));
            if ((i & 1) == 0) evolving = evolving.dropRight(1);
        }
    }

    private static void verifyCompoundTagCopy(GameTestHelper helper) {
        class CopyTrackingCompoundTag extends CompoundTag {
            int copies;
            @Override public CompoundTag copy() { copies++; return new CompoundTag(); }
        }

        CompoundTag empty = new CompoundTag();
        CompoundTag emptyCopy = FastCompoundTagCopy.copy(empty);
        helper.assertTrue(empty != emptyCopy && empty.copy().equals(emptyCopy),
                "Allocation-lean CompoundTag copy changed an empty tag");
        emptyCopy.putString("copy-only", "value");
        helper.assertTrue(empty.isEmpty(), "Copying an empty CompoundTag retained a mutable alias");
        CompoundTag source = new CompoundTag();
        CompoundTag child = new CompoundTag();
        child.putString("text", "deep");
        child.putIntArray("ints", new int[] {1, 2, 3});
        CompoundTag grandchild = new CompoundTag();
        grandchild.putString("level", "third");
        child.put("grandchild", grandchild);
        CopyTrackingCompoundTag customChild = new CopyTrackingCompoundTag();
        ListTag list = new ListTag();
        CompoundTag listed = new CompoundTag();
        listed.putLongArray("longs", new long[] {4L, 5L});
        list.add(listed);
        source.put("nested", child);
        source.put("custom", customChild);
        source.put("list", list);
        source.putString("text", "entry");
        source.put("bytes", new ByteArrayTag(new byte[] {6, 7, 8}));
        source.put("number", IntTag.valueOf(9));

        CompoundTag vanilla = source.copy();
        helper.assertTrue(customChild.copies == 1, "Vanilla copy did not invoke the custom CompoundTag override");
        customChild.copies = 0;
        CompoundTag optimized = FastCompoundTagCopy.copy(source);
        helper.assertTrue(customChild.copies == 1, "Fast copy bypassed a custom CompoundTag copy override");
        helper.assertTrue(vanilla.equals(optimized) && vanilla.toString().equals(optimized.toString()),
                "Allocation-lean CompoundTag copy changed NBT content or iteration order");
        helper.assertTrue(source != optimized && source.get("nested") != optimized.get("nested")
                        && source.getCompound("nested").get("grandchild")
                        != optimized.getCompound("nested").get("grandchild")
                        && source.get("list") != optimized.get("list")
                        && source.get("bytes") != optimized.get("bytes"),
                "Allocation-lean CompoundTag copy retained mutable child aliases");
        optimized.getCompound("nested").putString("text", "changed");
        optimized.getCompound("nested").getCompound("grandchild").putString("level", "changed");
        optimized.getList("list", Tag.TAG_COMPOUND).getCompound(0).putLong("extra", 10L);
        helper.assertTrue(source.getCompound("nested").getString("text").equals("deep")
                        && source.getCompound("nested").getCompound("grandchild").getString("level").equals("third")
                        && !source.getList("list", Tag.TAG_COMPOUND).getCompound(0).contains("extra"),
                "Mutating copied nested NBT changed the source tag");

        CompoundTag tickData = new CompoundTag();
        CompoundTag ticks = new CompoundTag();
        ticks.putInt("0,0,0", 4);
        CompoundTag ravenmind = new CompoundTag();
        ravenmind.put("large-iota", list.copy());
        tickData.put("hexal:times_ticked", ticks);
        tickData.put("hexcasting:ravenmind", ravenmind);
        tickData.put("extension-data", child);
        CompoundTag tickCopy = FastCompoundTagCopy.copyForTick(
                tickData, "hexal:times_ticked", "hexcasting:ravenmind");
        helper.assertTrue(tickCopy != tickData
                        && tickCopy.get("hexcasting:ravenmind") == ravenmind
                        && tickCopy.get("hexal:times_ticked") != ticks
                        && tickCopy.get("extension-data") != child,
                "Tick's selective userdata copy did not isolate its counter and other data");
        tickCopy.getCompound("hexal:times_ticked").putInt("0,0,0", 5);
        tickCopy.getCompound("extension-data").putString("text", "tick-copy");
        helper.assertTrue(ticks.getInt("0,0,0") == 4
                        && child.getString("text").equals("deep")
                        && ravenmind.getList("large-iota", Tag.TAG_COMPOUND).equals(list),
                "Tick's selective userdata copy mutated its source data");

        CopyTrackingCompoundTag customRoot = new CopyTrackingCompoundTag();
        helper.assertTrue(FastCompoundTagCopy.copy(customRoot) != customRoot && customRoot.copies == 1,
                "Fast copy bypassed the root CompoundTag subclass override");
    }

    private static List<Iota> parseReferenceSpell() {
        var result = new ArrayList<Iota>();
        var token = new StringBuilder();
        for (int i = 0; i < REFERENCE_SPELL_TEXT.length(); i++) {
            char ch = REFERENCE_SPELL_TEXT.charAt(i);
            if (ch == ',' || ch == '(' || ch == ')') {
                appendReferenceToken(result, token);
                if (ch == '(') result.add(p(HexActions.OPEN_PAREN));
                else if (ch == ')') result.add(p(HexActions.CLOSE_PAREN));
            } else {
                token.append(ch);
            }
        }
        appendReferenceToken(result, token);
        return List.copyOf(result);
    }

    private static void appendReferenceToken(List<Iota> out, StringBuilder token) {
        if (token.length() == 0) return;
        String name = token.toString();
        token.setLength(0);
        if (name.startsWith("num_")) {
            out.add(math(numberPattern(Double.parseDouble(name.substring(4)))));
            return;
        }
        if (name.startsWith("mask_")) {
            out.add(math(maskPattern(name.substring(5))));
            return;
        }
        out.add(switch (name) {
            case "get_caster" -> p(HexActions.GET_CASTER);
            case "get_entity_look" -> p(HexActions.ENTITY_LOOK);
            case "last_n_list" -> p(HexActions.LAST_N_LIST);
            case "write/local" -> p(HexActions.WRITE$LOCAL);
            case "read/local" -> p(HexActions.READ$LOCAL);
            case "empty_list" -> p(HexActions.EMPTY_LIST);
            case "duplicate" -> p(HexActions.DUPLICATE);
            case "eval/cc" -> p(HexActions.EVAL$CC);
            case "fisherman" -> p(HexActions.FISHERMAN);
            case "add_motion" -> p(HexActions.ADD_MOTION);
            case "if" -> p(HexActions.IF);
            case "eval" -> p(HexActions.EVAL);
            case "const/null" -> p(HexActions.CONST$NULL);
            case "splat" -> p(HexActions.SPLAT);
            case "rotate" -> p(HexActions.ROTATE);
            case "rotate_reverse" -> p(HexActions.ROTATE_REVERSE);
            case "add" -> p(HexActions.ADD);
            case "sub" -> p(HexActions.SUB);
            case "mul" -> p(HexActions.MUL_DOT);
            case "greater" -> p(HexActions.GREATER);
            case "append" -> p(HexActions.APPEND);
            default -> throw new IllegalArgumentException("Unknown benchmark spell token: " + name);
        });
    }

    private static HexPattern numberPattern(double number) {
        boolean negative = number < 0;
        double remaining = Math.abs(number);
        var suffix = new StringBuilder();
        // These constants have exact short encodings in HexMod's numerical handler.
        if (remaining == 100) suffix.append("eeeeeeeeee");
        else if (remaining == 4) suffix.append("wwww");
        else if (remaining == 3) suffix.append("www");
        else if (remaining == 2) suffix.append("ww");
        else if (remaining == 1) suffix.append('w');
        else if (remaining != 0) throw new IllegalArgumentException("Unsupported benchmark numeric literal: " + number);
        return HexPattern.fromAngleString((negative ? "dedd" : "aqaa") + suffix, HexDir.SOUTH_EAST, false);
    }

    private static HexPattern maskPattern(String mask) {
        if (mask.isEmpty()) throw new IllegalArgumentException("Empty mask in benchmark spell");
        boolean startsWithDrop = mask.charAt(0) == 'v';
        HexDir orientation = startsWithDrop ? HexDir.SOUTH_EAST : HexDir.EAST;
        var angles = new StringBuilder();
        if (startsWithDrop) angles.append('a');
        char previous = mask.charAt(0);
        for (int i = startsWithDrop ? 1 : 0; i < mask.length(); i++) {
            char current = mask.charAt(i);
            if (current == '-') angles.append(previous == 'v' ? 'e' : 'w');
            else if (current == 'v') angles.append(previous == 'v' ? "da" : "ea");
            else throw new IllegalArgumentException("Invalid mask in benchmark spell: " + mask);
            previous = current;
        }
        return HexPattern.fromAngleString(angles.toString(), orientation, false);
    }

    private static void benchmarkCold(ArithmeticEngine template, TestEnvironment env,
                                      com.sun.management.ThreadMXBean bean) throws Throwable {
        int perRound = 32; // Below the default 64-call compilation threshold.
        var image = new CastingImage(TreeList.from(List.<Iota>of(new DoubleIota(1), new DoubleIota(2))),
                0, TreeList.empty(), false, false, 0, new CompoundTag());
        var arithmetics = Arrays.asList(template.arithmetics);
        ServerConfig.hexJitThreshold = 64;
        for (int round = 0; round < 16; round++) {
            var interpreted = new ArrayList<ArithmeticEngine>(perRound);
            var specialized = new ArrayList<ArithmeticEngine>(perRound);
            for (int i = 0; i < perRound; i++) {
                interpreted.add(new ArithmeticEngine(arithmetics));
                specialized.add(new ArithmeticEngine(arithmetics));
            }
            ServerConfig.hexJitMode = ServerConfig.HexJitMode.OFF;
            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long start = System.nanoTime();
            for (ArithmeticEngine fresh : interpreted)
                blackhole = fresh.run(Arithmetic.ADD, env, image, SpellContinuation.Done.INSTANCE);
            long elapsed = System.nanoTime() - start;
            long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
            System.out.println("HEXJIT_COLD mode=OFF round=" + round + " ns/op=" + elapsed / (double) perRound
                    + " bytes/op=" + bytes / (double) perRound);

            HexJitRuntime.invalidate("cold arithmetic benchmark");
            ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
            HexJitRuntime.acquire(ArithmeticSite.CALL_SITE, ArithmeticSite.CALL); // Create cache outside timing.
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            start = System.nanoTime();
            for (ArithmeticEngine fresh : specialized)
                blackhole = fresh.run(Arithmetic.ADD, env, image, SpellContinuation.Done.INSTANCE);
            elapsed = System.nanoTime() - start;
            bytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
            System.out.println("HEXJIT_COLD mode=AUTO round=" + round + " ns/op=" + elapsed / (double) perRound
                    + " bytes/op=" + bytes / (double) perRound);
        }
    }

    static final class TestEnvironment extends CastingEnvironment {
        static final AtomicLong SKIPPABLE_CALLS = new AtomicLong();
        final List<String> trace = new ArrayList<>();
        final List<ItemStack> usableStacks = new ArrayList<>();
        int limit = 100000;
        int dynamicCalls;
        long nonzeroMediaChecks;
        long mediaChecks;
        long particleCalls;
        SpellContinuation lastContinuation = SpellContinuation.Done.INSTANCE;
        long continuationSteps;
        boolean captureContinuations;
        long remainingMedia = Long.MAX_VALUE / 4;
        long consumedMedia;
        long observerMotionHash = 0x9e3779b97f4a7c15L;
        private final LivingEntity caster;
        private final boolean instrumented;
        TestEnvironment(ServerLevel level) {
            this(level, null, true);
        }
        TestEnvironment(ServerLevel level, LivingEntity caster) {
            this(level, caster, true);
        }
        TestEnvironment(ServerLevel level, LivingEntity caster, boolean instrumented) {
            super(level);
            this.caster = caster;
            this.instrumented = instrumented;
            this.captureContinuations = instrumented;
            if (instrumented) {
                addExtension(new CastingEnvironmentComponent.PostExecution() {
                    private final CastingEnvironmentComponent.Key<CastingEnvironmentComponent.PostExecution> key = new CastingEnvironmentComponent.Key<>() {};
                    public CastingEnvironmentComponent.Key<?> getKey() { return key; }
                    public void onPostExecution(CastResult result) {
                        if (caster != null) {
                            Vec3 motion = caster.getDeltaMovement();
                            observerMotionHash = Long.rotateLeft(observerMotionHash, 9)
                                    ^ Double.doubleToRawLongBits(motion.x)
                                    ^ Long.rotateLeft(Double.doubleToRawLongBits(motion.y), 17)
                                    ^ Long.rotateLeft(Double.doubleToRawLongBits(motion.z), 31);
                        }
                        if (result.getNewData() != null) {
                            CompoundTag tag = result.getNewData().getUserData();
                            tag.putInt("observed", tag.getInt("observed") + 1);
                        }
                        trace.add("stateful observer");
                    }
                });
                addExtension(new SkippablePostExecutionObserver() {
                    private final CastingEnvironmentComponent.Key<SkippablePostExecutionObserver> key = new CastingEnvironmentComponent.Key<>() {};
                    public CastingEnvironmentComponent.Key<?> getKey() { return key; }
                    public void onPostExecution(CastResult result) { SKIPPABLE_CALLS.incrementAndGet(); }
                });
            }
        }
        @Override public int maxOpCount() { return limit; }
        @Override public LivingEntity getCastingEntity() { return caster; }
        @Override public boolean isEnlightened() { return true; }
        @Override public Vec3 mishapSprayPos() { return Vec3.ZERO; }
        @Override protected long extractMediaEnvironment(long cost, boolean simulate) {
            mediaChecks++;
            if (cost > 0) nonzeroMediaChecks++;
            if (instrumented) trace.add("media:" + cost + ":" + simulate);
            long extracted = Math.min(Math.max(cost, 0L), remainingMedia);
            if (!simulate) {
                remainingMedia -= extracted;
                consumedMedia += extracted;
            }
            return cost - extracted;
        }
        @Override protected boolean isVecInRangeEnvironment(Vec3 vector) { return true; }
        @Override protected boolean hasEditPermissionsAtEnvironment(BlockPos pos) { return true; }
        @Override public InteractionHand getCastingHand() { return InteractionHand.MAIN_HAND; }
        @Override public List<ItemStack> getUsableStacks(StackDiscoveryMode mode) { return usableStacks; }
        @Override public List<HeldItemInfo> getPrimaryStacks() { return List.of(); }
        @Override public boolean replaceItem(Predicate<ItemStack> predicate, ItemStack stack, InteractionHand hand) { return false; }
        @Override public FrozenPigment getPigment() { return FrozenPigment.DEFAULT.get(); }
        @Override public FrozenPigment setPigment(FrozenPigment pigment) { return null; }
        @Override public void produceParticles(ParticleSpray spray, FrozenPigment pigment) {
            particleCalls++;
            spray.sprayParticles(getWorld(), pigment);
            if (instrumented) trace.add("particles");
        }
        @Override public void printMessage(Component message) { if (instrumented) trace.add(message.getString()); }
        @Override public void postExecution(CastResult result) {
            super.postExecution(result);
            if (captureContinuations) {
                lastContinuation = result.getContinuation();
                continuationSteps++;
            }
            if (!instrumented) return;
            if (result.getResolutionType() == ResolvedPatternType.ERRORED) {
                String mishap = result.getSideEffects().stream()
                        .filter(OperatorSideEffect.DoMishap.class::isInstance)
                        .map(OperatorSideEffect.DoMishap.class::cast)
                        .map(effect -> {
                            var cause = effect.getMishap();
                            if (cause instanceof MishapInvalidIota invalid) {
                                String value = invalid.getPerpetrator() instanceof ListIota list
                                        ? "ListIota(size=" + list.getList().size() + ")"
                                        : invalid.getPerpetrator().getClass().getSimpleName();
                                return "MishapInvalidIota[" + value
                                        + ", expected=" + invalid.getExpected().getString() + "]";
                            }
                            return cause.getClass().getSimpleName();
                        })
                        .findFirst().orElse("unknown");
                String pattern = result.getCast() instanceof PatternIota pat ? pat.getPattern().toChatString()
                        : result.getCast().getClass().getSimpleName();
                trace.add("mishap:" + mishap + "@" + pattern);
            }
            trace.add(result.getResolutionType() + ":" + result.getSideEffects().stream().map(e -> e.getClass().getName()).toList());
        }
        @Override public MishapEnvironment getMishapEnvironment() {
            return new MishapEnvironment(world, null) {
                public void yeetHeldItemsTowards(Vec3 position) { if (instrumented) trace.add("yeet"); }
                public void dropHeldItems() { if (instrumented) trace.add("drop"); }
                public void drown() { if (instrumented) trace.add("drown"); }
                public void damage(float amount) { if (instrumented) trace.add("damage:" + amount); }
                public void removeXp(int amount) { if (instrumented) trace.add("xp:" + amount); }
                public void blind(int ticks) { if (instrumented) trace.add("blind:" + ticks); }
                public void nauseate(int ticks) { if (instrumented) trace.add("nauseate:" + ticks); }
            };
        }
    }
}
