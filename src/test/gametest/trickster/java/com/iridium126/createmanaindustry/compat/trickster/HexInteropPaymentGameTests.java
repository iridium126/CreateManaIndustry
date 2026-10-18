package com.iridium126.createmanaindustry.compat.trickster;

import at.petrak.hexcasting.api.mod.HexStatistics;
import at.petrak.hexcasting.common.lib.HexDataComponents;
import com.iridium126.createmanaindustry.compat.trickster.casting.InvertedPayments;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexInteropPaymentGameTests {
    private static ServerPlayer player(GameTestHelper helper) {
        var sink = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "PaymentTest"));
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), sink.getGameProfile(), ClientInformation.createDefault());
        player.connection = sink.connection;
        return player;
    }
    private static ItemStack battery() {
        var stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("hexcasting:battery")));
        stack.set(HexDataComponents.MEDIA_MAX, 1000000L); stack.set(HexDataComponents.MEDIA, 1000000L);
        return stack;
    }
    private static int used(ServerPlayer player) { return player.getStats().getValue(Stats.CUSTOM.get(HexStatistics.MEDIA_USED)); }

    @GameTest(template = "trickster_stock_test")
    public static void rollbackRestoresCompleteComponentsAndDoesNotAwardMediaStatistics(GameTestHelper helper) {
        var player = player(helper);
        var stack = battery(); player.getInventory().setItem(0, stack);
        var before = stack.copy(); int statistics = used(player);
        try (var transaction = Transaction.openOuter()) {
            helper.assertTrue(InvertedPayments.spendMedia(player, 1000, transaction, true), "Simulation failed");
            helper.assertTrue(ItemStack.matches(stack, before) && used(player) == statistics, "Simulation changed resources/statistics");
            helper.assertTrue(InvertedPayments.spendMedia(player, 1000, transaction, false), "Reservation failed");
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("reservation only"));
            stack.remove(DataComponents.MAX_STACK_SIZE);
            helper.assertTrue(used(player) == statistics, "Statistics escaped before final commit");
        }
        helper.assertTrue(ItemStack.matches(stack, before), "Rollback merged patches instead of restoring the complete component map");
        helper.assertTrue(used(player) == statistics, "Aborted payment awarded statistics");
        try (var transaction = Transaction.openOuter()) {
            helper.assertTrue(InvertedPayments.spendMedia(player, 1000, transaction, false), "Committed reservation failed");
            transaction.commit();
        }
        helper.assertTrue(used(player) - statistics == InvertedPayments.manaToMedia(1000), "Final commit did not award exactly one native media statistic");
        InvertedPayments.logout(player); helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void finalMediaItemAndNestedReservationsReturnOnAbort(GameTestHelper helper) {
        var player = player(helper);
        var dust = new ItemStack(at.petrak.hexcasting.common.lib.HexItems.AMETHYST_DUST.get());
        player.getInventory().setItem(0, dust);
        try (var outer = Transaction.openOuter()) {
            helper.assertTrue(InvertedPayments.spendMedia(player, 1, outer, false), "Dust payment failed");
            helper.assertTrue(dust.getCount() == 0, "Last dust item was not reserved");
            helper.assertTrue(!InvertedPayments.spendMedia(player, 1, outer, false), "Second payment reused already reserved media");
            helper.assertTrue(used(player) == 0, "Reservation counted waste before commit");
        }
        helper.assertTrue(player.getInventory().getItem(0) == dust && dust.getCount() == 1, "Abort did not restore the last item's slot and count");
        helper.assertTrue(used(player) == 0, "Abort awarded a media statistic");
        InvertedPayments.logout(player); helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void equipmentMediaUsesTheNativePriorityAndCommitsOnce(GameTestHelper helper) {
        var player = player(helper);
        var stack = battery(); player.getInventory().setItem(39, stack);
        long fee = InvertedPayments.manaToMedia(1000);
        try (var transaction = Transaction.openOuter()) {
            helper.assertTrue(InvertedPayments.spendMedia(player, 1000, transaction, false)
                    && InvertedPayments.spendMedia(player, 1000, transaction, false), "Equipped media was omitted from native discovery");
            helper.assertTrue(used(player) == 0, "Nested reservations published statistics early");
            transaction.commit();
        }
        helper.assertTrue(stack.get(HexDataComponents.MEDIA) == 1000000L - fee * 2 && used(player) == fee * 2,
                "Equipped payment resources/statistics did not commit once for the aggregate reservation");
        InvertedPayments.logout(player); helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void nestedOvercastsReserveHealthAndOnlyDamageOnFinalCommit(GameTestHelper helper) {
        var player = player(helper);
        var advancement = helper.getLevel().getServer().getAdvancements().get(net.minecraft.resources.ResourceLocation.parse("hexcasting:y_u_no_cast_angy"));
        helper.assertTrue(advancement != null, "Native overcast advancement fixture missing");
        for (String criterion : player.getAdvancements().getOrStartProgress(advancement).getRemainingCriteria())
            player.getAdvancements().award(advancement, criterion);
        player.setHealth(1);
        try (var transaction = Transaction.openOuter()) {
            helper.assertTrue(InvertedPayments.spendMedia(player, 1, transaction, false)
                    && InvertedPayments.spendMedia(player, 1, transaction, false), "Eligible nested overcast was refused");
            helper.assertTrue(!InvertedPayments.spendMedia(player, 1, transaction, false), "Nested overcast reused reserved health");
            helper.assertTrue(player.getHealth() == 1 && used(player) == 0, "Overcast damaged/awarded statistics before commit");
        }
        helper.assertTrue(player.getHealth() == 1 && used(player) == 0, "Aborted overcast emitted side effects");
        player.setHealth(2);
        try (var transaction = Transaction.openOuter()) {
            helper.assertTrue(InvertedPayments.spendMedia(player, 1, transaction, false)
                    && InvertedPayments.spendMedia(player, 1, transaction, false), "Committed nested overcast failed");
            transaction.commit();
        }
        int expected = net.minecraft.util.Mth.ceil(at.petrak.hexcasting.api.mod.HexConfig.common().mediaToHealthRate());
        helper.assertTrue(player.getHealth() == 1 && used(player) == expected, "Final overcast damage/statistics were duplicated or omitted");
        InvertedPayments.logout(player); helper.succeed();
    }
}
