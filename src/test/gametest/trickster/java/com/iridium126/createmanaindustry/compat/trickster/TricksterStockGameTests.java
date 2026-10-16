package com.iridium126.createmanaindustry.compat.trickster;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.foundation.item.ItemHelper;
import dev.enjarai.trickster.item.ModItems;
import dev.enjarai.trickster.item.component.ModComponents;
import dev.enjarai.trickster.spell.mana.MutableManaPool;
import dev.enjarai.trickster.spell.mana.OverfillingManaPool;
import dev.enjarai.trickster.spell.mana.SavingsManaPool;
import dev.enjarai.trickster.spell.mana.SimpleManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import dev.enjarai.trickster.spell.mana.type.Manae;
import dev.enjarai.trickster.spell.mana.type.component.ColorComponent;
import dev.enjarai.trickster.spell.mana.type.component.ManaComponents;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.mutable.MutableBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class TricksterStockGameTests {
    @GameTest(template = "trickster_stock_test")
    public static void chargedKnotsSurviveStockRefreshAndExtraction(GameTestHelper helper) {
        var savings = new SavingsManaPool(128000, 0.001f);
        savings.setVariant(ManaVariant.of(Manae.TRADITIONAL), helper.getLevel());
        savings.set(64000, helper.getLevel());
        for (MutableManaPool pool : new MutableManaPool[] {
                new SimpleManaPool(64000, 128000, ManaVariant.of(Manae.TRADITIONAL)),
                new SimpleManaPool(0, 128000, ManaVariant.of(Manae.TRADITIONAL)),
                new SimpleManaPool(64000, 128000, coloredMana(255, 0, 0)),
                new OverfillingManaPool(160000, 128000, ManaVariant.of(Manae.STORM)), savings }) {
            var stored = knot(pool);
            var firstSnapshot = roundTrip(helper, stored);
            var nextSnapshot = roundTrip(helper, stored);
            helper.assertTrue(firstSnapshot.get(ModComponents.MANA).pool() != nextSnapshot.get(ModComponents.MANA).pool(),
                    "Stock snapshots must contain independently decoded mana pools");
            var summary = new InventorySummary();
            summary.add(nextSnapshot, 3);
            helper.assertTrue(summary.getCountOf(firstSnapshot) == 3,
                    "Stock refresh discarded a charged knot order: " + pool.type());
            helper.assertTrue(ItemStack.isSameItemSameComponents(stored, firstSnapshot),
                    "Decoded order does not match the warehouse knot");
            helper.assertTrue(ItemStack.hashItemAndComponents(stored) == ItemStack.hashItemAndComponents(firstSnapshot),
                    "Decoded charged knot has a different component hash");
            var reloaded = ItemStack.parseOptional(helper.getLevel().registryAccess(),
                    (net.minecraft.nbt.CompoundTag) stored.save(helper.getLevel().registryAccess()));
            helper.assertTrue(ItemStack.isSameItemSameComponents(stored, reloaded),
                    "Knot equality changed after saving and reloading");
            var inventory = new ItemStackHandler(1);
            inventory.setStackInSlot(0, stored);
            var extracted = ItemHelper.extract(inventory,
                    stack -> ItemStack.isSameItemSameComponents(stack, firstSnapshot), 1, false);
            helper.assertTrue(!extracted.isEmpty() && inventory.getStackInSlot(0).isEmpty(),
                    "Create could not extract the decoded knot order");
        }
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void differentManaRemainsDistinct(GameTestHelper helper) {
        var stored = knot(new SimpleManaPool(64000, 128000, ManaVariant.of(Manae.TRADITIONAL)));
        var summary = new InventorySummary();
        summary.add(roundTrip(helper, stored), 3);
        for (MutableManaPool different : new MutableManaPool[] {
                new SimpleManaPool(63000, 128000, ManaVariant.of(Manae.TRADITIONAL)),
                new SimpleManaPool(64000, 256000, ManaVariant.of(Manae.TRADITIONAL)),
                new SimpleManaPool(64000, 128000, ManaVariant.of(Manae.STORM)) }) {
            helper.assertTrue(summary.getCountOf(roundTrip(helper, knot(different))) == 0,
                    "Different mana amount, capacity or type was merged into stock");
        }
        var a = new SavingsManaPool(128000, 0.001f);
        var b = new SavingsManaPool(128000, 0.002f);
        a.set(64000, helper.getLevel());
        b.set(64000, helper.getLevel());
        helper.assertTrue(!roundTrip(helper, knot(a)).get(ModComponents.MANA).equals(
                roundTrip(helper, knot(b)).get(ModComponents.MANA)), "Savings interest was ignored");
        helper.assertTrue(!ItemStack.isSameItemSameComponents(
                roundTrip(helper, knot(new SimpleManaPool(64000, 128000, coloredMana(255, 0, 0)))),
                roundTrip(helper, knot(new SimpleManaPool(64000, 128000, coloredMana(0, 0, 255))))),
                "Different color mana components were merged");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void decodedOrderProducesPackageFromWarehouse(GameTestHelper helper) {
        var chestPos = new BlockPos(1, 1, 0);
        var packagerPos = new BlockPos(1, 1, 1);
        helper.setBlock(chestPos, Blocks.CHEST);
        helper.setBlock(packagerPos, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.SOUTH));
        var chest = (ChestBlockEntity) helper.getBlockEntity(chestPos);
        var packager = (PackagerBlockEntity) helper.getBlockEntity(packagerPos);
        var stored = knot(new SimpleManaPool(64000, 128000, ManaVariant.of(Manae.TRADITIONAL)));
        chest.setItem(0, stored.copy());
        // The request has crossed the same item codec used by Stock Ticker packets.
        var requestItem = roundTrip(helper, roundTrip(helper, stored));
        var requests = new ArrayList<>(List.of(PackagingRequest.create(requestItem, 1,
                "Trickster regression", 0, new MutableBoolean(true), 0, 1, null)));
        packager.targetInventory.findNewCapability();
        helper.assertTrue(packager.targetInventory.getInventory() != null, "Packager did not find the warehouse chest");
        packager.attemptToSend(requests);
        helper.assertTrue(chest.getItem(0).isEmpty(), "Charged knot remained in the warehouse");
        helper.assertTrue(!packager.heldBox.isEmpty(), "Decoded order did not produce a package");
        var contents = PackageItem.getContents(packager.heldBox);
        helper.assertTrue(ItemStack.isSameItemSameComponents(stored, contents.getStackInSlot(0)),
                "Package did not preserve the charged knot components");
        helper.assertTrue(PackageItem.getAddress(packager.heldBox).equals("Trickster regression"),
                "Package lost its requested address");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void defaultTricksterItemsSurviveStockSnapshots(GameTestHelper helper) {
        for (var item : BuiltInRegistries.ITEM) {
            if (!BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("trickster")) continue;
            var stored = item.getDefaultInstance();
            var decoded = roundTrip(helper, stored);
            helper.assertTrue(ItemStack.isSameItemSameComponents(stored, decoded),
                    "Default item changed after stock sync: " + BuiltInRegistries.ITEM.getKey(item));
            helper.assertTrue(ItemStack.hashItemAndComponents(stored) == ItemStack.hashItemAndComponents(decoded),
                    "Equal stock items have different hashes: " + BuiltInRegistries.ITEM.getKey(item));
        }
        helper.succeed();
    }

    private static ItemStack knot(MutableManaPool pool) {
        var stack = new ItemStack(ModItems.AMETHYST_KNOT);
        stack.set(ModComponents.MANA, stack.get(ModComponents.MANA).with(pool));
        return stack;
    }

    private static ManaVariant coloredMana(double red, double green, double blue) {
        return ManaVariant.of(Manae.COLOR, DataComponentPatch.builder()
                .set(ManaComponents.COLOR, new ColorComponent(red, green, blue)).build());
    }

    private static ItemStack roundTrip(GameTestHelper helper, ItemStack stack) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            BigItemStack.STREAM_CODEC.encode(buffer, new BigItemStack(stack, 3));
            return BigItemStack.STREAM_CODEC.decode(buffer).stack;
        } finally {
            buffer.release();
        }
    }
}
