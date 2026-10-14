package com.iridium126.createmanaindustry.compat.trickster;

import com.iridium126.createmanaindustry.compat.trickster.construct.ConstructCastEnv;
import com.iridium126.createmanaindustry.compat.trickster.construct.ConstructMediaStorage;

import at.petrak.hexcasting.api.casting.circles.CircleExecutionState;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.HexBlocks;
import at.petrak.hexcasting.common.lib.hex.HexActions;
import com.iridium126.createmanaindustry.compat.hexcasting.BlockSpellExecutionManager;
import com.iridium126.createmanaindustry.compat.hexcasting.SpellExecutionBridge;
import com.iridium126.createmanaindustry.compat.hexcasting.TrickIota;
import com.iridium126.createmanaindustry.compat.hexcasting.circle.SlateKnotHolder;
import com.iridium126.createmanaindustry.compat.hexcasting.circle.CircleSlateManaPool;
import com.iridium126.createmanaindustry.compat.hexcasting.circle.SlateKnotInventory;
import dev.enjarai.trickster.block.ModBlocks;
import dev.enjarai.trickster.block.SpellConstructBlockEntity;
import dev.enjarai.trickster.spell.PatternGlyph;
import dev.enjarai.trickster.spell.SpellPart;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.execution.ExecutionState;
import dev.enjarai.trickster.spell.execution.TickData;
import dev.enjarai.trickster.spell.execution.source.PlayerSpellSource;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.source.BlockSpellSource;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.fragment.VectorFragment;
import dev.enjarai.trickster.spell.mana.ManaPool;
import dev.enjarai.trickster.spell.mana.SimpleManaPool;
import dev.enjarai.trickster.item.ModItems;
import dev.enjarai.trickster.item.component.ModComponents;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import dev.enjarai.trickster.spell.mana.type.Manae;
import dev.enjarai.trickster.spell.trick.Trick;
import dev.enjarai.trickster.spell.trick.Tricks;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class PlayerlessTrickIotaGameTests {
    private static final BlockPos SOURCE = new BlockPos(0, 1, 0);
    private static final BlockPos SLATE = new BlockPos(1, 1, 0);
    private static final BlockPos TARGET = new BlockPos(1, 1, 1);
    private static final long INITIAL_MANA = 64000;
    private static final long INITIAL_MEDIA = 123456;

    @GameTest(template = "trickster_stock_test")
    public static void slateManaPrecheckAndAbortedSpendRestoreKnots(GameTestHelper helper) {
        var env = circle(helper);
        var pool = new CircleSlateManaPool(SlateKnotInventory.forCircle(helper.getLevel(),
                env.circleState().reachedPositions), helper.getLevel());
        var ctx = new SpellContext(new ExecutionState(List.of(), pool),
                new PlayerSpellSource(FakePlayerFactory.getMinecraft(helper.getLevel())), new TickData());
        ctx.checkScaledMana(20);
        helper.assertTrue(slateMana(helper) == INITIAL_MANA, "Mana precheck drained the knot");
        try (var transaction = Transaction.openOuter()) {
            ctx.useScaledMana(20, transaction);
            helper.assertTrue(slateMana(helper) == INITIAL_MANA - 20 * ManaPool.MANA_SCALE,
                    "Nested spend did not debit the knot");
        }
        helper.assertTrue(slateMana(helper) == INITIAL_MANA, "Aborted mana spend did not restore the knot");
        try (var transaction = Transaction.openOuter()) {
            ctx.useScaledMana(20, transaction);
            transaction.commit();
        }
        helper.assertTrue(slateMana(helper) == INITIAL_MANA - 20 * ManaPool.MANA_SCALE,
                "Committed mana spend did not debit the knot");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void unboundCircleUsesSlatePositionAndManaWithoutMedia(GameTestHelper helper) {
        var env = circle(helper);
        helper.assertTrue(env.getCastingEntity() == null, "Circle unexpectedly has a player");
        cast(helper, env, List.of(trick(Tricks.REFLECTION)),
                List.of(new Vec3Iota(Vec3.atCenterOf(helper.absolutePos(SLATE)))));
        var args = new ListIota(List.of(new DoubleIota(42)));
        cast(helper, env, List.of(args, trick(Tricks.LOAD_ARGUMENT_1)), List.of(new DoubleIota(42)));
        cast(helper, env, List.of(new TrickIota(light(helper))),
                List.of(new Vec3Iota(Vec3.atCenterOf(helper.absolutePos(TARGET)))));
        helper.assertBlockPresent(ModBlocks.LIGHT, TARGET);
        helper.assertTrue(slateMana(helper) == INITIAL_MANA - 20 * ManaPool.MANA_SCALE,
                "Unbound circle did not spend slate-knot mana");
        helper.assertTrue(env.getImpetus().getMedia() == INITIAL_MEDIA, "Trickster spell spent Hex media");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void bothConstructTypesUseTheirInventoryMana(GameTestHelper helper) {
        for (var block : List.of(ModBlocks.SPELL_CONSTRUCT, ModBlocks.MODULAR_SPELL_CONSTRUCT)) {
            helper.setBlock(SOURCE, block);
            helper.setBlock(TARGET, Blocks.AIR);
            BlockEntity construct = helper.getBlockEntity(SOURCE);
            ((Container) construct).setItem(0, knot());
            ConstructMediaStorage.setStoredMedia(construct, INITIAL_MEDIA);
            var env = new ConstructCastEnv(helper.getLevel(), construct);
            helper.assertTrue(env.getCastingEntity() == null, "Construct unexpectedly has a player");
            cast(helper, env, List.of(trick(Tricks.REFLECTION)),
                    List.of(new Vec3Iota(Vec3.atCenterOf(helper.absolutePos(SOURCE)))));
            cast(helper, env, List.of(new ListIota(List.of(new DoubleIota(42))), trick(Tricks.LOAD_ARGUMENT_1)),
                    List.of(new DoubleIota(42)));
            cast(helper, env, List.of(new TrickIota(light(helper))),
                    List.of(new Vec3Iota(Vec3.atCenterOf(helper.absolutePos(TARGET)))));
            helper.assertBlockPresent(ModBlocks.LIGHT, TARGET);
            helper.assertTrue(mana(((Container) construct).getItem(0), helper) == INITIAL_MANA - 20 * ManaPool.MANA_SCALE,
                    "Construct did not spend its native inventory's mana");
            helper.assertTrue(ConstructMediaStorage.getMedia(construct) == INITIAL_MEDIA,
                    "Construct's stored Hex media was spent by Trickster");
        }
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void unboundCircleRunsDelayedSpellOnServerTicks(GameTestHelper helper) {
        var env = circle(helper);
        var vm = cast(helper, env, List.of(new DoubleIota(7), new TrickIota(delayed(light(helper)))),
                List.of(new DoubleIota(7)));
        helper.assertBlockPresent(Blocks.AIR, TARGET);
        helper.runAfterDelay(6, () -> {
            helper.assertBlockPresent(ModBlocks.LIGHT, TARGET);
            helper.assertTrue(slateMana(helper) == INITIAL_MANA - 20 * ManaPool.MANA_SCALE,
                    "Delayed circle spell lost its mana source");
            helper.assertTrue(vm.getImage().getStack().equals(List.of(new DoubleIota(7))),
                    "Background circle result was pushed onto the Hex stack");
            helper.assertTrue(env.getImpetus().getMedia() == INITIAL_MEDIA, "Background spell spent Hex media");
            helper.succeed();
        });
    }

    @GameTest(template = "trickster_stock_test")
    public static void constructBackgroundSpellPreservesNativeExecutor(GameTestHelper helper) {
        var env = construct(helper);
        var construct = (SpellConstructBlockEntity) env.construct();
        var nativeExecutor = new DefaultSpellExecutor(delayed(new SpellPart(new NumberFragment(99))), List.of());
        construct.executor = nativeExecutor;
        var vm = cast(helper, env, List.of(new DoubleIota(7), new TrickIota(delayed(light(helper)))),
                List.of(new DoubleIota(7)));
        helper.assertTrue(construct.executor == nativeExecutor, "Hand-off replaced construct's native executor");
        helper.assertBlockPresent(Blocks.AIR, TARGET);
        helper.runAfterDelay(6, () -> {
            helper.assertBlockPresent(ModBlocks.LIGHT, TARGET);
            helper.assertTrue(mana(construct.getItem(0), helper) == INITIAL_MANA - 20 * ManaPool.MANA_SCALE,
                    "Delayed construct spell lost its inventory mana");
            helper.assertTrue(vm.getImage().getStack().equals(List.of(new DoubleIota(7))),
                    "Background construct result was pushed onto the Hex stack");
            helper.succeed();
        });
    }

    @GameTest(template = "trickster_stock_test")
    public static void removedSourceCancelsBackgroundSpell(GameTestHelper helper) {
        var env = construct(helper);
        cast(helper, env, List.of(new TrickIota(delayed(light(helper)))), List.of());
        helper.setBlock(SOURCE, Blocks.AIR);
        helper.runAfterDelay(6, () -> {
            helper.assertBlockPresent(Blocks.AIR, TARGET);
            helper.succeed();
        });
    }

    @GameTest(template = "trickster_stock_test")
    public static void removedSlateCannotSupplyBackgroundMana(GameTestHelper helper) {
        var env = circle(helper);
        ItemStack original = ((SlateKnotHolder) helper.getBlockEntity(SLATE)).createmanaindustry$getKnot();
        cast(helper, env, List.of(new TrickIota(delayed(light(helper)))), List.of());
        helper.setBlock(SLATE, Blocks.AIR);
        helper.runAfterDelay(6, () -> {
            helper.assertBlockPresent(Blocks.AIR, TARGET);
            helper.assertTrue(mana(original, helper) == INITIAL_MANA, "Removed slate's knot was still drained");
            helper.succeed();
        });
    }

    @GameTest(template = "trickster_stock_test")
    public static void emptyManaCannotBeReplacedWithHexMedia(GameTestHelper helper) {
        var env = construct(helper);
        ((Container) env.construct()).setItem(0, ItemStack.EMPTY);
        cast(helper, env, List.of(new TrickIota(light(helper))), List.of());
        helper.assertBlockPresent(Blocks.AIR, TARGET);
        helper.assertTrue(ConstructMediaStorage.getMedia(env.construct()) == INITIAL_MEDIA, "Hex media replaced mana");
        var circle = circle(helper);
        ((SlateKnotHolder) helper.getBlockEntity(SLATE)).createmanaindustry$setKnot(ItemStack.EMPTY);
        cast(helper, circle, List.of(new TrickIota(light(helper))), List.of());
        helper.assertBlockPresent(Blocks.AIR, TARGET);
        helper.assertTrue(circle.getImpetus().getMedia() == INITIAL_MEDIA, "Impetus media replaced mana");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void blockBackgroundQueueIsBoundedAndClearsOnUnload(GameTestHelper helper) {
        var env = construct(helper);
        for (int i = 0; i < 6; i++) {
            var result = SpellExecutionBridge.tryExecute(delayed(new SpellPart(new NumberFragment(i))), List.of(), env);
            helper.assertTrue(result.status() == (i < 5 ? SpellExecutionBridge.SpellExecutionStatus.HANDED_OFF
                            : SpellExecutionBridge.SpellExecutionStatus.FAILED), "Background queue exceeded five slots");
        }
        var construct = (SpellConstructBlockEntity) env.construct();
        var manager = new BlockSpellExecutionManager(construct,
                new BlockSpellSource<>(helper.getLevel(), construct.getBlockPos(), construct));
        helper.assertTrue(manager.getSpellExecutor(4).isPresent(), "Expected queued spell is missing");
        // Block entities are marked removed on chunk unload as well as removal.
        construct.setRemoved();
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(manager.getSpellExecutor(0).isEmpty(), "Unloaded source retained background jobs");
            helper.succeed();
        });
    }

    private static CircleCastEnv circle(GameTestHelper helper) {
        helper.setBlock(SOURCE, HexBlocks.IMPETUS_REDSTONE.get());
        helper.setBlock(SLATE, HexBlocks.SLATE.get());
        ((SlateKnotHolder) helper.getBlockEntity(SLATE)).createmanaindustry$setKnot(knot());
        BlockPos impetus = helper.absolutePos(SOURCE);
        BlockPos slate = helper.absolutePos(SLATE);
        var state = new CircleExecutionState(impetus, Direction.EAST, new HashSet<>(List.of(impetus, slate)),
                slate, Direction.WEST, new CastingImage(), null, null, 0L,
                helper.absolutePos(new BlockPos(2, 2, 2)), helper.absolutePos(BlockPos.ZERO)) {};
        var env = new CircleCastEnv(helper.getLevel(), state);
        env.getImpetus().setMedia(INITIAL_MEDIA);
        return env;
    }

    private static ConstructCastEnv construct(GameTestHelper helper) {
        helper.setBlock(SOURCE, ModBlocks.SPELL_CONSTRUCT);
        var construct = helper.getBlockEntity(SOURCE);
        ((Container) construct).setItem(0, knot());
        ConstructMediaStorage.setStoredMedia(construct, INITIAL_MEDIA);
        return new ConstructCastEnv(helper.getLevel(), construct);
    }

    private static ItemStack knot() {
        var knot = new ItemStack(ModItems.AMETHYST_KNOT);
        knot.set(ModComponents.MANA, knot.get(ModComponents.MANA).with(
                new SimpleManaPool(INITIAL_MANA, 128000, ManaVariant.of(Manae.TRADITIONAL))));
        return knot;
    }

    private static long mana(ItemStack knot, GameTestHelper helper) { return knot.get(ModComponents.MANA).pool().get(helper.getLevel()); }
    private static long slateMana(GameTestHelper helper) {
        return mana(((SlateKnotHolder) helper.getBlockEntity(SLATE)).createmanaindustry$getKnot(), helper);
    }

    private static TrickIota trick(Trick<?> trick) { return new TrickIota(new SpellPart(new PatternGlyph(trick.getPattern()))); }

    private static SpellPart light(GameTestHelper helper) {
        Vec3 pos = Vec3.atCenterOf(helper.absolutePos(TARGET));
        return new SpellPart(new PatternGlyph(Tricks.CONJURE_LIGHT.getPattern()),
                List.of(new SpellPart(new VectorFragment(new Vector3d(pos.x, pos.y, pos.z)))));
    }

    private static SpellPart delayed(SpellPart after) {
        return new SpellPart(new PatternGlyph(), List.of(
                new SpellPart(new PatternGlyph(Tricks.DELAY_EXECUTION.getPattern()), List.of(new SpellPart(new NumberFragment(2)))), after));
    }

    private static CastingVM cast(GameTestHelper helper, CastingEnvironment env, List<Iota> stack, List<Iota> expected) {
        var vm = new CastingVM(new CastingImage(TreeList.from(stack), 0, TreeList.empty(), false, false, 0, new CompoundTag()), env);
        var result = vm.queueExecuteAndWrapIota(new PatternIota(HexActions.EVAL.value().prototype()), helper.getLevel());
        helper.assertTrue(result.getResolutionType().getSuccess() && vm.getImage().getStack().equals(expected),
                "Unexpected playerless cast result: " + vm.getImage().getStack());
        return vm;
    }

    private PlayerlessTrickIotaGameTests() {}
}
