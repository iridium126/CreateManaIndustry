package com.iridium126.createmanaindustry.client.ponder;

import static com.iridium126.createmanaindustry.client.ponder.Workshop.*;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.CMIItems;
import com.iridium126.createmanaindustry.content.recipes.HexItemDataTransfer;
import com.iridium126.createmanaindustry.content.recipes.HexItemFillingLogic;
import com.iridium126.createmanaindustry.content.fluids.MediaBatteryFluidHandler;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.common.lib.HexDataComponents;
import at.petrak.hexcasting.common.lib.HexRegistries;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.spout.SpoutBlockEntity;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Kept separate from core scenes so Hexcasting types are never loaded without Hexcasting. */
public final class HexPonderScenes {
    private HexPonderScenes() {}
    private static ItemStack scroll() {
        ItemStack stack = item("hexcasting:scroll");
        stack.set(HexDataComponents.ACTION, ResourceKey.create(HexRegistries.ACTION, ResourceLocation.parse("hexcasting:craft/battery")));
        return stack;
    }
    private static ItemStack filledBattery() {
        ItemStack incomplete = CMIItems.INCOMPLETE_MEDIA_BATTERY.asStack();
        incomplete = HexItemFillingLogic.fillIncompleteHexItem(incomplete, 1000);
        return HexItemDataTransfer.applyPressTransfer(incomplete, item("hexcasting:battery"));
    }

