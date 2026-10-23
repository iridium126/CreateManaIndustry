package com.iridium126.createmanaindustry.compat.trickster;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.EntityIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.iota.NullIota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.HexActions;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.FragmentConverter;
import com.iridium126.createmanaindustry.compat.hextrick.data.IotaFragment;
import com.iridium126.createmanaindustry.compat.hextrick.data.TrickIota;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.PatternGlyph;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.SpellPart;
import dev.enjarai.trickster.spell.execution.ExecutionState;
import dev.enjarai.trickster.spell.execution.TickData;
import dev.enjarai.trickster.spell.execution.source.PlayerSpellSource;
import dev.enjarai.trickster.spell.fragment.EntityFragment;
import dev.enjarai.trickster.spell.fragment.ListFragment;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.fragment.VoidFragment;
import dev.enjarai.trickster.spell.trick.Tricks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class TrickIotaEvalGameTests {
    @GameTest(template = "trickster_stock_test")
    public static void readListStaysWrappedAfterSaveAndRunsThroughSignature(GameTestHelper helper) {
        var context = trickContext(helper);
        var code = new ListIota(List.of(escape(), new DoubleIota(42), escape(), new DoubleIota(8), add()));
        var read = readFocus(helper, context, code);
        helper.assertTrue(read instanceof IotaFragment, "Reading a Hex list expanded it into native fragments");
        var saved = (IotaFragment) Fragment.fromBytes(read.toBytes());
        helper.assertTrue(saved.getIota().equals(code), "Saved list lost its Hex data");
        // InlinePatternData uses identity equality; compare the serialized text and styles.
        helper.assertTrue(Component.Serializer.toJson(saved.asText(), helper.getLevel().registryAccess())
                        .equals(Component.Serializer.toJson(code.display(), helper.getLevel().registryAccess())),
                "Saved list lost its Hex display");
        var result = CMITricks.Hexcasting.EVAL_IOTA.activate(context, List.of(saved));
        helper.assertTrue(result.equals(new NumberFragment(50)), "Read list could not be run directly through the signature");

        var nested = new ListIota(List.of(new DoubleIota(1), new NullIota(),
                new ListIota(List.of(escape(), new DoubleIota(2)))));
        var wrapped = (IotaFragment) readFocus(helper, context, nested);
        helper.assertTrue(wrapped.getIota().equals(nested), "Reading a list changed its nested data");
        helper.assertTrue(FragmentConverter.iotaToFragment(nested) instanceof ListFragment,
                "The item reader changed general-purpose list conversion");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void readScalarsStillProduceNativeFragments(GameTestHelper helper) {
        var context = trickContext(helper);
        helper.assertTrue(readFocus(helper, context, new DoubleIota(42)).equals(new NumberFragment(42)),
                "Reading a number stopped producing a native NumberFragment");
        var player = context.source().getPlayer().orElseThrow();
        helper.assertTrue(readFocus(helper, context, new EntityIota(player))
                        instanceof EntityFragment entity && entity.uuid().equals(player.getUUID()),
                "Reading an entity stopped producing a native EntityFragment");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void evalMatchesHermesForPatternsListsAndOtherExecutableIotas(GameTestHelper helper) {
        for (Iota code : List.of(add(), new ListIota(List.of(add())), literal(34), new ListIota(List.of(literal(34))))) {
            assertEvalMatchesHermes(helper, code, List.of(new NumberFragment(20), new NumberFragment(22)));
        }
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void evalMatchesHermesForEmptyAndNestedLists(GameTestHelper helper) {
        var empty = new ListIota(List.of());
        assertEvalMatchesHermes(helper, empty, List.of());
        assertEvalMatchesHermes(helper, empty, List.of(new NumberFragment(7)));
        var nested = new ListIota(List.of(add()));
        assertEvalMatchesHermes(helper, new ListIota(List.of(nested)),
                List.of(new NumberFragment(20), new NumberFragment(22)));
        assertEvalMatchesHermes(helper, new ListIota(List.of(escape(), nested)), List.of());
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void evalUsesHermesHaltBoundaries(GameTestHelper helper) {
        var halt = new PatternIota(HexActions.HALT.value().prototype());
        var inner = new ListIota(List.of(escape(), new DoubleIota(42), halt, escape(), new DoubleIota(66)));
        var outer = new ListIota(List.of(escape(), inner, hermes(), escape(), new DoubleIota(99)));
        assertEvalMatchesHermes(helper, inner, List.of());
        assertEvalMatchesHermes(helper, outer, List.of());
        helper.succeed();
    }

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

    private static PatternIota escape() {
        return new PatternIota(HexActions.ESCAPE.value().prototype());
    }

    private static PatternIota add() {
        return new PatternIota(HexActions.ADD.value().prototype());
    }

    private static SpellContext trickContext(GameTestHelper helper) {
        return new SpellContext(new ExecutionState(List.of()),
                new PlayerSpellSource(FakePlayerFactory.getMinecraft(helper.getLevel())), new TickData());
    }

    private static Fragment readFocus(GameTestHelper helper, SpellContext context, Iota iota) {
        // Use the saved-item codec to avoid Hex's client-only Font setter signature.
        var saved = new CompoundTag();
        saved.putString("id", "hexcasting:focus");
        saved.putInt("count", 1);
        var components = new CompoundTag();
        components.put("hexcasting:iota", IotaType.TYPED_CODEC.encodeStart(NbtOps.INSTANCE, iota).getOrThrow());
        saved.put("components", components);
        var player = context.source().getPlayer().orElseThrow();
        var previous = player.getOffhandItem();
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.parseOptional(helper.getLevel().registryAccess(), saved));
        try {
            return (Fragment) CMITricks.Hexcasting.READ_IOTA.activate(context, List.of());
        } finally {
            player.setItemInHand(InteractionHand.OFF_HAND, previous);
        }
    }

    private static void assertEvalMatchesHermes(GameTestHelper helper, Iota code, List<Fragment> args) {
        var stack = new ArrayList<Iota>();
        args.forEach(arg -> stack.add(FragmentConverter.fragmentToIota(arg, helper.getLevel())));
        stack.add(code);
        var reference = vm(helper, stack).queueExecuteAndWrapIota(hermes(), helper.getLevel());
        Fragment expected = reference.getResolutionType().getSuccess()
                ? FragmentConverter.iotaToFragment(reference.getStackDescs().isEmpty()
                        ? new NullIota() : reference.getStackDescs().getLast())
                : VoidFragment.INSTANCE;
        var inputs = new ArrayList<Fragment>();
        inputs.add(new IotaFragment(IotaFragment.serializeIota(code)));
        inputs.addAll(args);
        var actual = CMITricks.Hexcasting.EVAL_IOTA.activate(trickContext(helper), inputs);
        helper.assertTrue(expected.equals(actual), "Eval Iota differed from Hermes for " + code.display().getString()
                + ": expected " + expected + ", got " + actual);
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
