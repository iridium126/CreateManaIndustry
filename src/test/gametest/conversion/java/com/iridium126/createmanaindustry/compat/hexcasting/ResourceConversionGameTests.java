package com.iridium126.createmanaindustry.compat.hexcasting;

import com.iridium126.createmanaindustry.compat.hextrick.bootstrap.CMIHexTrickActions;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.ResourceConversionInventory;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.ConversionTickTime;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.OpMediaToMana;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.MishapResourceConversion;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.iota.NullIota;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia;
import at.petrak.hexcasting.api.item.MediaHolderItem;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.HexDataComponents;
import at.petrak.hexcasting.common.lib.HexItems;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.ManaToMediaTrick;
import com.iridium126.createmanaindustry.compat.trickster.blunder.ResourceConversionBlunder;
import com.mojang.authlib.GameProfile;
import dev.enjarai.trickster.item.ModItems;
import dev.enjarai.trickster.item.component.ManaComponent;
import dev.enjarai.trickster.item.component.ModComponents;
import dev.enjarai.trickster.spell.Pattern;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.execution.ExecutionState;
import dev.enjarai.trickster.spell.execution.TickData;
import dev.enjarai.trickster.spell.execution.source.PlayerSpellSource;
import dev.enjarai.trickster.spell.execution.source.SpellSource;
import dev.enjarai.trickster.spell.exception.blunder.NotEnoughManaBlunder;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.fragment.VoidFragment;
import dev.enjarai.trickster.spell.mana.InfiniteManaPool;
import dev.enjarai.trickster.spell.mana.MutableManaPool;
import dev.enjarai.trickster.spell.mana.OverfillingManaPool;
import dev.enjarai.trickster.spell.mana.SavingsManaPool;
import dev.enjarai.trickster.spell.mana.SimpleManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import dev.enjarai.trickster.spell.mana.type.Manae;
import dev.enjarai.trickster.spell.trick.Tricks;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class ResourceConversionGameTests {
    private static final ManaVariant TRADITIONAL = ManaVariant.of(Manae.TRADITIONAL);

    private static class Env extends StaffCastEnv {
        Env(ServerPlayer player) { super(player, InteractionHand.MAIN_HAND); }
        @Override public void produceParticles(ParticleSpray spray, FrozenPigment pigment) {}
        @Override protected void sendMishapMsgToPlayer(OperatorSideEffect.DoMishap mishap) {}
    }

    @GameTest(template = "conversion_test")
    public static void registeredOperationsRoundTripAt25ms(GameTestHelper helper) throws Exception {
        fixedTickTime(helper, () -> {
            var player = player(helper);
            var inventory = player.getInventory();
            inventory.setItem(0, battery(10000, 20000));
            inventory.setItem(1, knot(new SimpleManaPool(128000)));
            var entry = CMIHexTrickActions.MEDIA_TO_MANA.get();
            helper.assertTrue(entry.prototype().equals(
                    at.petrak.hexcasting.api.casting.math.HexPattern.fromAngleString("dadaw", HexDir.NORTH_EAST)),
                    "Wrong Hex pattern");
            var vm = new CastingVM(new CastingImage(TreeList.from(List.<Iota>of(new DoubleIota(1))),
                    0, TreeList.empty(), false, false, 0, new CompoundTag()), new Env(player));
            var result = vm.queueExecuteAndWrapIota(new PatternIota(entry.prototype()), helper.getLevel());
            helper.assertTrue(result.getResolutionType().getSuccess(), "Forward VM operation failed");
            var forwardStack = vm.getImage().getStack();
            helper.assertTrue(forwardStack.size() == 1
                            && forwardStack.get(0) instanceof DoubleIota output
                            && Math.abs(output.getDouble() - 64.0) < 0.0001,
                    "Forward operation did not return the stored mana points");
            helper.assertTrue(media(inventory.getItem(0)) == 0, "Forward cost was not 10000 media");
            helper.assertTrue(mana(inventory.getItem(1), helper) == 64000, "Forward output was not 64000 internal mana");
            var trick = Tricks.lookup(Pattern.of(3, 0, 4, 2));
            helper.assertTrue(trick instanceof ManaToMediaTrick
                    && Tricks.REGISTRY.getKey(trick).equals(CreateManaIndustry.modLoc("mana_to_media")),
                    "Wrong Trickster pattern resolution");
            var reverseOutput = trick.activate(context(player), List.of(new NumberFragment(64)));
            helper.assertTrue(reverseOutput instanceof NumberFragment amount && Math.abs(amount.number() - 1.0) < 0.0001,
                    "Reverse operation did not return media in 10000-unit display values");
            helper.assertTrue(mana(inventory.getItem(1), helper) == 0, "Reverse cost scaled incorrectly");
            helper.assertTrue(media(inventory.getItem(0)) == 10000, "Reverse output scaled incorrectly");
            var reloadedBattery = ItemStack.parseOptional(helper.getLevel().registryAccess(),
                    (CompoundTag) inventory.getItem(0).save(helper.getLevel().registryAccess()));
            var reloadedKnot = ItemStack.parseOptional(helper.getLevel().registryAccess(),
                    (CompoundTag) inventory.getItem(1).save(helper.getLevel().registryAccess()));
            helper.assertTrue(media(reloadedBattery) == 10000 && mana(reloadedKnot, helper) == 0,
                    "Converted values did not survive item serialization");
        });
        helper.succeed();
    }

    @GameTest(template = "conversion_test")
    public static void knotCapacityAndOriginalSlotOrder(GameTestHelper helper) throws Exception {
        fixedTickTime(helper, () -> {
            var player = player(helper);
            var inventory = player.getInventory();
            inventory.setItem(1, knot(new SimpleManaPool(10000)));
            inventory.setItem(36, knot(new SimpleManaPool(10000)));
            inventory.setItem(40, knot(new SimpleManaPool(44000)));
            var plan = ResourceConversionInventory.prepareMana(player, 64000);
            helper.assertTrue(mana(inventory.getItem(1), helper) == 0, "Capacity preflight changed live mana");
            plan.apply();
            helper.assertTrue(mana(inventory.getItem(1), helper) == 10000
                    && mana(inventory.getItem(36), helper) == 10000
                    && mana(inventory.getItem(40), helper) == 44000, "Armor/offhand capacity was not included");
            expect(helper, MishapResourceConversion.class,
                    () -> OpMediaToMana.INSTANCE.execute(List.of(new DoubleIota(1)), new Env(player)));
            inventory.clearContent();
            expect(helper, MishapResourceConversion.class,
                    () -> OpMediaToMana.INSTANCE.execute(List.of(new DoubleIota(1)), new Env(player)));
            inventory.setItem(0, knot(InfiniteManaPool.INSTANCE));
            inventory.setItem(1, knot(new SimpleManaPool(1, 100000, ManaVariant.of(Manae.STORM))));
            inventory.setItem(2, knot(new SimpleManaPool(0)));
            expect(helper, MishapResourceConversion.class,
                    () -> OpMediaToMana.INSTANCE.execute(List.of(new DoubleIota(1)), new Env(player)));
            helper.assertTrue(mana(inventory.getItem(1), helper) == 1, "Incompatible mana was changed");
        });
        helper.succeed();
    }

    @GameTest(template = "conversion_test")
    public static void specialKnotPoolsAndFractionalMana(GameTestHelper helper) throws Exception {
        fixedTickTime(helper, () -> {
            var player = player(helper);
            var savings = new SavingsManaPool(100000, 0.001f);
            savings.set(0, helper.getLevel());
            player.getInventory().setItem(0, knot(savings));
            ResourceConversionInventory.prepareMana(player, 64000).apply();
            helper.assertTrue(player.getInventory().getItem(0).get(ModComponents.MANA).pool() instanceof SavingsManaPool,
                    "Savings pool was replaced with a simple pool");
            helper.assertTrue(mana(player.getInventory().getItem(0), helper) == 64000, "Savings pool received wrong amount");
            player.getInventory().setItem(0, knot(new OverfillingManaPool(100000)));
            var result = OpMediaToMana.INSTANCE.execute(List.of(new DoubleIota(0.0001)), new Env(player));
            helper.assertTrue(result.getCost() == 1, "Fractional media cost did not truncate");
            result.getEffect().cast(new Env(player));
            helper.assertTrue(mana(player.getInventory().getItem(0), helper) == 6
                    && player.getInventory().getItem(0).get(ModComponents.MANA).pool() instanceof OverfillingManaPool,
                    "Fractional mana or overfilling pool was changed incorrectly");
            expect(helper, MishapResourceConversion.class,
                    () -> OpMediaToMana.INSTANCE.execute(List.of(new DoubleIota(0.00001)), new Env(player)));
        });
        helper.succeed();
    }

    @GameTest(template = "conversion_test")
    public static void rechargeableMediaCapacityAndSlotOrder(GameTestHelper helper) throws Exception {
        fixedTickTime(helper, () -> {
            var player = player(helper);
            var inventory = player.getInventory();
            inventory.setItem(0, battery(0, 4000));
            var cypher = new ItemStack(HexItems.CYPHER.get());
            cypher.set(HexDataComponents.MEDIA_MAX.get(), 100000L);
            inventory.setItem(1, cypher);
            inventory.setItem(36, battery(0, 2000));
            inventory.setItem(40, battery(0, 4000));
            inventory.setItem(2, knot(new SimpleManaPool(64000, 64000, TRADITIONAL)));
            new ManaToMediaTrick().run(context(player), new NumberFragment(64));
            helper.assertTrue(media(inventory.getItem(0)) == 4000 && media(inventory.getItem(36)) == 2000
                    && media(inventory.getItem(40)) == 4000 && media(inventory.getItem(1)) == 0,
                    "Reverse operation missed slots or recharged a cypher");
            helper.assertTrue(mana(inventory.getItem(2), helper) == 0, "Reverse mana debit was incorrect");
            inventory.setItem(2, knot(new SimpleManaPool(64000, 64000, TRADITIONAL)));
            expect(helper, ResourceConversionBlunder.class,
                    () -> new ManaToMediaTrick().run(context(player), new NumberFragment(64)));
            helper.assertTrue(mana(inventory.getItem(2), helper) == 64000, "Capacity failure consumed mana");
        });
        helper.succeed();
    }

    @GameTest(template = "conversion_test")
    public static void failedManaDebitRollsBackEveryDestination(GameTestHelper helper) throws Exception {
        fixedTickTime(helper, () -> {
            var player = player(helper);
            var inventory = player.getInventory();
            inventory.setItem(0, battery(0, 4000));
            inventory.setItem(1, battery(0, 6000));
            inventory.setItem(2, knot(new SimpleManaPool(123, 64000, TRADITIONAL)));
            expect(helper, NotEnoughManaBlunder.class,
                    () -> new ManaToMediaTrick().run(context(player), new NumberFragment(64)));
            helper.assertTrue(media(inventory.getItem(0)) == 0 && media(inventory.getItem(1)) == 0,
                    "Failed payment left partial media charging");
            helper.assertTrue(mana(inventory.getItem(2), helper) == 123, "Failed payment partially consumed mana");
            inventory.setItem(2, knot(new SimpleManaPool(64000, 64000, TRADITIONAL)));
            var disabled = new SpellContext(new ExecutionState(List.of()), new PlayerSpellSource(player, null),
                    new TickData().withCanUseMana(false));
            expect(helper, NotEnoughManaBlunder.class,
                    () -> new ManaToMediaTrick().run(disabled, new NumberFragment(64)));
            helper.assertTrue(media(inventory.getItem(0)) == 0 && media(inventory.getItem(1)) == 0,
                    "Mana-disabled execution left media charging");
            new ManaToMediaTrick().run(context(player), new NumberFragment(0.007));
            helper.assertTrue(media(inventory.getItem(0)) == 1 && mana(inventory.getItem(2), helper) == 63993,
                    "Fractional reverse conversion did not use 0.001 mana units");
            expect(helper, ResourceConversionBlunder.class,
                    () -> new ManaToMediaTrick().run(context(player), new NumberFragment(0.001)));
        });
        helper.succeed();
    }

    @GameTest(template = "conversion_test")
    public static void invalidInputsAndMissingPlayerLeaveResourcesAlone(GameTestHelper helper) throws Exception {
        fixedTickTime(helper, () -> {
            var player = player(helper);
            player.getInventory().setItem(0, battery(0, 20000));
            player.getInventory().setItem(1, knot(new SimpleManaPool(64000, 128000, TRADITIONAL)));
            for (double value : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
                expect(helper, MishapResourceConversion.class,
                        () -> OpMediaToMana.INSTANCE.execute(List.of(new DoubleIota(value)), new Env(player)));
                expect(helper, Double.isFinite(value) ? ResourceConversionBlunder.class
                                : dev.enjarai.trickster.spell.exception.TricksterEngineException.class,
                        () -> new ManaToMediaTrick().run(context(player), new NumberFragment(value)));
            }
            expect(helper, Mishap.class,
                    () -> OpMediaToMana.INSTANCE.execute(List.of(new NullIota()), new Env(player)));
            expect(helper, dev.enjarai.trickster.spell.exception.TricksterEngineException.class,
                    () -> new ManaToMediaTrick().activate(context(player), List.of(VoidFragment.INSTANCE)));
            var noPlayerEnv = new Env(player) { @Override public ServerPlayer getCastingEntity() { return null; } };
            expect(helper, MishapResourceConversion.class,
                    () -> OpMediaToMana.INSTANCE.execute(List.of(new DoubleIota(1)), noPlayerEnv));
            var source = (SpellSource) Proxy.newProxyInstance(SpellSource.class.getClassLoader(),
                    new Class<?>[]{SpellSource.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getWorld" -> helper.getLevel();
                        case "getPlayer" -> Optional.empty();
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            expect(helper, ResourceConversionBlunder.class, () -> new ManaToMediaTrick().run(
                    new SpellContext(new ExecutionState(List.of()), source, new TickData()), new NumberFragment(64)));
            helper.assertTrue(mana(player.getInventory().getItem(1), helper) == 64000
                    && media(player.getInventory().getItem(0)) == 0, "Invalid inputs changed resources");
        });
        helper.succeed();
    }

    @GameTest(template = "conversion_test")
    public static void mediaPrecheckAndCompletedTickSampling(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        double expected = server.getTickTimesNanos()[Math.floorMod(server.getTickCount() - 1, 100)] / 1_000_000.0;
        helper.assertTrue(ConversionTickTime.milliseconds(server) == expected, "Sampler did not use the completed tick");
        fixedTickTime(helper, () -> {
            var player = player(helper);
            player.getInventory().setItem(1, knot(new SimpleManaPool(64000)));
            var env = new Env(player) {
                @Override public long extractMediaEnvironment(long amount, boolean simulate) { return amount; }
            };
            expect(helper, MishapNotEnoughMedia.class,
                    () -> OpMediaToMana.INSTANCE.operate(env, new CastingImage(TreeList.from(List.<Iota>of(new DoubleIota(1))),
                            0, TreeList.empty(), false, false, 0, new CompoundTag()),
                            at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation.Done.INSTANCE));
            helper.assertTrue(mana(player.getInventory().getItem(1), helper) == 0, "Failed media precheck charged a knot");
        });
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "ConversionTest"));
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        return player;
    }

    private static SpellContext context(ServerPlayer player) {
        return new SpellContext(new ExecutionState(List.of()), new PlayerSpellSource(player, null), new TickData());
    }

    private static ItemStack battery(long current, long max) {
        var stack = new ItemStack(HexItems.BATTERY.get());
        stack.set(HexDataComponents.MEDIA_MAX.get(), max);
        ((MediaHolderItem) stack.getItem()).setMedia(stack, current);
        return stack;
    }

    private static ItemStack knot(MutableManaPool pool) {
        var stack = new ItemStack(ModItems.AMETHYST_KNOT);
        stack.set(ModComponents.MANA, stack.get(ModComponents.MANA).with(pool));
        return stack;
    }

    private static long media(ItemStack stack) { return ((MediaHolderItem) stack.getItem()).getMedia(stack); }
    private static long mana(ItemStack stack, GameTestHelper helper) { return stack.get(ModComponents.MANA).pool().get(helper.getLevel()); }

    private interface Checked { void run() throws Exception; }

    private static void expect(GameTestHelper helper, Class<? extends Throwable> type, Checked action) throws Exception {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure, failure);
        }
        helper.fail("Expected " + type.getSimpleName());
    }

    private static void fixedTickTime(GameTestHelper helper, Checked action) throws Exception {
        var field = ConversionTickTime.class.getDeclaredField("LAST_TICK");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var samples = (Map<MinecraftServer, Double>) field.get(null);
        var server = helper.getLevel().getServer();
        Double previous = samples.put(server, 25.0);
        try { action.run(); }
        finally { if (previous == null) samples.remove(server); else samples.put(server, previous); }
    }
}
