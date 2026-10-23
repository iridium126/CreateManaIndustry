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
        Workshop w = new Workshop(builder, util, "trickster_knots", "An emerald knot production line", "绿宝石晶结生产线");
        AssemblyWorkshop line = new AssemblyWorkshop(w, CMIFluids.LIQUID_MANA.get());
        line.input(new ItemStack(Items.EMERALD));
        w.text(line.deployer.below(2), "Begin with an Emerald on the belt and Glass in the Deployer's hand.", "把绿宝石放上传送带，让机械手拿着玻璃。");
        ItemStack unfinished = CMIItems.INCOMPLETE_EMERALD_KNOT.asStack();
        line.deploy(new ItemStack(Items.GLASS), unfinished);
        w.text(line.deployer, "The Deployer makes an incomplete Emerald Knot, ready for mana infusion.", "机械手先制成未完成的绿宝石晶结，接下来需要灌注魔力。");
        line.moveTo(line.spout, unfinished);
        ItemStack lastFill = unfinished.copy();
        lastFill.set(CMIComponents.FILLED_MANA.get(), Math.max(0, KnotFillingLogic.getCreationCost(lastFill) - ServerConfig.manaPerBucket));
        ItemStack complete = KnotFillingLogic.fillIncompleteKnot(lastFill);
        line.fill(CMIFluids.LIQUID_MANA.get(), complete);
        w.text(line.spout, "Repeat Liquid Mana filling until its creation cost is met. Filling itself completes the knot.", "反复灌注液态魔力，达到所需总量后就成为完整晶结，不需要冲压定型。");
        w.point(line.press, PonderPalette.RED);
        w.text(line.press, "Stop here for an intact knot. The press is an optional next step: it cracks the knot.", "想要完整晶结，就在这里取走。下一步冲压是可选步骤，会把晶结压裂。");
        line.moveTo(line.press, complete); line.press(item("trickster:cracked_emerald_knot"));
        line.output(item("trickster:cracked_emerald_knot"));
        w.text(p(8, 1, 3), "The output is a Cracked Emerald Knot. Other knot types have their own ingredients and mana costs.", "这里产出的是裂纹绿宝石晶结。其他晶结的原料和魔力需求各不相同。");
        w.finish();
    }

    public static void charging(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "trickster_charging", "Charging and delivering mana knots", "晶结充能与自动搬运");
        BlockPos generator = p(4, 2, 3), array = generator.above();
        w.place(generator, CMIBlocks.KINETIC_MANA_GENERATOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.place(generator.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.speed(generator.below(), 32); w.speed(generator, 32);
        w.text(generator, "The Kinetic Mana Generator consumes rotational stress to charge nearby knot storage.", "动力注魔台消耗旋转应力，为相邻容器里的晶结补充魔力。");
        w.place(array, block("trickster:charging_array"));
        w.text(array, "Put a Charging Array directly on its output face. Here, the output points upwards.", "把充能阵列放在注魔台输出面紧邻的一格，这里输出面朝上。");
        BlockPos depot = p(3, 2, 2), arm = p(5, 2, 5);
        w.depot(depot, item("trickster:emerald_knot"));
        w.arm(arm, depot, array, "createmanaindustry:trickster_knot");
        w.text(arm, "Mechanical Arms can deliver knots to Charging Arrays and Spell Constructs.", "机械臂可以把晶结送入充能阵列和法术组构台。");
        w.armTransfer(arm, depot, item("trickster:emerald_knot"));
        slot(w, array, 0, item("trickster:emerald_knot"));
        w.scene.world().modifyBlockEntity(array, BlockEntity.class, be -> {
            if (be instanceof Container inventory)
                TricksterManaAccess.refillMana(inventory.getItem(0), be.getLevel(), 200);
        });
        w.text(array, "Arrays expose their knot slots; Spell Constructs expose only the first slot and can also receive power on this face.", "充能阵列开放各个晶结槽；法术组构台只开放首槽，也能在这个输出面接收供能。");
        w.use(generator, com.simibubi.create.AllItems.WRENCH.asStack());
        w.scene.world().modifyBlockEntityNBT(util.select().position(generator), KineticManaGeneratorBlockEntity.class,
                nbt -> nbt.putInt("ScrollValue", 16));
        w.text(generator, "Adjust the scroll control to spend more stress per RPM. Make sure your power source can carry the load.", "调节滚动控件，可以提高每转速消耗的应力；同时确保动力源能承担负载。");
        w.speed(generator, 0); w.speed(generator.below(), 0);
        w.text(generator, "Charging stops without rotation or when the network is overstressed.", "没有转动，或动力网络过载时，注魔就会停止。");
        w.finish();
    }

    public static void display(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "trickster_display", "Inventory counts as spell arguments", "把库存数量传给法术");
        BlockPos chest = p(2, 1, 3), link = chest.above(), target = p(6, 1, 3);
        w.place(chest, Blocks.CHEST); slot(w, chest, 0, new ItemStack(Items.AMETHYST_SHARD, 32));
        w.place(target, block("trickster:spell_construct"));
        w.text(chest, "A Display Link can send an inventory's item count to a Spell Construct.", "显示链接可以把容器的物品数量发送给法术组构台。");
        w.use(target, AllBlocks.DISPLAY_LINK.asStack()); w.point(target, PonderPalette.OUTPUT);
        w.place(link, AllBlocks.DISPLAY_LINK.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.scene.world().modifyBlockEntityNBT(util.select().position(link), DisplayLinkBlockEntity.class,
                nbt -> nbt.put("TargetOffset", NbtUtils.writeBlockPos(target.subtract(link))));
        w.text(link, "Link the inventory to the construct, then choose the item-count source and destination argument slot.", "把容器链接到组构台，选择物品数量作为来源，并指定目标参数槽。");
        w.scene.world().flashDisplayLink(link);
        w.scene.world().modifyBlockEntity(target, BlockEntity.class,
                be -> SpellConstructDisplayArguments.storeArgument(be, 0, List.of(Component.literal("32"))));
        w.scene.overlay().showControls(util.vector().topOf(target), net.createmod.catnip.math.Pointing.DOWN, 80)
                .withItem(new ItemStack(Items.AMETHYST_SHARD, 32));
        w.text(target, "The first argument now receives 32. Your spell can read this argument to react to the stock level.", "第一个参数现在收到 32，法术可以读取这个参数，根据库存作出判断。");
        slot(w, chest, 0, new ItemStack(Items.AMETHYST_SHARD, 16));
        w.scene.world().flashDisplayLink(link);
        w.scene.world().modifyBlockEntity(target, BlockEntity.class,
                be -> SpellConstructDisplayArguments.storeArgument(be, 0, List.of(Component.literal("16"))));
        w.text(target, "Updated counts replace the argument. On a Modular Spell Construct, also choose the intended executor.", "库存变化后，参数会随之更新。使用模块化法术组构台时，还要选对执行器。");
        w.finish();
    }
}
