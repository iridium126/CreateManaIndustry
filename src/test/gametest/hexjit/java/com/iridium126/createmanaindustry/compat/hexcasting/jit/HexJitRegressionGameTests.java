package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.PatternShapeMatch;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.common.lib.HexRegistries;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.casting.PatternRegistryManifest;
import at.petrak.hexcasting.common.lib.hex.HexArithmetics;
import at.petrak.hexcasting.common.lib.hex.HexActions;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import com.iridium126.createmanaindustry.compat.hexcasting.OpTick;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

/** Differential regressions for the optimized VM boundaries. */
@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexJitRegressionGameTests {
    public static class Env extends StaffCastEnv {
        int messages;
        boolean throwPrecheck;
        int opLimit = Integer.MAX_VALUE;
        CastingVM observedVm;
        List<CastingImage> observedImages = new ArrayList<>();
        Env(ServerPlayer player) { super(player, InteractionHand.MAIN_HAND); }
        @Override public boolean isEnlightened() { return true; }
        @Override public int maxOpCount() { return Math.min(super.maxOpCount(), opLimit); }
        @Override public void produceParticles(ParticleSpray spray, FrozenPigment pigment) {}
        @Override protected void sendMishapMsgToPlayer(OperatorSideEffect.DoMishap mishap) { messages++; }
        @Override public void precheckAction(PatternShapeMatch match) {
            if (throwPrecheck) throw new IllegalStateException("review precheck exception");
            if (observedVm != null && match instanceof PatternShapeMatch.PerWorld)
                observedImages.add(observedVm.getImage());
            super.precheckAction(match);
        }
    }
    private static final class LimitedEnv extends StaffCastEnv implements TickStateReuseEnvironment {
        LimitedEnv(ServerPlayer player) { super(player, InteractionHand.MAIN_HAND); }
        @Override public boolean isEnlightened() { return true; }
        @Override public int maxOpCount() { return Math.min(super.maxOpCount(), 3); }
    }
    private static final class CallbackEnv extends StaffCastEnv implements TickStateReuseEnvironment {
        int sounds;
        java.util.Set<?> patterns;
        double ambit;
        double sentinel;
        CallbackEnv(ServerPlayer player) { super(player, InteractionHand.MAIN_HAND); }
        @Override public boolean isEnlightened() { return true; }
        @Override public void precheckAction(PatternShapeMatch match) {
            super.precheckAction(match);
            if (match instanceof PatternShapeMatch.PerWorld) {
                var attribute = getCastingEntity().getAttribute(at.petrak.hexcasting.common.lib.HexAttributes.AMBIT_RADIUS);
                attribute.setBaseValue(attribute.getBaseValue() + 1);
                getCastingEntity().getAttribute(at.petrak.hexcasting.common.lib.HexAttributes.SENTINEL_RADIUS)
                        .setBaseValue(48);
            }
        }
        @Override public void postCast(CastingImage image) {
            try {
                var soundsField = StaffCastEnv.class.getDeclaredField("soundsPlayed");
                soundsField.setAccessible(true);
                sounds = soundsField.getInt(this);
                var patternsField = at.petrak.hexcasting.api.casting.eval.env.PlayerBasedSpiralPatternCastEnv.class
                        .getDeclaredField("castPatterns");
                patternsField.setAccessible(true);
                patterns = java.util.Set.copyOf((java.util.Set<?>) patternsField.get(this));
                ambit = getAmbitRadius();
                sentinel = getSentinelRadius();
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            super.postCast(image);
        }
    }
    private static CastingVM vm(StaffCastEnv env, List<Iota> stack) {
        return new CastingVM(new CastingImage(TreeList.from(stack), 0, TreeList.empty(),
                false, false, 0, new CompoundTag()), env);
    }
    @GameTest(template="hex_jit_test", timeoutTicks=1200, batch="hexjit_regression")
    public static void vmBoundaryParity(GameTestHelper helper) throws Exception {
        try {
            ServerConfig.refreshHexJitSettings();
            HexArithmetics.getEngine();
            ServerPlayer player = FakePlayerFactory.getMinecraft(helper.getLevel());
            player.setGameMode(GameType.CREATIVE);
            var pos = helper.absolutePos(new net.minecraft.core.BlockPos(0, 2, 0));
            player.setPos(Vec3.atCenterOf(pos));
            var target = new Vec3Iota(Vec3.atCenterOf(pos));
            var tick = new PatternIota(Objects.requireNonNull(PatternRegistryManifest.getCanonicalStrokesPerWorld(
                    ResourceKey.create(HexRegistries.ACTION, OpTick.ACTION_ID),
                    helper.getLevel().getServer().overworld())));
            ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
            vm(new Env(player), List.of(target)).queueExecuteAndWrapIotas(List.of(tick), helper.getLevel());
            helper.assertTrue(HexJitRuntime.enabled(), "Review requires verified JIT: " + HexJitRuntime.status());

            int stage = Integer.getInteger("hexjit.regression.stage", 5);
            // The inherited Staff callback must still report argument underflow.
            Env off = new Env(player), auto = new Env(player);
            ServerConfig.hexJitMode = ServerConfig.HexJitMode.OFF;
            vm(off, List.of()).queueExecuteAndWrapIotas(List.of(tick), helper.getLevel());
            ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
            vm(auto, List.of()).queueExecuteAndWrapIotas(List.of(tick), helper.getLevel());
            System.out.println("HEXJIT_REVIEW_MESSAGES off=" + off.messages + " auto=" + auto.messages);
            helper.assertTrue(off.messages == 1 && auto.messages == 1, "Mishap callback probe changed");

            if (stage >= 4) {
            // validateIotaList does not establish the aggregate serialization limit.
            var oversized = new ArrayList<Iota>();
            for (int i=0; i<HexIotaTypes.MAX_SERIALIZATION_TOTAL; i++) oversized.add(new DoubleIota(i));
            oversized.add(target);
            ServerConfig.hexJitMode = ServerConfig.HexJitMode.OFF;
            var offVm = vm(new Env(player), oversized);
            var offView = offVm.queueExecuteAndWrapIotas(List.of(tick), helper.getLevel());
            ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
            var autoVm = vm(new Env(player), oversized);
            var autoView = autoVm.queueExecuteAndWrapIotas(List.of(tick), helper.getLevel());
            System.out.println("HEXJIT_REVIEW_OVERSIZED off=" + offView.getResolutionType() + " auto="
                    + autoView.getResolutionType() + " offOps=" + offVm.getImage().getOpsConsumed()
                    + " autoOps=" + autoVm.getImage().getOpsConsumed());
            helper.assertTrue(!offView.getResolutionType().getSuccess() && !autoView.getResolutionType().getSuccess() && offVm.getImage().getOpsConsumed() == autoVm.getImage().getOpsConsumed(),
                    "Initial stack validation probe changed");

            oversized.subList(0, 2).clear();
            for (var mode : List.of(ServerConfig.HexJitMode.OFF, ServerConfig.HexJitMode.AUTO)) {
                ServerConfig.hexJitMode = mode;
                helper.assertTrue(vm(new Env(player), oversized).queueExecuteAndWrapIotas(List.of(tick),
                        helper.getLevel()).getResolutionType().getSuccess(),
                        "An initially oversized stack must be allowed to shrink to a valid output: " + mode);
            }

            }
            if (stage >= 3) {
            Env throwing = new Env(player);
            throwing.throwPrecheck = true;
            ServerConfig.hexJitMode = ServerConfig.HexJitMode.OFF;
            var caught = vm(throwing, List.of(target)).queueExecuteAndWrapIotas(List.of(tick), helper.getLevel());
            ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
            boolean escaped = false;
            try { vm(throwing, List.of(target)).queueExecuteAndWrapIotas(List.of(tick), helper.getLevel()); }
            catch (IllegalStateException expected) { escaped = expected.getMessage().equals("review precheck exception"); }
            System.out.println("HEXJIT_REVIEW_EXCEPTION off=" + caught.getResolutionType() + " autoEscaped=" + escaped);
            helper.assertTrue(!caught.getResolutionType().getSuccess() && !escaped, "Direct dispatch exception probe changed");

            }
            var eval = new PatternIota(HexActions.EVAL.value().prototype());
            var loopStack = new ArrayList<Iota>(List.of(target, target, target, target,
                    new ListIota(List.of(tick, tick, tick, tick))));
            if (stage >= 2) {
            var limitedOff = new LimitedEnv(player);
            var limitedAuto = new LimitedEnv(player);
            ServerConfig.hexJitMode = ServerConfig.HexJitMode.OFF;
            var limitedOffVm = vm(limitedOff, loopStack);
            limitedOffVm.queueExecuteAndWrapIotas(List.of(eval), helper.getLevel());
            ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
            var limitedAutoVm = vm(limitedAuto, loopStack);
            limitedAutoVm.queueExecuteAndWrapIotas(List.of(eval), helper.getLevel());
            int offTicks = limitedOffVm.getImage().getUserData().getCompound(OpTick.TAG_TIMES_TICKED).getInt(pos.toShortString());
            int autoTicks = limitedAutoVm.getImage().getUserData().getCompound(OpTick.TAG_TIMES_TICKED).getInt(pos.toShortString());
            System.out.println("HEXJIT_REVIEW_OP_LIMIT limit=3 offTicks=" + offTicks + " autoTicks=" + autoTicks
                    + " offOps=" + limitedOffVm.getImage().getOpsConsumed() + " autoOps=" + limitedAutoVm.getImage().getOpsConsumed());
            helper.assertTrue(autoTicks == offTicks && autoTicks == 2, "Overridden maxOpCount probe changed");

            }
            if (stage >= 5) {
            Env snapshotEnv = new Env(player);
            var snapshotVm = vm(snapshotEnv, loopStack);
            snapshotEnv.observedVm = snapshotVm;
            snapshotVm.queueExecuteAndWrapIotas(List.of(eval), helper.getLevel());
            var snapshots = snapshotEnv.observedImages;
            System.out.println("HEXJIT_REVIEW_SNAPSHOTS count=" + snapshots.size()
                    + " ops=" + snapshots.stream().map(CastingImage::getOpsConsumed).toList()
                    + " secondAndThirdSame=" + (snapshots.get(1) == snapshots.get(2)));
            helper.assertTrue(snapshots.get(1) != snapshots.get(2) && snapshots.get(1).getOpsConsumed() == 2, "Aliased immutable image probe changed");

            var radius = player.getAttribute(at.petrak.hexcasting.common.lib.HexAttributes.AMBIT_RADIUS);
            double initialRadius = radius.getBaseValue();
            CallbackEnv callbackOff = new CallbackEnv(player), callbackAuto = new CallbackEnv(player);
            try {
                ServerConfig.hexJitMode = ServerConfig.HexJitMode.OFF;
                vm(callbackOff, loopStack).queueExecuteAndWrapIotas(List.of(eval), helper.getLevel());
                radius.setBaseValue(initialRadius);
                ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
                ServerConfig.hexJitCollectMetrics = true;
                vm(callbackAuto, loopStack).queueExecuteAndWrapIotas(List.of(eval), helper.getLevel());
                helper.assertTrue(callbackOff.sounds > 0 && callbackOff.sounds == callbackAuto.sounds
                                && callbackOff.patterns.equals(callbackAuto.patterns)
                                && callbackOff.ambit == callbackAuto.ambit && callbackOff.sentinel == callbackAuto.sentinel,
                        "Successful Tick callback lost sounds, patterns, or range refresh");
                helper.assertTrue(ExecutionScope.lastStaffTickCallbacks() > 0,
                        "Regression did not exercise the specialized Staff callback: " + HexJitRuntime.status());
                System.out.println("HEXJIT_CALLBACK_PARITY sounds=" + callbackAuto.sounds
                        + " patterns=" + callbackAuto.patterns.size() + " ambit=" + callbackAuto.ambit);
            } finally { radius.setBaseValue(initialRadius); }

            for (var mode : List.of(ServerConfig.HexJitMode.OFF, ServerConfig.HexJitMode.AUTO)) {
                var fresh = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
                        new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes(("hexjit_attr_" + mode).getBytes()),
                                "hexjit_attr_" + mode));
                fresh.setGameMode(GameType.CREATIVE);
                fresh.setPos(Vec3.atCenterOf(pos));
                var freshEnv = new CallbackEnv(fresh);
                ServerConfig.hexJitMode = mode;
                vm(freshEnv, loopStack).queueExecuteAndWrapIotas(List.of(eval), helper.getLevel());
                helper.assertTrue(freshEnv.sentinel == 48, "New attribute instance was not observed: " + mode);
            }

            var quoting = new ArrayList<Iota>();
            quoting.add(new PatternIota(HexActions.OPEN_PAREN.value().prototype()));
            for (int i = 0; i < 300; i++) quoting.add(target);
            quoting.add(new PatternIota(HexActions.CLOSE_PAREN.value().prototype()));
            net.minecraft.nbt.Tag quotedOff = null;
            for (var mode : List.of(ServerConfig.HexJitMode.OFF, ServerConfig.HexJitMode.AUTO)) {
                ServerConfig.hexJitMode = mode;
                var quoteEnv = new CallbackEnv(player);
                var quoteVm = vm(quoteEnv, List.of(new ListIota(quoting)));
                var quoteView = quoteVm.queueExecuteAndWrapIotas(List.of(eval), helper.getLevel());
                var encoded = CastingImage.Companion.getCODEC().encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, quoteVm.getImage()).getOrThrow();
                helper.assertTrue(quoteView.getResolutionType().getSuccess() && quoteEnv.sounds == 100,
                        "Quoted vector batching changed the Staff sound cap: " + mode);
                if (quotedOff == null) quotedOff = encoded;
                else helper.assertTrue(quotedOff.equals(encoded), "Quoted vector batching changed final image");
                if (mode == ServerConfig.HexJitMode.AUTO)
                    helper.assertTrue(ExecutionScope.lastSoundsEmitted() == 1 && ExecutionScope.lastSoundsCoalesced() == 99,
                            "Identical Staff sounds were not coalesced within the cast");
            }

            int interpreterCallbacks = -1;
            for (var mode : List.of(ServerConfig.HexJitMode.OFF, ServerConfig.HexJitMode.AUTO)) {
                ServerConfig.hexJitMode = mode;
                var observed = new CallbackEnv(player);
                int[] callbacks = {0};
                observed.addExtension(new at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent.PostExecution() {
                    private final at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent.Key<?> key =
                            new at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent.Key<>() {};
                    @Override public at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent.Key<?> getKey() { return key; }
                    @Override public void onPostExecution(at.petrak.hexcasting.api.casting.eval.CastResult result) { callbacks[0]++; }
                });
                var observedVm = vm(observed, List.of(new ListIota(quoting)));
                observedVm.queueExecuteAndWrapIotas(List.of(eval), helper.getLevel());
                if (interpreterCallbacks < 0) interpreterCallbacks = callbacks[0];
                else helper.assertTrue(callbacks[0] == interpreterCallbacks && ExecutionScope.lastPureQuoteRuns() == 0,
                        "A post-execution observer lost intermediate callbacks");
            }

            ServerConfig.hexJitMode = ServerConfig.HexJitMode.AUTO;
            ServerConfig.hexJitCollectMetrics = true;
            var level = helper.getLevel();
            var evalSound = at.petrak.hexcasting.common.lib.hex.HexEvalSounds.HERMES.get();
            var castSound = evalSound.sound();
            double sx = pos.getX(), sy = pos.getY(), sz = pos.getZ();
            var category = net.minecraft.sounds.SoundSource.PLAYERS;
            var randomField = net.minecraft.world.level.Level.class.getDeclaredField("threadSafeRandom");
            randomField.setAccessible(true);
            var soundRandom = (net.minecraft.util.RandomSource) randomField.get(level);
            helper.assertTrue(JitCompatibility.soundElisionReady() && SoundEventObservers.unobserved(),
                    "Regression requires the verified unobserved Staff sound path");
            for (boolean enabled : List.of(false, true)) {
                ServerConfig.hexJitCoalesceStaffSounds = enabled;
                var soundEnv = (TickPostExecutionAccess) (Object) new CallbackEnv(player);
                long seed = 582419;
                soundRandom.setSeed(seed);
                var expected = net.minecraft.util.RandomSource.create(seed);
                expected.nextLong();
                if (!enabled) expected.nextLong();
                try (ExecutionScope ignored = ExecutionScope.enter(false)) {
                    soundEnv.cmi$postSuccessfulTick(null, evalSound);
                    soundEnv.cmi$postSuccessfulTick(null, evalSound);
                }
                helper.assertTrue(soundRandom.nextLong() == expected.nextLong(),
                        "Staff sound RNG count does not follow the single config switch: " + enabled);
                helper.assertTrue(ExecutionScope.lastSoundsEmitted() == (enabled ? 1 : 2)
                                && ExecutionScope.lastSoundsCoalesced() == (enabled ? 1 : 0),
                        "Staff sound config did not restore original playback: " + enabled);
            }
            ServerConfig.hexJitCoalesceStaffSounds = true;
            var soundEnv = (TickPostExecutionAccess) (Object) new CallbackEnv(player);
            var initialPosition = player.position();
            try (ExecutionScope ignored = ExecutionScope.enter(false)) {
                soundEnv.cmi$postSuccessfulTick(null, evalSound);
                double savedAmbit = radius.getBaseValue();
                try {
                    helper.assertTrue(soundEnv.cmi$canCollapseQuotedCallbacks(evalSound),
                            "Pure callbacks should fold after the first matching Staff sound");
                    radius.setBaseValue(savedAmbit + 3);
                    helper.assertTrue(!soundEnv.cmi$canCollapseQuotedCallbacks(evalSound),
                            "Dirty attributes were treated as pure");
                    radius.getValue();
                    helper.assertTrue(!soundEnv.cmi$canCollapseQuotedCallbacks(evalSound),
                            "A clean attribute with an outdated environment radius was treated as pure");
                    soundEnv.cmi$refreshTickRangeAttributes();
                    helper.assertTrue(soundEnv.cmi$canCollapseQuotedCallbacks(evalSound),
                            "Refreshed attributes should permit pure callbacks again");
                } finally { radius.setBaseValue(savedAmbit); soundEnv.cmi$refreshTickRangeAttributes(); }
                soundEnv.cmi$postSuccessfulTick(null, evalSound);
                player.setPos(initialPosition.add(.25, 0, 0));
                soundEnv.cmi$postSuccessfulTick(null, evalSound);
                player.setPos(initialPosition);
                soundEnv.cmi$postSuccessfulTick(null, evalSound);
            } finally { player.setPos(initialPosition); }
            helper.assertTrue(ExecutionScope.lastSoundsEmitted() == 2 && ExecutionScope.lastSoundsCoalesced() == 2,
                    "Staff sound positions or nonadjacent duplicates were merged incorrectly");
            try (ExecutionScope ignored = ExecutionScope.enter(false)) {
                soundEnv.cmi$postSuccessfulTick(null, evalSound);
            }
            helper.assertTrue(ExecutionScope.lastSoundsEmitted() == 1 && ExecutionScope.lastSoundsCoalesced() == 0,
                    "Staff sound cache leaked across casts");

            long ordinarySeed = 672183;
            soundRandom.setSeed(ordinarySeed);
            var expectedOrdinary = net.minecraft.util.RandomSource.create(ordinarySeed);
            expectedOrdinary.nextLong(); expectedOrdinary.nextLong();
            try (ExecutionScope ignored = ExecutionScope.enter(false)) {
                level.playSound(null, sx, sy, sz, castSound, category, 1, 1);
                level.playSound(null, sx, sy, sz, castSound, category, 1, 1);
            }
            helper.assertTrue(soundRandom.nextLong() == expectedOrdinary.nextLong()
                            && ExecutionScope.lastSoundsEmitted() == 0 && ExecutionScope.lastSoundsCoalesced() == 0,
                    "Ordinary sounds were intercepted by Staff sound coalescing");

            int[] volumeChanges = {0};
            java.util.function.Consumer<net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition> volumeListener =
                    event -> event.setNewVolume(++volumeChanges[0] * .1f);
            try (ExecutionScope ignored = ExecutionScope.enter(false)) {
                soundEnv.cmi$postSuccessfulTick(null, evalSound);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                        net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition.class, volumeListener);
                helper.assertTrue(!SoundEventObservers.unobserved(), "Live sound listener registration was missed");
                soundEnv.cmi$postSuccessfulTick(null, evalSound);
                soundEnv.cmi$postSuccessfulTick(null, evalSound);
            } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(volumeListener); }
            helper.assertTrue(volumeChanges[0] == 2 && ExecutionScope.lastSoundsEmitted() == 3
                            && ExecutionScope.lastSoundsCoalesced() == 0 && SoundEventObservers.unobserved(),
                    "Staff sound event observers were bypassed");
            System.out.println("HEXJIT_STAFF_SOUND_CONFIG rng=verified ordinary=unchanged listener=preserved");
            int[] statEvents = {0};
            var statPlayer = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("hexjit_stat".getBytes()), "hexjit_stat")) {
                @Override public void awardStat(net.minecraft.stats.Stat<?> stat, int amount) {
                    getStats().increment(this, stat, amount);
                }
            };
            statPlayer.setGameMode(GameType.CREATIVE);
            statPlayer.setPos(Vec3.atCenterOf(pos));
            java.util.function.Consumer<net.neoforged.neoforge.event.StatAwardEvent> listener = event -> {
                if (event.getEntity() == statPlayer && event.getStat().getType() == net.minecraft.stats.Stats.CUSTOM
                        && event.getStat().getValue().equals(at.petrak.hexcasting.api.mod.HexStatistics.SPELLS_CAST)) {
                    statEvents[0]++;
                    event.setValue(event.getValue() + 10);
                }
            };
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    net.neoforged.neoforge.event.StatAwardEvent.class, listener);
            try {
                var stat = net.minecraft.stats.Stats.CUSTOM.get(at.petrak.hexcasting.api.mod.HexStatistics.SPELLS_CAST);
                for (var mode : List.of(ServerConfig.HexJitMode.OFF, ServerConfig.HexJitMode.AUTO)) {
                    ServerConfig.hexJitMode = mode;
                    int before = statPlayer.getStats().getValue(stat);
                    statEvents[0] = 0;
                    vm(new CallbackEnv(statPlayer), loopStack).queueExecuteAndWrapIotas(List.of(eval), helper.getLevel());
                    helper.assertTrue(statEvents[0] == 4 && statPlayer.getStats().getValue(stat) - before == 44,
                            "Stat listener values or per-Tick event count changed: " + mode);
                    radius.setBaseValue(initialRadius);
                }
            } finally {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
                radius.setBaseValue(initialRadius);
            }

            }
            helper.succeed();
        } finally { ServerConfig.refreshHexJitSettings(); }
    }
}

