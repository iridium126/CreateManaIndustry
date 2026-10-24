package com.iridium126.createmanaindustry.compat.trickster;

import at.petrak.hexcasting.api.casting.circles.CircleExecutionState;
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.common.lib.HexBlocks;
import com.iridium126.createmanaindustry.compat.hextrick.execution.CircleSpellSource;
import com.iridium126.createmanaindustry.compat.hextrick.execution.circle.CircleSlateManaPool;
import com.iridium126.createmanaindustry.compat.hextrick.execution.circle.SlateKnotHolder;
import com.iridium126.createmanaindustry.compat.hextrick.execution.*;
import dev.enjarai.trickster.item.ModItems;
import dev.enjarai.trickster.item.component.ModComponents;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.execution.TickData;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.fragment.VectorFragment;
import dev.enjarai.trickster.spell.mana.SimpleManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import dev.enjarai.trickster.spell.mana.type.Manae;
import dev.enjarai.trickster.spell.trick.Tricks;
import io.wispforest.endec.SerializationContext;
import io.wispforest.endec.format.edm.EdmDeserializer;
import io.wispforest.endec.format.edm.EdmSerializer;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexInteropExecutionGameTests {
    private static final BlockPos IMPETUS = new BlockPos(0, 1, 0);
    private static final BlockPos SLATE = new BlockPos(1, 1, 0);

    private static CastingOrigin circle(GameTestHelper helper) {
        helper.setBlock(IMPETUS, HexBlocks.IMPETUS_REDSTONE.get());
        helper.setBlock(SLATE, HexBlocks.SLATE.get());
        holder(helper).createmanaindustry$setKnot(knot(64000));
        var impetus = helper.absolutePos(IMPETUS);
        var slate = helper.absolutePos(SLATE);
        var state = new CircleExecutionState(impetus, Direction.EAST, new HashSet<>(List.of(impetus, slate)),
                slate, Direction.WEST, new CastingImage(), null, null, 0L,
                helper.absolutePos(new BlockPos(2, 2, 2)), helper.absolutePos(BlockPos.ZERO)) {};
        return com.iridium126.createmanaindustry.compat.hextrick.execution.HexCastingOrigins.capture(new CircleCastEnv(helper.getLevel(), state));
    }

    private static SlateKnotHolder holder(GameTestHelper helper) { return (SlateKnotHolder) helper.getBlockEntity(SLATE); }
    private static ItemStack knot(long mana) {
        var stack = new ItemStack(ModItems.AMETHYST_KNOT);
        stack.set(ModComponents.MANA, stack.get(ModComponents.MANA).with(new SimpleManaPool(mana, 128000, ManaVariant.of(Manae.TRADITIONAL))));
        return stack;
    }
    private static CircleSpellSource source(GameTestHelper helper, CastingOrigin origin) {
        return new CircleSpellSource(new AnchoredCircleCastEnv(helper.getLevel(), origin), origin);
    }
    private static SpellExecutor copy(SpellExecutor executor) {
        var serializer = EdmSerializer.of();
        SpellExecutor.INTERNAL_ENDEC.encode(SerializationContext.empty(), serializer, executor);
        return SpellExecutor.INTERNAL_ENDEC.decode(SerializationContext.empty(), EdmDeserializer.of(serializer.result()));
    }
    private static BridgeSpellExecutor delayed(GameTestHelper helper, CastingOrigin origin, SpellPart next) {
        var delay = new SpellPart(new PatternGlyph(Tricks.DELAY_EXECUTION.getPattern()), List.of(new SpellPart(new NumberFragment(2))));
        var spell = new SpellPart(next, List.of(delay));
        return new BridgeSpellExecutor(new DefaultSpellExecutor(spell, List.of()), new BridgeExecutionContext(helper.getLevel().getServer(), origin));
    }

    @GameTest(template = "trickster_stock_test")
    public static void sourceIdentitySurvivesBlockSaveButReplacementInvalidates(GameTestHelper helper) {
        CastingOrigin origin = circle(helper);
        CastingOrigin decoded = CastingOrigin.decode(origin.encode());
        helper.assertTrue(decoded.equals(origin), "Source identifiers or ordered fuel slots changed on save");
        var invalid = com.google.gson.JsonParser.parseString(origin.encode()).getAsJsonObject();
        invalid.getAsJsonObject("source").getAsJsonObject("dimension").remove("namespace");
        boolean rejected = false;
        try { CastingOrigin.decode(invalid.toString()); } catch (RuntimeException expected) { rejected = true; }
        helper.assertTrue(rejected, "Malformed source registry identity survived restoration");
        var world = helper.getLevel();
        var old = helper.getBlockEntity(SLATE);
        var saved = old.saveWithFullMetadata(world.registryAccess());
        world.removeBlockEntity(old.getBlockPos());
        var restored = BlockEntity.loadStatic(old.getBlockPos(), old.getBlockState(), saved, world.registryAccess());
        world.setBlockEntity(restored);
        helper.assertTrue(decoded.source().resolve(world.getServer()).status() == CircleSourceDescriptor.Status.READY, "Restored block lost its persistent UUID");
        helper.setBlock(SLATE, Blocks.AIR);
        helper.setBlock(SLATE, HexBlocks.SLATE.get());
        helper.assertTrue(decoded.source().resolve(world.getServer()).status() == CircleSourceDescriptor.Status.INVALID, "A new slate inherited the old identity");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void restoredExecutorSpendsCurrentKnotAndKeepsSlateAnchor(GameTestHelper helper) {
        CastingOrigin origin = circle(helper);
        var executor = delayed(helper, origin, new SpellPart(new PatternGlyph(Tricks.REFLECTION.getPattern())));
        helper.assertTrue(executor.run(source(helper, origin)).isEmpty(), "Delay did not hand off");
        SpellExecutor restored = copy(executor);
        holder(helper).createmanaindustry$setKnot(knot(12000));
        var pool = new CircleSlateManaPool(origin);
        helper.assertTrue(pool.get(helper.getLevel()) == 12000, "Restored origin retained a detached balance");
        pool.use(ManaVariant.of(Manae.TRADITIONAL), 5000, helper.getLevel());
        helper.assertTrue(holder(helper).createmanaindustry$getKnot().get(ModComponents.MANA).pool().get(helper.getLevel()) == 7000, "Payment missed the replacement knot");
        java.util.Optional<Fragment> result = java.util.Optional.empty();
        for (int i = 0; i < 8 && result.isEmpty(); i++) result = restored.run(source(helper, origin));
        Fragment value = result.orElseThrow();
        if (value instanceof dev.enjarai.trickster.spell.fragment.ListFragment list) value = list.fragments().getLast();
        helper.assertTrue(value instanceof VectorFragment vector && vector.vector().distance(source(helper, origin).getPos()) < 1e-8,
                "Native executor save/restore moved the spell away from its slate");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void unloadedOriginPausesDelayAndSurvivesAnotherSave(GameTestHelper helper) {
        CastingOrigin origin = circle(helper);
        var far = new CircleSourceDescriptor.BlockReference(12000000, 64, 12000000, origin.source().anchor().type(), UUID.randomUUID());
        var waiting = new CastingOrigin(new CircleSourceDescriptor(origin.source().dimension(), origin.source().impetus(), origin.source().anchor(), List.of(far)),
                origin.facing(), origin.minX(), origin.minY(), origin.minZ(), origin.maxX(), origin.maxY(), origin.maxZ(), null);
        var executor = delayed(helper, waiting, new SpellPart(new NumberFragment(42)));
        executor.getDeepestState().addDelay(7);
        var data = new TickData();
        helper.assertTrue(executor.run(source(helper, origin), data).isEmpty(), "Unavailable source executed");
        helper.assertTrue(data.getExecutions() == 0 && executor.getDeepestState().getDelay() == 7, "Pause advanced delay/instructions");
        var restored = copy(executor);
        helper.assertTrue(restored.run(source(helper, origin)).isEmpty() && restored.getDeepestState().getDelay() == 7, "Saving a paused executor advanced it");
        var world = helper.getLevel();
        var saved = helper.getBlockEntity(SLATE).saveWithFullMetadata(world.registryAccess());
        saved.putUUID("cmi_source_identity", far.identity());
        world.setBlockAndUpdate(far.pos(), HexBlocks.SLATE.get().defaultBlockState());
        world.setBlockEntity(BlockEntity.loadStatic(far.pos(), world.getBlockState(far.pos()), saved, world.registryAccess()));
        helper.assertTrue(waiting.source().resolve(world.getServer()).status() == CircleSourceDescriptor.Status.READY,
                "Loading the matching source did not make it ready");
        helper.assertTrue(restored.run(source(helper, origin)).isEmpty() && restored.getDeepestState().getDelay() == 6,
                "Matching source did not resume the preserved delay exactly once");
        world.setBlockAndUpdate(far.pos(), Blocks.AIR.defaultBlockState());
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void independentTickObjectsShareRootQuotaButIndependentRootsDoNot(GameTestHelper helper) {
        var context = new BridgeExecutionContext(helper.getLevel().getServer(), null);
        context.hexLimit(10, 0);
        try (var scope = context.enter(); var crossing = context.crossing()) {
            var first = context.tickData(new TickData());
            for (int i = 0; i < dev.enjarai.trickster.Trickster.CONFIG.maxExecutionsPerSpellPerTick(); i++) first.incrementExecutions();
            helper.assertTrue(context.tickData(new TickData()).isExecutionLimitReached(), "Fresh TickData bypassed shared quota");
            helper.assertTrue(context.consumeHex(7) && !context.withOrigin(null).consumeHex(4), "Nested Hex calls obtained fresh budget");
        }
        var independent = new BridgeExecutionContext(helper.getLevel().getServer(), null);
        try (var scope = independent.enter(); var crossing = independent.crossing()) {
            helper.assertTrue(!independent.tickData(new TickData()).isExecutionLimitReached() && independent.consumeHex(1), "Independent root inherited exhausted counters");
        }
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void budgetSaveCannotResetHexQuotaAndDepthScopeAlwaysUnwinds(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var context = new BridgeExecutionContext(server, null);
        context.hexLimit(10, 0);
        try (var scope = context.enter(); var crossing = context.crossing()) { context.consumeHex(10); }
        var restored = BridgeExecutionContext.restore(server, null, context.encodeBudget());
        boolean rejected = false;
        try (var crossing = restored.crossing()) { throw new AssertionError("Exhausted Hex budget resumed"); }
        catch (BridgeExecutionContext.BridgeFailure expected) { rejected = true; }
        helper.assertTrue(rejected && restored.hexUsed() == 10, "Save reset total Hex operations");
        var scopes = new java.util.ArrayList<BridgeExecutionContext.Scope>();
        var fresh = new BridgeExecutionContext(server, null);
        try {
            for (int i = 0; i < com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.hexTrickMaxDepth; i++) scopes.add(fresh.crossing());
            try (var ignored = fresh.crossing()) { throw new AssertionError("Cross-language depth was unbounded"); }
            catch (BridgeExecutionContext.BridgeFailure expected) {}
        } finally { java.util.Collections.reverse(scopes); scopes.forEach(BridgeExecutionContext.Scope::close); }
        try (var ignored = fresh.crossing()) { helper.succeed(); }
    }

    private HexInteropExecutionGameTests() {}

    @GameTest(template = "trickster_stock_test")
    public static void cancellationReleasesWrapperAndNativeOwnerWithoutEndingIndependentRoot(GameTestHelper helper) {
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
        Object independentOwner = new Object();
        var independent = com.iridium126.createmanaindustry.compat.trickster.casting.CastChains.attach(
                new com.iridium126.createmanaindustry.compat.trickster.casting.CastChain(), player, independentOwner);
        var nativeExecutor = new DefaultSpellExecutor(new SpellPart(new NumberFragment(42)), List.of());
        nativeExecutor.getDeepestState().addDelay(3);
        var executor = new BridgeSpellExecutor(nativeExecutor, new BridgeExecutionContext(helper.getLevel().getServer(), null));
        var manager = new dev.enjarai.trickster.spell.execution.PlayerSpellExecutionManager(5);
        var source = new dev.enjarai.trickster.spell.execution.source.PlayerSpellSource(player, manager);
        helper.assertTrue(executor.run(source).isEmpty(), "Expected a paused native continuation");
        var chain = executor.cmi$executorChain();
        int slot = manager.queue(executor).orElseThrow();
        manager.kill(slot);
        helper.assertTrue(!chain.live() && independent.live(), "Cancelling wrapper leaked the native owner or ended another root");
        com.iridium126.createmanaindustry.compat.trickster.casting.CastChains.release(independent, independentOwner);
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void invalidSavedBudgetFailsBeforeFuelAndReleasesItsOwner(GameTestHelper helper) throws Exception {
        CastingOrigin origin = circle(helper);
        var executor = delayed(helper, origin, new SpellPart(new NumberFragment(42)));
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
        var chain = com.iridium126.createmanaindustry.compat.trickster.casting.CastChains.attach(
                new com.iridium126.createmanaindustry.compat.trickster.casting.CastChain(), player, executor);
        executor.cmi$executorChain(chain);
        var context = BridgeSpellExecutor.class.getDeclaredField("context"); context.setAccessible(true); context.set(executor, null);
        var budget = BridgeSpellExecutor.class.getDeclaredField("savedBudget"); budget.setAccessible(true); budget.set(executor, "damaged");
        boolean rejected = false;
        try { executor.run(new dev.enjarai.trickster.spell.execution.source.PlayerSpellSource(player)); }
        catch (BridgeExecutionContext.BridgeFailure failure) { rejected = failure.reason().equals("invalid_saved_execution"); }
        helper.assertTrue(rejected && !chain.live() && new CircleSlateManaPool(origin).get(helper.getLevel()) == 64000,
                "Damaged restore fell back to player fuel, consumed resources, or retained its owner");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void trickQuotaResetsOnlyOnANewServerTick(GameTestHelper helper) {
        var context = new BridgeExecutionContext(helper.getLevel().getServer(), null);
        context.hexLimit(100, 0);
        try (var scope = context.enter(); var crossing = context.crossing()) {
            context.consumeHex(7);
            var data = context.tickData(new TickData());
            for (int i = 0; i < dev.enjarai.trickster.Trickster.CONFIG.maxExecutionsPerSpellPerTick(); i++) data.incrementExecutions();
            helper.assertTrue(context.tickData(new TickData()).isExecutionLimitReached(), "Quota reset during same tick");
        }
        helper.runAfterDelay(1, () -> {
            try (var scope = context.enter()) {
                helper.assertTrue(!context.tickData(new TickData()).isExecutionLimitReached() && context.hexUsed() == 7,
                        "A new tick failed to restore Trickster quota or reset cumulative Hex operations");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "trickster_stock_test")
    public static void nativeTricksterRootKeepsMixedBudgetAcrossTicksAndSave(GameTestHelper helper) {
        var context = new BridgeExecutionContext(helper.getLevel().getServer(), null);
        context.hexLimit(10, 0);
        var executor = new DefaultSpellExecutor(new SpellPart(new NumberFragment(42)), List.of());
        executor.getDeepestState().addDelay(2);
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
        var source = new dev.enjarai.trickster.spell.execution.source.PlayerSpellSource(player);
        try (var scope = context.enter(); var crossing = context.crossing()) {
            context.consumeHex(7);
            helper.assertTrue(executor.run(source).isEmpty(), "Native root did not pause");
        }
        var restored = copy(executor);
        restored.run(source);
        String budget = ((ExecutorBudgetAccess) restored).cmi$encodeBudget();
        var recovered = BridgeExecutionContext.restore(helper.getLevel().getServer(), null, budget);
        helper.assertTrue(recovered.hexUsed() == 7 && !recovered.consumeHex(4), "Native continuation/save reset its mixed root budget");
        ((com.iridium126.createmanaindustry.compat.trickster.casting.ExecutorChainAccess) restored).cmi$releaseExecution();
        ((com.iridium126.createmanaindustry.compat.trickster.casting.ExecutorChainAccess) (Object) executor).cmi$releaseExecution();
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void realNestedLanguageCallsCannotAcquireFreshHexBudgets(GameTestHelper helper) {
        at.petrak.hexcasting.api.casting.iota.Iota nested = new com.iridium126.createmanaindustry.compat.hextrick.data.TrickIota(new SpellPart(new NumberFragment(42)));
        for (int i = 0; i < 6; i++) {
            var hex = new at.petrak.hexcasting.api.casting.iota.ListIota(List.of(nested));
            var input = new com.iridium126.createmanaindustry.compat.hextrick.data.IotaFragment(
                    com.iridium126.createmanaindustry.compat.hextrick.data.IotaFragment.serializeIota(hex));
            nested = new com.iridium126.createmanaindustry.compat.hextrick.data.TrickIota(new SpellPart(
                    new PatternGlyph(CMITricks.Hexcasting.EVAL_IOTA.getPattern()), List.of(new SpellPart(input))));
        }
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
        var environment = new at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv(player, net.minecraft.world.InteractionHand.MAIN_HAND);
        var context = new BridgeExecutionContext(helper.getLevel().getServer(), null);
        context.hexLimit(3, 0);
        try (var scope = context.enter()) {
            var vm = new at.petrak.hexcasting.api.casting.eval.vm.CastingVM(new CastingImage(), environment);
            var result = vm.queueExecuteAndWrapIota(nested, helper.getLevel());
            helper.assertTrue(context.hexUsed() == 4 && !result.getResolutionType().getSuccess(),
                    "Nested VMs reset or double-counted the total Hex quota: " + context.hexUsed());
        }
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void nativeTailAndChildExecutorsPreserveQuotaAcrossSaveAndTick(GameTestHelper helper) throws Exception {
        record Pending(SpellExecutor executor, BridgeExecutionContext context, int expected) {}
        var pending = new java.util.ArrayList<Pending>();
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
        var source = new dev.enjarai.trickster.spell.execution.source.PlayerSpellSource(player);
        var childField = DefaultSpellExecutor.class.getDeclaredField("child");
        childField.setAccessible(true);
        for (boolean tail : new boolean[]{true, false}) {
            var body = new SpellPart(new NumberFragment(7));
            var call = new SpellPart(new PatternGlyph(Tricks.EXECUTE.getPattern()), List.of(new SpellPart(body)));
            var root = tail ? call : new SpellPart(new PatternGlyph(Tricks.ADD.getPattern()), List.of(call, new SpellPart(new NumberFragment(1))));
            var nativeExecutor = new DefaultSpellExecutor(root, List.of());
            var context = new BridgeExecutionContext(helper.getLevel().getServer(), null);
            context.hexLimit(10, 0);
            context.consumeHex(7);
            var executor = new BridgeSpellExecutor(nativeExecutor, context);
            var data = new TickData().withExecutionLimit(2);
            try (var scope = context.enter(); var crossing = context.crossing()) {
                boolean waiting = executor.run(source, data).isEmpty();
                // Native non-tail runChild yields before its parent's increment.
                // The bridge has already reserved that second instruction.
                helper.assertTrue(waiting && data.getExecutions() == (tail ? 2 : 1),
                        "Native call accounting changed: tail=" + tail + ", nativeCount=" + data.getExecutions());
            }
            helper.assertTrue(((java.util.Optional<?>) childField.get(nativeExecutor)).isEmpty() == tail,
                    "Fixture failed to enter the native tail/child execution branch");
            var restored = copy(executor);
            helper.assertTrue(restored.run(source, new TickData().withExecutionLimit(2)).isEmpty()
                    && restored.getLastRunExecutions() == 0, "Saving acquired fresh per-tick instruction quota");
            executor.cmi$releaseExecution();
            pending.add(new Pending(restored, context, tail ? 7 : 8));
        }
        helper.startSequence().thenExecuteAfter(1, () -> {
            for (var entry : pending) {
                var result = entry.executor().run(source).orElseThrow();
                helper.assertTrue(result instanceof NumberFragment number && number.number() == entry.expected()
                        && entry.context().hexUsed() == 7, "Tail/child continuation lost its result or cumulative Hex budget");
            }
        }).thenSucceed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void onlineOwnerInOtherDimensionKeepsSlateWorldInventoryAndOfflinePause(GameTestHelper helper) throws Exception {
        CastingOrigin captured = circle(helper);
        var server = helper.getLevel().getServer();
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "CircleOwner"));
        var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var online = (java.util.Map<UUID, net.minecraft.server.level.ServerPlayer>) field.get(server.getPlayerList());
        online.put(player.getUUID(), player);
        var origin = new CastingOrigin(captured.source(), captured.facing(), captured.minX(), captured.minY(), captured.minZ(),
                captured.maxX(), captured.maxY(), captured.maxZ(), player.getUUID());
        try {
            player.setServerLevel(server.getLevel(net.minecraft.world.level.Level.NETHER));
            player.setPos(1000, 70, 1000);
            var source = source(helper, origin);
            helper.assertTrue(source.getWorld() == helper.getLevel() && source.getPlayer().orElseThrow() == player
                    && source.getInventory().orElseThrow() == player.getInventory(), "Cross-dimensional owner replaced circle source/inventory");
            var environment = new AnchoredCircleCastEnv(helper.getLevel(), origin);
            helper.assertTrue(!environment.isVecInRangeEnvironment(player.position()) && environment.isVecInRangeEnvironment(origin.position()),
                    "Coordinates from the owner's other world extended the circle range");
            var executor = delayed(helper, origin, new SpellPart(new PatternGlyph(Tricks.REFLECTION.getPattern())));
            online.remove(player.getUUID());
            var data = new TickData();
            helper.assertTrue(executor.run(source, data).isEmpty() && data.getExecutions() == 0, "Offline owner did not pause before instructions");
            var restored = copy(executor);
            online.put(player.getUUID(), player);
            java.util.Optional<Fragment> result = java.util.Optional.empty();
            for (int i = 0; i < 8 && result.isEmpty(); i++) result = restored.run(new dev.enjarai.trickster.spell.execution.source.PlayerSpellSource(player));
            helper.assertTrue(result.orElseThrow() instanceof VectorFragment vector && vector.vector().distance(source.getPos()) < 1e-8,
                    "Player manager restored the executor at its cross-dimensional owner");
            helper.succeed();
        } finally { online.remove(player.getUUID()); player.setServerLevel(helper.getLevel()); }
    }
}
