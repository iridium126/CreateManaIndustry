package com.iridium126.createmanaindustry.compat.hexcasting;

import com.iridium126.createmanaindustry.compat.hextrick.bootstrap.CMIHexTrickActions;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.ConversionTickTime;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.OpMediaToMana;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.MishapResourceConversion;

import at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus;
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.blocks.circles.BlockEntitySlate;
import at.petrak.hexcasting.common.blocks.circles.BlockSlate;
import at.petrak.hexcasting.common.lib.HexBlocks;
import com.iridium126.createmanaindustry.compat.hextrick.execution.circle.SlateKnotHolder;
import com.iridium126.createmanaindustry.compat.trickster.construct.ConstructMediaStorage;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.ManaToMediaTrick;
import com.iridium126.createmanaindustry.compat.trickster.blunder.ResourceConversionBlunder;
import com.mojang.authlib.GameProfile;
import dev.enjarai.trickster.block.ModBlocks;
import dev.enjarai.trickster.block.ModularSpellConstructBlockEntity;
import dev.enjarai.trickster.block.SpellConstructBlockEntity;
import dev.enjarai.trickster.item.ModItems;
import dev.enjarai.trickster.item.component.FragmentComponent;
import dev.enjarai.trickster.item.component.ModComponents;
import dev.enjarai.trickster.spell.PatternGlyph;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.SpellPart;
import dev.enjarai.trickster.spell.execution.ExecutionState;
import dev.enjarai.trickster.spell.execution.TickData;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.source.BlockSpellSource;
import dev.enjarai.trickster.spell.exception.blunder.NotEnoughManaBlunder;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.mana.SimpleManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import dev.enjarai.trickster.spell.mana.type.Manae;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class ResourceConversionAutomationGameTests {
    @GameTest(template = "conversion_test")
    public static void unboundCircleRunsTheActualSlateWave(GameTestHelper helper) throws Exception {
        at25ms(helper, () -> {
            var circle = circle(helper);
            var first = holder(helper, circle.north());
            var second = holder(helper, circle.north().east());
            var unvisited = holder(helper, circle.outside());
            first.createmanaindustry$setKnot(knot(0, 32000));
            second.createmanaindustry$setKnot(knot(0, 32000));
            unvisited.createmanaindustry$setKnot(knot(0, 64000));
            var lastSlate = (BlockEntitySlate) helper.getLevel().getBlockEntity(circle.impetus().getBlockPos().south());
            lastSlate.pattern = CMIHexTrickActions.MEDIA_TO_MANA.get().prototype();
            circle.impetus().setMedia(10000);
            circle.impetus().startExecution(null);
            var state = circle.impetus().getExecutionState();
            helper.assertTrue(state != null && state.caster == null, "Unbound circle did not start");
            state.currentImage = input();
            for (int i = 0; i < 20 && circle.impetus().getExecutionState() != null; i++)
                circle.impetus().tickExecution();
            helper.assertTrue(circle.impetus().getExecutionState() == null, "Circle did not finish its closed path");
            helper.assertTrue(circle.impetus().getMedia() == 0, "Circle did not pay 10000 media from its impetus");
            helper.assertTrue(mana(first.createmanaindustry$getKnot(), helper) == 32000
                    && mana(second.createmanaindustry$getKnot(), helper) == 32000,
                    "Circle did not split the full 64000 internal mana across reached slates");
            helper.assertTrue(mana(unvisited.createmanaindustry$getKnot(), helper) == 0,
                    "Circle charged a slate outside its visited path");
            var tile = helper.getLevel().getBlockEntity(circle.north());
            var saved = tile.saveWithFullMetadata(helper.getLevel().registryAccess());
            var restored = BlockEntity.loadStatic(tile.getBlockPos(), tile.getBlockState(), saved,
                    helper.getLevel().registryAccess());
            helper.assertTrue(restored instanceof SlateKnotHolder restoredHolder
                    && mana(restoredHolder.createmanaindustry$getKnot(), helper) == 32000,
                    "Converted slate knot did not survive block entity serialization");
        });
        helper.succeed();
    }

    @GameTest(template = "conversion_test")
    public static void boundCirclesNeverUseTheOwnersBackpack(GameTestHelper helper) throws Exception {
        at25ms(helper, () -> {
            var circle = circle(helper);
            circle.impetus().setMedia(20000);
            circle.impetus().startExecution(null);
            var state = circle.impetus().getExecutionState();
            helper.assertTrue(state != null, "Circle did not start");
            var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "CircleOwner"));
            player.getInventory().setItem(0, knot(0, 100000));
            state.reachedPositions.add(circle.north());
            holder(helper, circle.north()).createmanaindustry$setKnot(knot(0, 63999));
            // The other circle slates have not yet been reached by this wave.
            holder(helper, circle.north().east()).createmanaindustry$setKnot(knot(0, 100000));
            var env = new CircleCastEnv(helper.getLevel(), state) {
                @Override public ServerPlayer getCastingEntity() { return player; }
            };
            expect(MishapResourceConversion.class,
                    () -> OpMediaToMana.INSTANCE.execute(List.of(new DoubleIota(1)), env));
            helper.assertTrue(circle.impetus().getMedia() == 20000
                    && mana(holder(helper, circle.north()).createmanaindustry$getKnot(), helper) == 0
                    && mana(player.getInventory().getItem(0), helper) == 0,
                    "Capacity failure spent media or used an unreached/owner knot");
            holder(helper, circle.north()).createmanaindustry$setKnot(knot(0, 64000));
            var result = OpMediaToMana.INSTANCE.execute(List.of(new DoubleIota(1)), env);
            helper.assertTrue(env.extractMedia(result.getCost(), false) == 0, "Circle payment failed");
            var returnedImage = result.getEffect().cast(env, new at.petrak.hexcasting.api.casting.eval.vm.CastingImage());
            helper.assertTrue(returnedImage != null && returnedImage.getStack().size() == 1
                            && returnedImage.getStack().get(0) instanceof DoubleIota output
                            && Math.abs(output.getDouble() - 64.0) < 0.0001,
                    "Circle conversion did not return the mana stored in reached slates");
            helper.assertTrue(mana(holder(helper, circle.north()).createmanaindustry$getKnot(), helper) == 64000
                    && mana(player.getInventory().getItem(0), helper) == 0,
                    "Bound circle used the player's inventory");
            circle.impetus().endExecution();
        });
        helper.succeed();
    }

    @GameTest(template = "conversion_test")
    public static void constructTickAndModularCoresUseTheirOwnReserves(GameTestHelper helper) throws Exception {
        at25ms(helper, () -> {
            var level = helper.getLevel();
            var pos = helper.absolutePos(new BlockPos(0, 2, 0));
            level.setBlockAndUpdate(pos, ModBlocks.SPELL_CONSTRUCT.defaultBlockState());
            var construct = (SpellConstructBlockEntity) level.getBlockEntity(pos);
            construct.setItem(0, knot(64000, 128000));
            construct.executor = new DefaultSpellExecutor(spell(64), List.of());
            construct.tick();
            helper.assertTrue(construct.executor == null, "Construct did not finish the conversion spell");
            helper.assertTrue(ConstructMediaStorage.getMedia(construct) == 10000
                    && mana(construct.getItem(0), helper) == 0, "Construct did not exchange 64 mana for 10000 media");
            var saved = construct.saveWithFullMetadata(level.registryAccess());
            var restored = BlockEntity.loadStatic(pos, construct.getBlockState(), saved, level.registryAccess());
            helper.assertTrue(restored instanceof SpellConstructBlockEntity reloaded
                    && ConstructMediaStorage.getMedia(reloaded) == 10000 && mana(reloaded.getItem(0), helper) == 0,
                    "Construct media and knot debit did not persist together");
            helper.assertTrue(construct.getUpdateTag(level.registryAccess()).getLong(ConstructMediaStorage.MEDIA_TAG) == 10000,
                    "Construct client update omitted the converted media");

            pos = helper.absolutePos(new BlockPos(2, 2, 0));
            level.setBlockAndUpdate(pos, ModBlocks.MODULAR_SPELL_CONSTRUCT.defaultBlockState());
            var modular = (ModularSpellConstructBlockEntity) level.getBlockEntity(pos);
            modular.setItem(0, knot(64000, 128000));
            for (int slot = 1; slot <= 2; slot++) {
                var core = new ItemStack(ModItems.SPELL_CORE);
                core.set(ModComponents.FRAGMENT, new FragmentComponent(spell(32)));
                modular.setItem(slot, core);
            }
            modular.tick();
            helper.assertTrue(modular.executors.get(0).isEmpty() && modular.executors.get(1).isEmpty(),
                    "Modular cores did not finish their conversion spells");
            helper.assertTrue(ConstructMediaStorage.getMedia(modular) == 10000
                    && mana(modular.getItem(0), helper) == 0, "Modular cores did not share their knot and media reserve");
        });
        helper.succeed();
    }

    @GameTest(template = "conversion_test")
    public static void constructCapacityAndFailedPaymentAreAtomic(GameTestHelper helper) throws Exception {
        at25ms(helper, () -> {
            var level = helper.getLevel();
            var pos = helper.absolutePos(new BlockPos(0, 2, 0));
            level.setBlockAndUpdate(pos, ModBlocks.SPELL_CONSTRUCT.defaultBlockState());
            var construct = (SpellConstructBlockEntity) level.getBlockEntity(pos);
            construct.setItem(0, knot(123, 128000));
            ConstructMediaStorage.setStoredMedia(construct, 500);
            var trick = new ManaToMediaTrick();
            expect(NotEnoughManaBlunder.class, () -> trick.run(context(helper, construct), new NumberFragment(64)));
            helper.assertTrue(ConstructMediaStorage.getMedia(construct) == 500 && mana(construct.getItem(0), helper) == 123,
                    "Failed construct payment left free media or a partial knot debit");
            construct.setItem(0, knot(64000, 128000));
            ConstructMediaStorage.setStoredMedia(construct, ConstructMediaStorage.MAX_CAPACITY - 9999);
            expect(ResourceConversionBlunder.class, () -> trick.run(context(helper, construct), new NumberFragment(64)));
            helper.assertTrue(ConstructMediaStorage.getMedia(construct) == ConstructMediaStorage.MAX_CAPACITY - 9999
                    && mana(construct.getItem(0), helper) == 64000, "Capacity failure changed the construct reserves");
            ConstructMediaStorage.setStoredMedia(construct, ConstructMediaStorage.MAX_CAPACITY - 10000);
            var constructOutput = trick.run(context(helper, construct), new NumberFragment(64));
            helper.assertTrue(Math.abs(constructOutput.number() - 1.0) < 0.0001,
                    "Construct conversion did not return media in 10000-unit display values");
            helper.assertTrue(ConstructMediaStorage.getMedia(construct) == ConstructMediaStorage.MAX_CAPACITY
                    && mana(construct.getItem(0), helper) == 0, "Exact-capacity conversion overflowed or failed");
        });
        helper.succeed();
    }

    private record Circle(BlockEntityAbstractImpetus impetus, BlockPos north, BlockPos outside) {}

    private static Circle circle(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(0, 2, 0));
        for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) {
            var pos = base.offset(x, 0, z);
            level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            if (x == 1 && z == 1) continue;
            level.setBlockAndUpdate(pos, HexBlocks.SLATE.get().defaultBlockState()
                    .setValue(BlockSlate.ATTACH_FACE, AttachFace.FLOOR));
        }
        var impetusPos = base.offset(0, 0, 1);
        level.setBlockAndUpdate(impetusPos, HexBlocks.IMPETUS_REDSTONE.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.NORTH));
        var outside = base.offset(3, 0, 3);
        level.setBlockAndUpdate(outside.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(outside, HexBlocks.SLATE.get().defaultBlockState()
                .setValue(BlockSlate.ATTACH_FACE, AttachFace.FLOOR));
        return new Circle((BlockEntityAbstractImpetus) level.getBlockEntity(impetusPos), base, outside);
    }

    private static SlateKnotHolder holder(GameTestHelper helper, BlockPos pos) {
        return (SlateKnotHolder) helper.getLevel().getBlockEntity(pos);
    }

    private static ItemStack knot(long amount, long max) {
        var stack = new ItemStack(ModItems.AMETHYST_KNOT);
        stack.set(ModComponents.MANA, stack.get(ModComponents.MANA).with(
                new SimpleManaPool(amount, max, ManaVariant.of(Manae.TRADITIONAL))));
        return stack;
    }

    private static long mana(ItemStack stack, GameTestHelper helper) {
        return stack.get(ModComponents.MANA).pool().get(helper.getLevel());
    }

    private static SpellPart spell(double amount) {
        return new SpellPart(new PatternGlyph(3, 0, 4, 2), List.of(new SpellPart(new NumberFragment(amount))));
    }

    private static CastingImage input() {
        return new CastingImage(TreeList.from(List.of(new DoubleIota(1))), 0, TreeList.empty(),
                false, false, 0, new CompoundTag());
    }

    private static SpellContext context(GameTestHelper helper, SpellConstructBlockEntity construct) {
        return new SpellContext(new ExecutionState(List.of()),
                new BlockSpellSource<>(helper.getLevel(), construct.getBlockPos(), construct), new TickData());
    }

    private interface Checked { void run() throws Exception; }

    private static void expect(Class<? extends Throwable> type, Checked action) throws Exception {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void at25ms(GameTestHelper helper, Checked action) throws Exception {
        var field = ConversionTickTime.class.getDeclaredField("LAST_TICK");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var samples = (Map<MinecraftServer, Double>) field.get(null);
        var server = helper.getLevel().getServer();
        Double previous = samples.put(server, 25.0);
        try { action.run(); }
        finally { if (previous == null) samples.remove(server); else samples.put(server, previous); }
    }
}
