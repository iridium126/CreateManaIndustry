package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.*;
import at.petrak.hexcasting.api.casting.arithmetic.*;
import at.petrak.hexcasting.api.casting.arithmetic.engine.*;
import at.petrak.hexcasting.api.casting.arithmetic.operator.Operator;
import at.petrak.hexcasting.api.casting.eval.*;
import at.petrak.hexcasting.api.casting.eval.vm.*;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.casting.math.*;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.*;
import at.petrak.hexcasting.common.casting.PatternRegistryManifest;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.iridium126.createmanaindustry.config.ServerConfig;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexJitGameTests {
    private static volatile Object blackhole;

    @GameTest(template = "hex_jit_test", timeoutTicks = 600)
    public static void differentialAndBenchmark(GameTestHelper helper) throws Exception {
        // Force all seven guarded targets through transformation before checking readiness.
        for (String name : List.of("api.casting.arithmetic.engine.ArithmeticEngine",
                "api.casting.arithmetic.engine.ArithmeticEngine$OpCandidates",
                "api.casting.eval.vm.FrameEvaluate", "api.casting.iota.PatternIota"))
            Class.forName("at.petrak.hexcasting." + name);
        var env = new TestEnvironment(helper.getLevel());
        CastingVM.empty(env).queueExecuteAndWrapIotas(List.of(), helper.getLevel());
        helper.assertTrue(JitCompatibility.ready(), "Hex JIT Mixin compatibility gate failed: " + JitCompatibility.status());
        var ordinaryMatch = PatternRegistryManifest.matchPattern(JitTestAddon.ORDINARY, env);
        helper.assertTrue(ordinaryMatch instanceof PatternShapeMatch.Normal,
                "Test add-on action pattern collided: " + ordinaryMatch.getClass().getName());
        var ordinaryKey = ((PatternShapeMatch.Normal) ordinaryMatch).key;
        helper.assertTrue(JitTestAddon.isOrdinaryAction(IXplatAbstractions.INSTANCE.getActionRegistry().get(ordinaryKey).action()),
                "Test add-on ordinary action was overridden");
        var throwingMatch = PatternRegistryManifest.matchPattern(JitTestAddon.THROWING, env);
        helper.assertTrue(throwingMatch instanceof PatternShapeMatch.Normal
                        && JitTestAddon.isThrowingAction(IXplatAbstractions.INSTANCE.getActionRegistry()
                        .get(((PatternShapeMatch.Normal) throwingMatch).key).action()),
                "Test add-on throwing action was overridden: " + throwingMatch.getClass().getName());
        helper.assertTrue(PatternRegistryManifest.matchPattern(JitTestAddon.DYNAMIC, env) instanceof PatternShapeMatch.Special,
                "Test add-on dynamic handler pattern collided");
        ServerConfig.hexJitThreshold = 2;
        ServerConfig.hexJitCompileActions = true;
        ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
        HexJitRuntime.invalidate("differential warmup");
        List<Scenario> corpus = corpus();
        for (int pass = 0; pass < 10; pass++) for (Scenario scenario : corpus) run(helper, scenario, ServerConfig.HexJitMode.AUTO);
        helper.runAfterDelay(10, () -> {
            try {
                for (int pass = 0; pass < 3; pass++) for (Scenario scenario : corpus) {
                    Snapshot baseline = run(helper, scenario, ServerConfig.HexJitMode.OFF);
                    Snapshot jit = run(helper, scenario, ServerConfig.HexJitMode.AUTO);
                    helper.assertTrue(baseline.equals(jit), "Differential mismatch: " + scenario.name + "\n" + baseline + "\n" + jit);
                }
                helper.assertTrue(HexJitRuntime.status().matches("(?s).*compiledHits=[1-9][0-9]*.*"), "No compiled execution: " + HexJitRuntime.status());
                helper.assertTrue(ActionSites.compiledHits() > 0, "PatternIota did not dispatch a compiled add-on Action");
                ServerConfig.hexJitSkipObservers = true;
                long observerStart = TestEnvironment.SKIPPABLE_CALLS.get();
                Snapshot observerBaseline = run(helper, corpus.stream().filter(s -> s.name.equals("addon action and continuation"))
                        .findFirst().orElseThrow(), ServerConfig.HexJitMode.OFF);
                long baselineObserverCalls = TestEnvironment.SKIPPABLE_CALLS.get() - observerStart;
                Snapshot observerJit = run(helper, corpus.stream().filter(s -> s.name.equals("addon action and continuation"))
                        .findFirst().orElseThrow(), ServerConfig.HexJitMode.AUTO);
                long jitObserverCalls = TestEnvironment.SKIPPABLE_CALLS.get() - observerStart - baselineObserverCalls;
                helper.assertTrue(observerBaseline.equals(observerJit), "Stateful observer was skipped on a compiled Action");
                helper.assertTrue(baselineObserverCalls > jitObserverCalls, "Explicitly skippable observer was not omitted");
                ServerConfig.hexJitSkipObservers = false;
                overloadCache(helper);
                benchmark(helper);
                HexJitRuntime.invalidate("test reload");
                for (Scenario scenario : corpus) helper.assertTrue(run(helper, scenario, ServerConfig.HexJitMode.OFF)
                        .equals(run(helper, scenario, ServerConfig.HexJitMode.AUTO)), "Reload mismatch: " + scenario.name);
                System.out.println("HEXJIT_VALIDATION " + HexJitRuntime.status());
                helper.succeed();
            } catch (Throwable error) { helper.fail(error.toString()); }
            finally { ServerConfig.refreshHexJitSettings(); }
        });
    }

    private record Scenario(String name, List<Iota> stack, List<Iota> program, int opLimit) {}
    private record Snapshot(Tag image, List<String> trace, String resolution, long randomState) {}
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
        cases.add(s("addon action and continuation", List.of(), math(JitTestAddon.ORDINARY), math(JitTestAddon.ORDINARY)));
        cases.add(s("addon action in parens", List.of(), p(HexActions.OPEN_PAREN), math(JitTestAddon.ORDINARY), p(HexActions.CLOSE_PAREN)));
        cases.add(s("addon exception", List.of(), math(JitTestAddon.THROWING)));
        cases.add(s("dynamic special handler", List.of(), math(JitTestAddon.DYNAMIC), math(JitTestAddon.DYNAMIC), add));
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
        return new Snapshot(CastingImage.Companion.getCODEC().encodeStart(NbtOps.INSTANCE, vm.getImage()).getOrThrow(),
                List.copyOf(env.trace), view.getResolutionType() + ":" + view.isStackClear(), env.getWorld().random.nextLong());
    }

    private static void overloadCache(GameTestHelper helper) throws Throwable {
        int[] predicates = {0};
        Operator first = new Operator(1, args -> { predicates[0]++; return true; }) {
            @Override public OperationResult operate(CastingEnvironment env, CastingImage image, SpellContinuation continuation) {
                return new OperationResult(image.withUsedOps(7), List.of(), continuation, HexEvalSounds.NOTHING.get());
            }
        };
        Arithmetic extension = new Arithmetic() {
            public String arithName() { return "test extension"; }
            public Iterable<HexPattern> opTypes() { return List.of(Arithmetic.ADD); }
            public Operator getOperator(HexPattern pattern) { return first; }
        };
        var engine = new ArithmeticEngine(List.of(extension));
        var image = new CastingImage(TreeList.from(List.<Iota>of(new DoubleIota(4))), 0, TreeList.empty(), false, false, 0, new CompoundTag());
        ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
        for (int i = 0; i < 50; i++) {
            var result = engine.run(Arithmetic.ADD, new TestEnvironment(helper.getLevel()), image, SpellContinuation.Done.INSTANCE);
            helper.assertTrue(result.getNewImage().getOpsConsumed() == 7, "Addon operator bypassed");
        }
        helper.assertTrue(predicates[0] == 1, "First-match cache semantics changed");
        ServerConfig.hexJitMode = ServerConfig.HexJitMode.OFF;
        engine.run(Arithmetic.ADD, new TestEnvironment(helper.getLevel()), image, SpellContinuation.Done.INSTANCE);
        helper.assertTrue(predicates[0] == 1, "JIT did not populate the original engine cache");
    }

    private static void benchmark(GameTestHelper helper) throws Throwable {
        var engine = HexArithmetics.getEngine();
        var env = new TestEnvironment(helper.getLevel());
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        benchmarkCold(engine, env, bean);
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
        int limit = 100000;
        int dynamicCalls;
        TestEnvironment(ServerLevel level) {
            super(level);
            addExtension(new CastingEnvironmentComponent.PostExecution() {
                private final CastingEnvironmentComponent.Key<CastingEnvironmentComponent.PostExecution> key = new CastingEnvironmentComponent.Key<>() {};
                public CastingEnvironmentComponent.Key<?> getKey() { return key; }
                public void onPostExecution(CastResult result) {
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
        @Override public int maxOpCount() { return limit; }
        @Override public LivingEntity getCastingEntity() { return null; }
        @Override public boolean isEnlightened() { return true; }
        @Override public Vec3 mishapSprayPos() { return Vec3.ZERO; }
        @Override protected long extractMediaEnvironment(long cost, boolean simulate) { trace.add("media:" + cost + ":" + simulate); return 0; }
        @Override protected boolean isVecInRangeEnvironment(Vec3 vector) { return true; }
        @Override protected boolean hasEditPermissionsAtEnvironment(BlockPos pos) { return true; }
        @Override public InteractionHand getCastingHand() { return InteractionHand.MAIN_HAND; }
        @Override public List<ItemStack> getUsableStacks(StackDiscoveryMode mode) { return List.of(); }
        @Override public List<HeldItemInfo> getPrimaryStacks() { return List.of(); }
        @Override public boolean replaceItem(Predicate<ItemStack> predicate, ItemStack stack, InteractionHand hand) { return false; }
        @Override public FrozenPigment getPigment() { return null; }
        @Override public FrozenPigment setPigment(FrozenPigment pigment) { return null; }
        @Override public void produceParticles(ParticleSpray spray, FrozenPigment pigment) { trace.add("particles"); }
        @Override public void printMessage(Component message) { trace.add(message.getString()); }
        @Override public void postExecution(CastResult result) {
            super.postExecution(result);
            trace.add(result.getResolutionType() + ":" + result.getSideEffects().stream().map(e -> e.getClass().getName()).toList());
        }
        @Override public MishapEnvironment getMishapEnvironment() {
            return new MishapEnvironment(world, null) {
                public void yeetHeldItemsTowards(Vec3 position) { trace.add("yeet"); }
                public void dropHeldItems() { trace.add("drop"); }
                public void drown() { trace.add("drown"); }
                public void damage(float amount) { trace.add("damage:" + amount); }
                public void removeXp(int amount) { trace.add("xp:" + amount); }
                public void blind(int ticks) { trace.add("blind:" + ticks); }
                public void nauseate(int ticks) { trace.add("nauseate:" + ticks); }
            };
        }
    }
}
