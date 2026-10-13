package com.iridium126.createmanaindustry.compat.trickster;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.HexActions;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.iridium126.createmanaindustry.compat.hexcasting.TrickIota;
import dev.enjarai.trickster.spell.PatternGlyph;
import dev.enjarai.trickster.spell.SpellPart;
import dev.enjarai.trickster.spell.execution.source.PlayerSpellSource;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.trick.Tricks;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class TrickIotaEvalGameTests {
    @GameTest(template = "trickster_stock_test")
    public static void hermesRunsSavedTrickWithoutArguments(GameTestHelper helper) {
        TrickIota original = literal(42.5);
        Iota saved = IotaType.TYPED_CODEC.parse(NbtOps.INSTANCE,
                IotaType.TYPED_CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow()).getOrThrow();
        helper.assertTrue(saved.executable(), "Saved TrickIota is not evaluatable");
        var vm = vm(helper, List.of(new DoubleIota(7), saved));
        cast(helper, vm, List.of(hermes()));
        assertStack(helper, vm, List.of(new DoubleIota(7), new DoubleIota(42.5)));
        helper.assertTrue(vm.getImage().getOpsConsumed() == 2,
                "Hermes and TrickIota must each consume one operation");
        helper.assertTrue(IXplatAbstractions.INSTANCE.getActionRegistry().get(
                ResourceLocation.fromNamespaceAndPath("createmanaindustry", "execute_trick")) == null,
                "Removed execute_trick action is still registered");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void hermesPassesOnlyTheTopArgumentList(GameTestHelper helper) {
        var keep = new ListIota(List.of(new DoubleIota(99)));
        var args = new ListIota(List.of(new DoubleIota(12), new ListIota(List.of(new DoubleIota(34)))));
        var trick = new TrickIota(new SpellPart(new PatternGlyph(Tricks.GET_ALL_ARGUMENTS.getPattern())));
        var vm = vm(helper, List.of(keep, args, trick));
        cast(helper, vm, List.of(hermes()));
        assertStack(helper, vm, List.of(keep, args));
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void hermesRunsTrickInsideHexListAndContinues(GameTestHelper helper) {
        var args = new ListIota(List.of(new DoubleIota(12)));
        var firstArgument = new TrickIota(new SpellPart(new PatternGlyph(Tricks.LOAD_ARGUMENT_1.getPattern())));
        var vm = vm(helper, List.of(new DoubleIota(7), args, new ListIota(List.of(firstArgument, literal(34)))));
        cast(helper, vm, List.of(hermes()));
        assertStack(helper, vm, List.of(new DoubleIota(7), new DoubleIota(12), new DoubleIota(34)));
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void escapingAndParenthesesKeepTrickAsData(GameTestHelper helper) {
        TrickIota trick = literal(42);
        var args = new ListIota(List.of(new DoubleIota(12)));
        var vm = vm(helper, List.of(args));
        var initial = vm.getImage();
        vm.setImage(initial.copy(initial.getStack(), 0, TreeList.empty(), true, false, 0, initial.getUserData()));
        cast(helper, vm, List.of(trick));
        assertStack(helper, vm, List.of(args, trick));
        helper.assertTrue(vm.getImage().getOpsConsumed() == 0, "Escaped TrickIota executed");

        vm.setImage(initial.copy(initial.getStack(), 1, TreeList.empty(), false, false, 0, initial.getUserData()));
        var result = vm.executeInner(trick, helper.getLevel(), SpellContinuation.Done.INSTANCE);
        helper.assertTrue(result.getResolutionType() == ResolvedPatternType.ESCAPED,
                "Parenthesized TrickIota executed");
        helper.assertTrue(result.getNewData().getStack().equals(initial.getStack())
                        && result.getNewData().getParenthesized().last().getIota() == trick,
                "Parenthesized TrickIota did not remain data");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void failedTrickConsumesArgumentsAndContinues(GameTestHelper helper) {
        var missingArgument = new TrickIota(new SpellPart(new PatternGlyph(Tricks.LOAD_ARGUMENT_1.getPattern())));
        var vm = vm(helper, List.of(new DoubleIota(7), new ListIota(List.of()), missingArgument));
        cast(helper, vm, List.of(hermes(), literal(34)));
        assertStack(helper, vm, List.of(new DoubleIota(7), new DoubleIota(34)));
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void delayedTrickContinuesInBackgroundWithoutPushingResult(GameTestHelper helper) throws Exception {
        var vm = vm(helper, List.of(new DoubleIota(7), new TrickIota(new SpellPart(
                new PatternGlyph(Tricks.DELAY_EXECUTION.getPattern()), List.of(new SpellPart(new NumberFragment(2)))))));
        var source = new PlayerSpellSource((ServerPlayer) vm.getEnv().getCastingEntity());
        var manager = source.getExecutionManager().orElseThrow();
        manager.killAll();
        try {
            cast(helper, vm, List.of(hermes(), literal(34)));
            assertStack(helper, vm, List.of(new DoubleIota(7), new DoubleIota(34)));
            var queued = manager.getSpellExecutor(0).orElseThrow();
            java.util.Optional<dev.enjarai.trickster.spell.Fragment> result = java.util.Optional.empty();
            for (int i = 0; i < 4 && result.isEmpty(); i++) result = queued.run(source);
            helper.assertTrue(result.orElseThrow() instanceof NumberFragment number && number.number() == 2,
                    "Handed-off Trickster spell did not finish");
            assertStack(helper, vm, List.of(new DoubleIota(7), new DoubleIota(34)));
        } finally {
            manager.killAll();
        }
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void nonPlayerEnvironmentConsumesArgumentsWithoutResult(GameTestHelper helper) {
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());
        var env = new QuietEnvironment(player) {
            @Override public LivingEntity getCastingEntity() { return null; }
        };
        var vm = new CastingVM(image(List.of(new DoubleIota(7), new ListIota(List.of(new DoubleIota(12))), literal(42))), env);
        cast(helper, vm, List.of(hermes()));
        assertStack(helper, vm, List.of(new DoubleIota(7)));
        helper.succeed();
    }

    private static TrickIota literal(double number) {
        return new TrickIota(new SpellPart(new NumberFragment(number)));
    }

    private static PatternIota hermes() {
        return new PatternIota(HexActions.EVAL.value().prototype());
    }

    private static CastingImage image(List<Iota> stack) {
        return new CastingImage(TreeList.from(stack), 0, TreeList.empty(), false, false, 0, new CompoundTag());
    }

    private static CastingVM vm(GameTestHelper helper, List<Iota> stack) {
        return new CastingVM(image(stack), new QuietEnvironment(FakePlayerFactory.getMinecraft(helper.getLevel())));
    }

    private static void cast(GameTestHelper helper, CastingVM vm, List<Iota> code) {
        var view = vm.queueExecuteAndWrapIotas(code, helper.getLevel());
        helper.assertTrue(view.getResolutionType().getSuccess(), "TrickIota cast failed: " + view.getResolutionType());
    }

    private static void assertStack(GameTestHelper helper, CastingVM vm, List<Iota> expected) {
        helper.assertTrue(vm.getImage().getStack().equals(expected), "Unexpected stack: " + vm.getImage().getStack());
    }

    private static class QuietEnvironment extends StaffCastEnv {
        QuietEnvironment(ServerPlayer player) { super(player, InteractionHand.MAIN_HAND); }
        @Override public void postExecution(CastResult result) {}
        @Override public void postCast(CastingImage image) {}
    }

    private TrickIotaEvalGameTests() {}
}
