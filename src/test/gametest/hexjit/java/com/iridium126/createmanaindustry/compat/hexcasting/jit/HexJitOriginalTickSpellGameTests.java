package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.addldata.ADMediaHolder;
import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ExecutionClientView;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.GarbageIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexJitOriginalTickSpellGameTests {
    private static final int EXPECTED_TICK_CALLS = 94_720;
    private static final int BENCHMARK_OP_LIMIT = 110_000;
    private static final ResourceLocation YJSP_MEDIA = ResourceLocation.parse("hexoverpowered:yjsp_media");
    private static final ResourceLocation TICK = ResourceLocation.parse("createmanaindustry:tick");
    private static final long WORLD_RANDOM_SEED = 0x5eed94720L;

    private HexJitOriginalTickSpellGameTests() {}

    @GameTest(template = "hex_jit_test", timeoutTicks = 1200)
    public static void originalSpellOffVsAuto(GameTestHelper helper) throws Exception {
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
        unlocker.unlock(YJSP_MEDIA.toString());
        unlocker.unlock(TICK.toString());

        ListIota programIota = parseReferenceSpell(player);
        List<Iota> program = programIota.getList();
        helper.assertTrue(!program.isEmpty(), "HexParse returned an empty original spell");
        helper.assertTrue(program.stream().noneMatch(GarbageIota.class::isInstance),
                "HexParse returned a garbage iota while reading .refs/spell.txt");

        PatternIota yjspIota = greatPattern(YJSP_MEDIA);
        PatternIota tickIota = greatPattern(TICK);
        var envForMatching = new ReferenceSpellEnvironment(player, yjspIota.getPattern(), tickIota.getPattern());
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
        boolean oldFastTickAction = ServerConfig.hexJitFastTickAction;
        boolean oldFastStack = ServerConfig.hexJitFastStackValidation;
        boolean oldCacheMetrics = ServerConfig.hexJitCacheStackMetrics;
        boolean oldReuseTail = ServerConfig.hexJitReuseFrameTail;
        boolean oldFastMath = ServerConfig.hexJitFastSpecialHandlerMath;
        boolean oldFastLookup = ServerConfig.hexJitFastSpecialHandlerLookup;
        boolean oldFastLiterals = ServerConfig.hexJitFastNumberLiterals;
        boolean oldPatternCache = ServerConfig.hexJitCacheNormalPatternLookup;
        try {
            ServerConfig.hexJitThreshold = 2;
            ServerConfig.hexJitCompileActions = true;
            ServerConfig.hexJitSkipObservers = false;
            ServerConfig.hexJitCoalesceDecorations = false;
            ServerConfig.hexJitBatchAddMotion = true;
            ServerConfig.hexJitFastAddMotionArguments = true;
            ServerConfig.hexJitFastTickAction = true;
            ServerConfig.hexJitFastStackValidation = true;
            ServerConfig.hexJitCacheStackMetrics = true;
            ServerConfig.hexJitReuseFrameTail = true;
            ServerConfig.hexJitFastSpecialHandlerMath = true;
            ServerConfig.hexJitFastSpecialHandlerLookup = true;
            ServerConfig.hexJitFastNumberLiterals = true;
            ServerConfig.hexJitCacheNormalPatternLookup = true;

            ServerConfig.hexJitMode = ServerConfig.HexJitMode.OFF;
            HexJitRuntime.invalidate("original spell interpreter warmup");
            SpellRun interpretedWarmup = cast(helper, player, target, program, yjspIota, tickIota);
            assertCompleted(helper, interpretedWarmup, target, "OFF warmup");

            ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
            ServerConfig.hexJitCoalesceDecorations = true;
            HexJitRuntime.invalidate("original spell AUTO warmup");
            SpellRun jitWarmup = cast(helper, player, target, program, yjspIota, tickIota);
            assertCompleted(helper, jitWarmup, target, "AUTO warmup");
            assertEquivalent(helper, interpretedWarmup, jitWarmup, "warmup");

            SpellRun profiledOff = recordAndCast(helper, player, target, program, yjspIota, tickIota,
                    ServerConfig.HexJitMode.OFF, "off");
            SpellRun profiledAuto = recordAndCast(helper, player, target, program, yjspIota, tickIota,
                    ServerConfig.HexJitMode.AUTO, "auto");
            assertEquivalent(helper, profiledOff, profiledAuto, "JFR profile");

            long[] offNanos = new long[3];
            long[] autoNanos = new long[3];
            SpellRun lastOff = profiledOff;
            SpellRun lastAuto = profiledAuto;
            for (int i = 0; i < offNanos.length; i++) {
                SpellRun off = castInMode(helper, player, target, program, yjspIota, tickIota,
                        ServerConfig.HexJitMode.OFF);
                SpellRun auto = castInMode(helper, player, target, program, yjspIota, tickIota,
                        ServerConfig.HexJitMode.AUTO);
                assertCompleted(helper, off, target, "OFF sample " + i);
                assertCompleted(helper, auto, target, "AUTO sample " + i);
                assertEquivalent(helper, off, auto, "sample " + i);
                offNanos[i] = off.elapsedNanos;
                autoNanos[i] = auto.elapsedNanos;
                lastOff = off;
                lastAuto = auto;
            }

            System.out.println("HEXJIT_ORIGINAL_SPELL programIotas=" + program.size()
                    + " tickCalls=" + lastAuto.storedTicks
                    + " yjspCalls=" + interpretedWarmup.yjspCalls
                    + " ops=" + lastAuto.opsConsumed
                    + " OFF_medianMs=" + median(offNanos) / 1_000_000.0
                    + " AUTO_medianMs=" + median(autoNanos) / 1_000_000.0
                    + " AUTO_ratio=" + median(autoNanos) / (double) median(offNanos)
                    + " OFF_particles=" + interpretedWarmup.particleCalls
                    + " AUTO_particles=" + jitWarmup.particleCalls
                    + " status=" + HexJitRuntime.status());
            helper.succeed();
        } finally {
            ServerConfig.hexJitMode = oldMode;
            ServerConfig.hexJitThreshold = oldThreshold;
            ServerConfig.hexJitCompileActions = oldCompileActions;
            ServerConfig.hexJitSkipObservers = oldSkipObservers;
            ServerConfig.hexJitCoalesceDecorations = oldCoalesce;
            ServerConfig.hexJitBatchAddMotion = oldBatchMotion;
            ServerConfig.hexJitFastAddMotionArguments = oldFastMotion;
            ServerConfig.hexJitFastTickAction = oldFastTickAction;
            ServerConfig.hexJitFastStackValidation = oldFastStack;
            ServerConfig.hexJitCacheStackMetrics = oldCacheMetrics;
            ServerConfig.hexJitReuseFrameTail = oldReuseTail;
            ServerConfig.hexJitFastSpecialHandlerMath = oldFastMath;
            ServerConfig.hexJitFastSpecialHandlerLookup = oldFastLookup;
            ServerConfig.hexJitFastNumberLiterals = oldFastLiterals;
            ServerConfig.hexJitCacheNormalPatternLookup = oldPatternCache;
            HexJitRuntime.invalidate("restore post-test HexJIT settings");
        }
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

    private static ListIota parseReferenceSpell(net.minecraft.server.level.ServerPlayer player) throws IOException {
        try (InputStream stream = HexJitOriginalTickSpellGameTests.class.getResourceAsStream("/hexjit/reference_spell.txt")) {
            if (stream == null) throw new IOException("Generated HexParse source resource is missing");
            String source = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            Iota parsed = ParserMain.ParseCode(source, player);
            if (!(parsed instanceof ListIota list)) throw new IllegalStateException("HexParse did not return a program list");
            return list;
        }
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
                                       PatternIota yjsp,
                                       PatternIota tick,
                                       ServerConfig.HexJitMode mode) {
        ServerConfig.hexJitMode = mode;
        return cast(helper, player, target, program, yjsp, tick, false);
    }

    private static SpellRun recordAndCast(GameTestHelper helper,
                                          net.minecraft.server.level.ServerPlayer player,
                                          BlockPos target,
                                          List<Iota> program,
                                          PatternIota yjsp,
                                          PatternIota tick,
                                          ServerConfig.HexJitMode mode,
                                          String label) throws Exception {
        ServerConfig.hexJitMode = mode;
        String output = System.getProperty("hexjit.jfr.dir");
        if (output == null || output.isBlank()) throw new IOException("GameTest profile output directory is unset");
        Path path = Path.of(output).resolve("original-spell-" + label + ".jfr");
        Files.createDirectories(path.getParent());
        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.setName("HexJIT original spell " + mode);
            recording.setToDisk(true);
            recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));
            recording.start();
            SpellRun result = cast(helper, player, target, program, yjsp, tick, false);
            recording.stop();
            recording.dump(path);
            System.out.println("HEXJIT_JFR path=" + path.toAbsolutePath() + " mode=" + mode
                    + " castMs=" + result.elapsedNanos / 1_000_000.0);
            return result;
        }
    }

    private static SpellRun cast(GameTestHelper helper,
                                 net.minecraft.server.level.ServerPlayer player,
                                 BlockPos target,
                                 List<Iota> program,
                                 PatternIota yjsp,
                                 PatternIota tick) {
        return cast(helper, player, target, program, yjsp, tick, true);
    }

    private static SpellRun cast(GameTestHelper helper,
                                 net.minecraft.server.level.ServerPlayer player,
                                 BlockPos target,
                                 List<Iota> program,
                                 PatternIota yjsp,
                                 PatternIota tick,
                                 boolean trackActionCalls) {
        ServerLevel level = helper.getLevel();
        level.setBlock(target.below(), Blocks.FARMLAND.defaultBlockState(), 3);
        level.setBlock(target, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0), 3);
        player.setPos(target.getX() + 2.5, target.getY(), target.getZ() + 0.5);
        player.setHealth(player.getMaxHealth());
        level.random.setSeed(WORLD_RANDOM_SEED);

        Iota coordinate = new Vec3Iota(Vec3.atCenterOf(target));
        TreeList<Iota> stack = TreeList.from(List.of(tick, yjsp, coordinate));
        if (stack.getLast() != coordinate || stack.get(stack.size() - 2) != yjsp || stack.getFirst() != tick)
            throw new IllegalStateException("The test stack is not ordered top-to-bottom as coordinate, yjsp_media, tick");

        ReferenceSpellEnvironment observerEnv = trackActionCalls
                ? new ReferenceSpellEnvironment(player, yjsp.getPattern(), tick.getPattern()) : null;
        StaffCastEnv env = observerEnv != null ? observerEnv : new BenchmarkSpellEnvironment(player);
        CastingImage initial = new CastingImage(stack, 0, TreeList.empty(), false, false, 0, new CompoundTag());
        CastingVM vm = new CastingVM(initial, env);
        long start = System.nanoTime();
        ExecutionClientView view = vm.queueExecuteAndWrapIotas(program, level);
        long elapsed = System.nanoTime() - start;
        CastingImage image = vm.getImage();
        int storedTicks = vm.getImage().getUserData().getCompound(OpTick.TAG_TIMES_TICKED).getInt(target.toShortString());
        String result = view.getResolutionType() + ":stackClear=" + view.isStackClear();
        int cropAge = level.getBlockState(target).hasProperty(CropBlock.AGE)
                ? level.getBlockState(target).getValue(CropBlock.AGE) : -1;
        long randomState = level.random.nextLong();
        long personalMedia = readPersonalMedia(player);
        return new SpellRun(image, result, level.getBlockState(target), cropAge, randomState,
                storedTicks, observerEnv == null ? 0 : observerEnv.tickCalls,
                observerEnv == null ? 0 : observerEnv.yjspCalls,
                observerEnv == null ? 0 : observerEnv.particleCalls, vm.getImage().getOpsConsumed(),
                observerEnv == null ? "[]" : observerEnv.errors.toString(), player.getHealth(), personalMedia, elapsed);
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

    private static void assertCompleted(GameTestHelper helper, SpellRun result, BlockPos target, String label) {
        helper.assertTrue(result.errors.equals("[]"), label + " had errors after " + result.tickCalls
                + " Tick and " + result.yjspCalls + " yjsp_media calls, ops=" + result.opsConsumed + ": " + result.errors);
        helper.assertTrue(!result.resolution.startsWith(ResolvedPatternType.ERRORED.toString()),
                label + " ended in an errored resolution: " + result.resolution);
        helper.assertTrue(result.tickCalls == 0 || result.tickCalls == EXPECTED_TICK_CALLS,
                label + " ran P2 " + result.tickCalls + " times, expected " + EXPECTED_TICK_CALLS);
        helper.assertTrue(result.storedTicks == EXPECTED_TICK_CALLS,
                label + " wrote " + result.storedTicks + " ticks to userdata, expected " + EXPECTED_TICK_CALLS);
        helper.assertTrue(result.opsConsumed <= BENCHMARK_OP_LIMIT,
                label + " exceeded the GameTest benchmark operation budget: " + result.opsConsumed);
        helper.assertTrue(result.cropAge >= 0, label + " lost the random-ticking crop at " + target);
    }

    private static void assertEquivalent(GameTestHelper helper, SpellRun off, SpellRun auto, String label) {
        helper.assertTrue(off.image.equals(auto.image), label + " changed the final CastingImage\nOFF=" + off + "\nAUTO=" + auto);
        helper.assertTrue(off.resolution.equals(auto.resolution), label + " changed the resolution\nOFF=" + off + "\nAUTO=" + auto);
        helper.assertTrue(off.finalBlock.equals(auto.finalBlock) && off.cropAge == auto.cropAge,
                label + " changed the final crop state\nOFF=" + off + "\nAUTO=" + auto);
        helper.assertTrue(off.randomState == auto.randomState,
                label + " changed the world RNG state\nOFF=" + off.randomState + "\nAUTO=" + auto.randomState);
        helper.assertTrue(off.tickCalls == auto.tickCalls && off.yjspCalls == auto.yjspCalls
                        && off.storedTicks == auto.storedTicks,
                label + " changed the number of Tick or yjsp_media actions");
        helper.assertTrue(Float.compare(off.playerHealth, auto.playerHealth) == 0,
                label + " changed caster health: OFF=" + off.playerHealth + ", AUTO=" + auto.playerHealth);
        helper.assertTrue(off.personalMedia == auto.personalMedia,
                label + " changed HexOP personal media: OFF=" + off.personalMedia + ", AUTO=" + auto.personalMedia);
        helper.assertTrue(auto.particleCalls <= off.particleCalls,
                label + " increased emitted particle sprays: OFF=" + off.particleCalls + ", AUTO=" + auto.particleCalls);
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private record SpellRun(CastingImage image, String resolution, net.minecraft.world.level.block.state.BlockState finalBlock,
                            int cropAge, long randomState, int storedTicks, long tickCalls, long yjspCalls, long particleCalls,
                            long opsConsumed, String errors, float playerHealth, long personalMedia, long elapsedNanos) {}

    private static class BenchmarkSpellEnvironment extends StaffCastEnv {
        private BenchmarkSpellEnvironment(net.minecraft.server.level.ServerPlayer player) {
            super(player, InteractionHand.MAIN_HAND);
        }

        @Override
        public boolean isEnlightened() {
            return true;
        }

        @Override
        public int maxOpCount() {
            // The complete source spell uses slightly more than Hexcasting's default 100,000 op limit
            // on this project's 0.12 runtime. Raise the test-only ceiling to exercise its full output.
            return Math.max(super.maxOpCount(), BENCHMARK_OP_LIMIT);
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
                                          HexPattern tickPattern) {
            super(player);
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

}
