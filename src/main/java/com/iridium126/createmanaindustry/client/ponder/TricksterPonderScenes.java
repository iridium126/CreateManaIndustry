package com.iridium126.createmanaindustry.client.ponder;

import static com.iridium126.createmanaindustry.client.ponder.Workshop.*;
import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.CMIItems;
import com.iridium126.createmanaindustry.CMIComponents;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.iridium126.createmanaindustry.content.recipes.KnotFillingLogic;
import com.iridium126.createmanaindustry.compat.trickster.mana.TricksterManaAccess;
import com.iridium126.createmanaindustry.content.display.SpellConstructDisplayArguments;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorBlockEntity;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.List;

/** Only registered after Trickster and the demonstrated registry entries have been checked. */
public final class TricksterPonderScenes {
    private TricksterPonderScenes() {}
    static void slot(Workshop w, BlockPos pos, int slot, ItemStack stack) {
        w.scene.world().modifyBlockEntity(pos, BlockEntity.class, be -> {
            if (be instanceof Container inventory) inventory.setItem(slot, stack.copy());
        });
    }

    public static void knots(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "trickster_knots", "An emerald knot production line", "自动制作绿宝石晶结");
        AssemblyWorkshop line = new AssemblyWorkshop(w, CMIFluids.LIQUID_MANA.get());
        line.input(new ItemStack(Items.EMERALD));
        w.explain(60, line.deployer.below(2), "Begin with an Emerald on the belt and Glass in the Deployer's hand.", "机械手需要持有玻璃，并对绿宝石加工");
        ItemStack unfinished = CMIItems.INCOMPLETE_EMERALD_KNOT.asStack();
        line.deploy(new ItemStack(Items.GLASS), unfinished);
        w.text(60, line.deployer, "The Deployer makes an incomplete Emerald Knot, ready for mana infusion.", "加工后得到未完成的绿宝石晶结");
        line.moveTo(line.spout, unfinished);
        ItemStack lastFill = unfinished.copy();
        lastFill.set(CMIComponents.FILLED_MANA.get(), Math.max(0, KnotFillingLogic.getCreationCost(lastFill) - ServerConfig.manaPerBucket));
        ItemStack complete = KnotFillingLogic.fillIncompleteKnot(lastFill);
        w.explain(70, line.spout, "Repeat Liquid Mana filling until its creation cost is met. Filling itself completes the knot.", "反复灌注液态魔力，达到所需总量后便可完成晶结");
        line.fill(CMIFluids.LIQUID_MANA.get(), complete);
        w.point(line.press, PonderPalette.RED);
        w.explain(70, line.press, "Stop here for an intact knot. The press is an optional next step: it cracks the knot.", "冲压会使完整晶结开裂，如需完整晶结，应在冲压前取出");
        line.moveTo(line.press, complete); line.press(item("trickster:cracked_emerald_knot"));
        line.output(item("trickster:cracked_emerald_knot"));
        w.text(80, p(8, 1, 3), "The output is a Cracked Emerald Knot. Other knot types have their own ingredients and mana costs.", "冲压后得到裂纹绿宝石晶结，其他晶结的原料和魔力用量不同");
        w.finish();
    }

    public static void charging(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "trickster_charging", "Charging and delivering mana knots", "为晶结充能与自动搬运晶结");
        BlockPos generator = p(4, 2, 3), array = generator.above();
        w.place(generator, CMIBlocks.KINETIC_MANA_GENERATOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.place(generator.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.speed(generator.below(), 32); w.speed(generator, 32);
        w.text(60, generator, "The Kinetic Mana Generator consumes rotational stress to charge nearby knot storage.", "动力注魔台可以消耗旋转应力，为晶结充能");
        w.place(array, block("trickster:charging_array"));
        w.text(60, array, "Put a Charging Array directly on its output face. Here, the output points upwards.", "充能阵列需要紧贴注魔台的输出面");
        BlockPos depot = p(3, 2, 2), arm = p(5, 2, 5);
        w.depot(depot, item("trickster:emerald_knot"));
        w.arm(arm, depot, array, "createmanaindustry:trickster_knot");
        w.explain(60, arm, "Mechanical Arms can deliver knots to Charging Arrays and Spell Constructs.", "动力臂可以向充能阵列和法术组构台输入晶结");
        w.armTransfer(arm, depot, item("trickster:emerald_knot"));
        slot(w, array, 0, item("trickster:emerald_knot"));
        w.scene.world().modifyBlockEntity(array, BlockEntity.class, be -> {
            if (be instanceof Container inventory)
                TricksterManaAccess.refillMana(inventory.getItem(0), be.getLevel(), 200);
        });
        w.text(80, array, "Arrays expose their knot slots; Spell Constructs expose only the first slot and can also receive power on this face.", "充能阵列开放所有晶结槽，法术组构台仅开放首槽，也可从此面接收供能");
        w.explain(80, generator, "Adjust the scroll control to spend more stress per RPM. Make sure your power source can carry the load.", "调节数值面板可以提高单位转速的应力消耗，动力源需提供足够容量");
        w.use(generator, com.simibubi.create.AllItems.WRENCH.asStack());
        w.scene.world().modifyBlockEntityNBT(util.select().position(generator), KineticManaGeneratorBlockEntity.class,
                nbt -> nbt.putInt("ScrollValue", 16));
        w.explain(60, generator, "Charging stops without rotation or when the network is overstressed.", "停止转动或应力过载时，注魔台将停止充能");
        w.speed(generator, 0); w.speed(generator.below(), 0);
        w.finish();
    }

    public static void display(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "trickster_display", "Inventory counts as spell arguments", "使用显示链接器传递法术参数");
        BlockPos chest = p(2, 1, 3), link = chest.above(), target = p(6, 1, 3);
        w.place(chest, Blocks.CHEST); slot(w, chest, 0, new ItemStack(Items.AMETHYST_SHARD, 32));
        w.place(target, block("trickster:spell_construct"));
        w.text(70, chest, "A Display Link can send an inventory's item count to a Spell Construct.", "显示链接器可以将容器中的物品数量传给法术组构台");
        w.explain(70, link, "Link the inventory to the construct, then choose the item-count source and destination argument slot.", "选择物品数量作为信息源，并指定接收参数的槽位");
        w.use(target, AllBlocks.DISPLAY_LINK.asStack()); w.point(target, PonderPalette.OUTPUT);
        w.place(link, AllBlocks.DISPLAY_LINK.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.scene.world().modifyBlockEntityNBT(util.select().position(link), DisplayLinkBlockEntity.class,
                nbt -> nbt.put("TargetOffset", NbtUtils.writeBlockPos(target.subtract(link))));
        w.explain(70, target, "The first argument now receives 32. Your spell can read this argument to react to the stock level.", "第一个参数接收数量 32，法术可以读取此参数");
        w.scene.world().flashDisplayLink(link);
        w.scene.world().modifyBlockEntity(target, BlockEntity.class,
                be -> SpellConstructDisplayArguments.storeArgument(be, 0, List.of(Component.literal("32"))));
        w.scene.overlay().showControls(util.vector().topOf(target), net.createmod.catnip.math.Pointing.DOWN, 80)
                .withItem(new ItemStack(Items.AMETHYST_SHARD, 32));
        w.waitForText();
        w.explain(70, target, "Updated counts replace the argument. On a Modular Spell Construct, also choose the intended executor.", "库存变化会更新参数，模块化法术组构台还需要指定执行器");
        slot(w, chest, 0, new ItemStack(Items.AMETHYST_SHARD, 16));
        w.scene.world().flashDisplayLink(link);
        w.scene.world().modifyBlockEntity(target, BlockEntity.class,
                be -> SpellConstructDisplayArguments.storeArgument(be, 0, List.of(Component.literal("16"))));
        w.finish();
    }
}
