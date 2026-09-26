package com.iridium126.createmanaindustry.ponder;

import static com.iridium126.createmanaindustry.ponder.Workshop.*;
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
        w.text(line.deployer, "Give the Deployer a Focus holding the pattern to write, and place a spell item on the belt.", "让机械手拿着保存了待写入图案的核心，再把施法物品放上传送带。");
        ItemStack written = HexItemDataTransfer.applyDeployerIotaAppend(CMIItems.INCOMPLETE_CYPHER.asStack(), blank, focus);
        line.deploy(focus, written);
        w.text(line.deployer, "Each application appends its stored Iota. The Focus is kept, so you can use it again.", "每次操作会追加核心中的 Iota，核心本身保留，可以重复使用。");
        line.moveTo(line.spout, written);
        ItemStack filled = HexItemFillingLogic.fillIncompleteHexItem(written, 1000);
        line.fill(CMIFluids.LIQUID_MEDIA.get(), filled);
        w.text(line.spout, "Fill the unfinished item with Liquid Media. Repeated filling increases its stored media up to its limit.", "向未完成物品灌注液态媒质，可以重复灌注，直到达到容量上限。");
        line.moveTo(line.press, filled);
        ItemStack result = HexItemDataTransfer.applyPressTransfer(filled, item("hexcasting:cypher"));
        line.press(result); line.output(result);
        w.text(p(8, 1, 3), "The press finishes the item while retaining its written patterns, media and pigment.", "冲压机完成定型，并保留已经写入的图案、媒质和颜料。");
        w.text(p(8, 1, 3), "Trinkets and Artifacts follow the same steps. Finished Cyphers are single-use and cannot be refilled.", "饰品和造物采用相同流程。成品缀品是一次性的，不能再次灌注补充。");
        w.finish();
    }

    public static void battery(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "hex_battery", "Making a Media Battery", "制造媒质之瓶");
        AssemblyWorkshop line = new AssemblyWorkshop(w, CMIFluids.LIQUID_MEDIA.get());
        line.input(new ItemStack(Items.GLASS_BOTTLE));
        w.text(line.deployer.below(2), "Start with a Glass Bottle and a scroll for the battery-crafting pattern.", "准备玻璃瓶，以及保存了制作媒质之瓶图案的远古卷轴。");
        ItemStack incomplete = CMIItems.INCOMPLETE_MEDIA_BATTERY.asStack();
        line.deploy(scroll(), incomplete);
        w.text(line.deployer, "The scroll must contain the correct crafting pattern. Other scrolls will not work, and the scroll is not consumed.", "必须使用对应制作图案的卷轴，其他卷轴无效。加工不会消耗卷轴。");
        line.moveTo(line.spout, incomplete);
        ItemStack filled = HexItemFillingLogic.fillIncompleteHexItem(incomplete, 1000);
        line.fill(CMIFluids.LIQUID_MEDIA.get(), filled);
        w.text(line.spout, "Fill with Liquid Media before pressing. You may repeat filling to store more media.", "先灌入液态媒质，再进行冲压。可以重复灌注来储存更多媒质。");
        line.moveTo(line.press, filled);
        ItemStack result = HexItemDataTransfer.applyPressTransfer(filled, item("hexcasting:battery"));
        line.press(result); line.output(result);
        w.text(p(8, 1, 3), "The press creates a finished Media Battery containing the media you supplied.", "冲压后得到成品媒质之瓶，里面保留灌入的媒质。");
        w.text(p(8, 1, 3), "Its final capacity is set by how much media was filled before pressing.", "成品的容量由冲压定型前实际灌入的媒质量决定。");
        w.finish();
    }

    public static void batteryTransfer(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "hex_battery_transfer", "Refilling and emptying a battery", "媒质之瓶的自动存取");
        BlockPos drain = p(3, 1, 3), depot = p(6, 1, 3), spout = depot.above(2);
        ItemStack full = filledBattery();
        ItemStack empty = full.copy();
        MediaBatteryFluidHandler handler = new MediaBatteryFluidHandler(empty);
        int amount = handler.drain(Integer.MAX_VALUE, FluidAction.EXECUTE).getAmount();
        w.place(drain, AllBlocks.ITEM_DRAIN.get()); w.display(drain, full);
        w.text(drain, "A finished Media Battery can exchange its stored media as Liquid Media.", "成品媒质之瓶可以把内部媒质转换成液态媒质来存取。");
        w.display(drain, empty); w.liquid(drain, CMIFluids.LIQUID_MEDIA.get(), Math.min(1000, amount));
        w.text(drain, "Use an Item Drain to empty it. The bottle remains; its stored media decreases.", "用动力排泄口抽出媒质。瓶子会保留，只减少内部储量。");
        w.pipe(drain.south(), Direction.NORTH, Direction.SOUTH); w.pump(drain.south(2), Direction.SOUTH);
        w.tank(drain.south(3), CMIFluids.LIQUID_MEDIA.get(), amount);
        w.liquid(drain, CMIFluids.LIQUID_MEDIA.get(), 0);
        w.text(drain.south(3), "Pump the recovered Liquid Media into a tank for other machines.", "把取出的液态媒质泵入储罐，供其他机器使用。");
        w.display(drain, ItemStack.EMPTY); w.depot(depot, empty);
        w.place(spout, AllBlocks.SPOUT.get());
        w.tank(spout.south(3), CMIFluids.LIQUID_MEDIA.get(), amount);
        w.pump(spout.south(2), Direction.NORTH);
        w.pipe(spout.south(), Direction.NORTH, Direction.SOUTH);
        w.liquid(spout, CMIFluids.LIQUID_MEDIA.get(), Math.min(1000, amount));
        w.scene.world().modifyBlockEntity(spout, SpoutBlockEntity.class, be -> be.processingTicks = 20);
        w.scene.idle(25);
        ItemStack refilled = empty.copy();
        new MediaBatteryFluidHandler(refilled).fill(new FluidStack(CMIFluids.LIQUID_MEDIA.get(), Math.max(1, amount)), FluidAction.EXECUTE);
        w.display(depot, refilled); w.liquid(spout, CMIFluids.LIQUID_MEDIA.get(), 0);
        w.text(spout, "A Spout fills the same bottle again with Liquid Media.", "注液器可以把液态媒质重新灌进同一个瓶子。");
        w.text(depot, "Refilling restores its contents but does not increase the capacity set during crafting.", "再次灌注只会恢复储量，不会提高制作时确定的容量。");
        w.finish();
    }
}