    public static void spells(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "hex_spells", "Writing and filling spell items", "自动写入与灌注施法物品");
        AssemblyWorkshop line = new AssemblyWorkshop(w, CMIFluids.LIQUID_MEDIA.get());
        ItemStack blank = item("hexcasting:cypher");
        ItemStack focus = item("hexcasting:focus");
        focus.set(HexDataComponents.IOTA_HOLDER_IOTA, new PatternIota(HexPattern.fromAngleString("qaq", HexDir.EAST)));
        blank.set(HexDataComponents.PIGMENT, FrozenPigment.DEFAULT.get());
        line.input(blank);
        w.explain(60, line.deployer, "A Focus in the Deployer supplies the stored pattern to write into a spell item.", "机械手可以将核心中保存的图案写入施法物品");
        ItemStack written = HexItemDataTransfer.applyDeployerIotaAppend(CMIItems.INCOMPLETE_CYPHER.asStack(), blank, focus);
        line.deploy(focus, written);
        w.explain(70, line.deployer, "Each application appends its stored Iota. The Focus is kept, so you can use it again.", "每次操作会追加核心中的 Iota，核心不会被消耗");
        line.moveTo(line.spout, written);
        ItemStack filled = HexItemFillingLogic.fillIncompleteHexItem(written, 1000);
        w.explain(70, line.spout, "Fill the unfinished item with Liquid Media. Repeated filling increases its stored media up to its limit.", "使用液态媒质可以反复灌注未完成物品，直到达到容量上限");
        line.fill(CMIFluids.LIQUID_MEDIA.get(), filled);
        line.moveTo(line.press, filled);
        ItemStack result = HexItemDataTransfer.applyPressTransfer(filled, item("hexcasting:cypher"));
        w.explain(60, line.press, "The press finishes the item while retaining its written patterns, media and pigment.", "冲压可以完成定型，并保留图案、媒质和颜料");
        line.press(result); line.output(result);
        w.text(80, p(8, 1, 3), "Trinkets and Artifacts follow the same steps. Finished Cyphers are single-use and cannot be refilled.", "饰品和造物使用相同流程，成品缀品只能使用一次，且无法再次灌注");
        w.finish();
    }

    public static void battery(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "hex_battery", "Making a Media Battery", "制作媒质之瓶");
        AssemblyWorkshop line = new AssemblyWorkshop(w, CMIFluids.LIQUID_MEDIA.get());
        line.input(new ItemStack(Items.GLASS_BOTTLE));
        ItemStack incomplete = CMIItems.INCOMPLETE_MEDIA_BATTERY.asStack();
        w.explain(80, line.deployer, "Apply the battery-crafting scroll to a Glass Bottle. Other patterns fail; the correct scroll is kept.", "机械手需要持有对应图案的远古卷轴才能加工玻璃瓶，卷轴不会被消耗");
        line.deploy(scroll(), incomplete);
        line.moveTo(line.spout, incomplete);
        ItemStack filled = HexItemFillingLogic.fillIncompleteHexItem(incomplete, 1000);
        w.explain(60, line.spout, "Fill with Liquid Media before pressing. You may repeat filling to store more media.", "冲压前可以反复灌注液态媒质，增加储存量");
        line.fill(CMIFluids.LIQUID_MEDIA.get(), filled);
        line.moveTo(line.press, filled);
        ItemStack result = HexItemDataTransfer.applyPressTransfer(filled, item("hexcasting:battery"));
        w.explain(60, line.press, "The press creates a finished Media Battery containing the media you supplied.", "冲压后得到媒质之瓶，并保留其中的媒质");
        line.press(result); line.output(result);
        w.text(60, p(8, 1, 3), "Its final capacity is set by how much media was filled before pressing.", "定型前灌入的媒质量决定成品容量");
        w.finish();
    }

    public static void batteryTransfer(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "hex_battery_transfer", "Refilling and emptying a battery", "提取与补充媒质之瓶中的媒质");
        BlockPos drain = p(3, 1, 3), depot = p(6, 1, 3), spout = depot.above(2);
        ItemStack full = filledBattery();
        ItemStack empty = full.copy();
        MediaBatteryFluidHandler handler = new MediaBatteryFluidHandler(empty);
        int amount = handler.drain(Integer.MAX_VALUE, FluidAction.EXECUTE).getAmount();
        w.place(drain, AllBlocks.ITEM_DRAIN.get()); w.display(drain, full);
        w.text(60, drain, "A finished Media Battery can exchange its stored media as Liquid Media.", "媒质之瓶中的媒质可以作为液态媒质存取");
        w.explain(60, drain, "Use an Item Drain to empty it. The bottle remains; its stored media decreases.", "分液池可以提取其中的媒质，瓶子不会被消耗");
        w.display(drain, empty); w.liquid(drain, CMIFluids.LIQUID_MEDIA.get(), Math.min(1000, amount));
        w.pipe(drain.south(), Direction.NORTH, Direction.SOUTH); w.pump(drain.south(2), Direction.SOUTH);
        w.tank(drain.south(3), CMIFluids.LIQUID_MEDIA.get(), amount);
        w.liquid(drain, CMIFluids.LIQUID_MEDIA.get(), 0);
        w.display(drain, ItemStack.EMPTY); w.depot(depot, empty);
        w.place(spout, AllBlocks.SPOUT.get());
        w.tank(spout.south(3), CMIFluids.LIQUID_MEDIA.get(), amount);
        w.pump(spout.south(2), Direction.NORTH);
        w.pipe(spout.south(), Direction.NORTH, Direction.SOUTH);
        w.liquid(spout, CMIFluids.LIQUID_MEDIA.get(), Math.min(1000, amount));
        w.explain(60, spout, "A Spout fills the same bottle again with Liquid Media.", "注液器可以向同一个瓶子重新灌入液态媒质");
        w.scene.world().modifyBlockEntity(spout, SpoutBlockEntity.class, be -> be.processingTicks = 20);
        w.idle(25);
        ItemStack refilled = empty.copy();
        new MediaBatteryFluidHandler(refilled).fill(new FluidStack(CMIFluids.LIQUID_MEDIA.get(), Math.max(1, amount)), FluidAction.EXECUTE);
        w.display(depot, refilled); w.liquid(spout, CMIFluids.LIQUID_MEDIA.get(), 0);
        w.text(60, depot, "Refilling restores its contents but does not increase the capacity set during crafting.", "再次灌注只会补充储量，不会增加容量");
        w.finish();
    }
}
