package com.iridium126.createmanaindustry.client.ponder;

import static com.iridium126.createmanaindustry.client.ponder.Workshop.*;
import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.content.kinetics.kineticatomizer.KineticAtomizerBlockEntity;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

public final class MistPonderScenes {
    static final int MEDIA = 0xB695EB, MANA = 0x58DBC4, SOUL = 0x598CFF, ROSE = 0xEF779F;
    private MistPonderScenes() {}

    static void atomizer(Workshop w, BlockPos a, Fluid fluid) {
        w.place(a, CMIBlocks.KINETIC_ATOMIZER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        connectAtomizer(w, a, fluid);
    }

    /** Add the connections without replaying the nozzle's placement animation. */
    private static void connectAtomizer(Workshop w, BlockPos a, Fluid fluid) {
        w.pipe(a.below(), Direction.UP, Direction.WEST);
        w.pump(a.below().west(), Direction.EAST);
        w.tank(a.below().west(2), fluid, 2000);
        // The pump drive above it also meshes with this upward-facing atomizer.
        // Replace it with a Y-axis drive south of the atomizer so axes cannot be confused.
        w.place(a.south(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        w.place(a.south().below(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.speed(a.south().below(), 32); w.speed(a.south(), 32); w.speed(a, -32);
        contents(w, a, fluid, 1000);
    }

    static void contents(Workshop w, BlockPos a, Fluid fluid, int amount) {
        w.scene.world().modifyBlockEntityNBT(w.util.select().position(a), KineticAtomizerBlockEntity.class, nbt -> {
            FluidTank tank = new FluidTank(1000);
            if (amount > 0) tank.setFluid(new FluidStack(fluid, amount));
            nbt.put("Tank", tank.writeToNBT(w.scene.world().getHolderLookupProvider(), new CompoundTag()));
        });
    }

    public static void atomizing(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "atomizer_setup", "Turning liquid into mist", "使用动力雾化器产生雾");
        BlockPos a = p(3, 2, 4);
        w.place(a, CMIBlocks.KINETIC_ATOMIZER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.explain(60, a, "An Atomizer turns supplied liquid into a mist field while rotating.", "动力雾化器可以将输入的流体转化为雾");
        connectAtomizer(w, a, CMIFluids.LIQUID_MEDIA.get());
        w.point(a.below(), PonderPalette.INPUT);
        w.text(70, a.below(), "Feed the back of the nozzle. An upward-facing atomizer takes liquid from below.", "流体需要从喷口背面输入，喷口朝上时，管道应连接底部");
        w.point(a.south(), PonderPalette.GREEN);
        w.text(60, a.south(), "Mesh a cogwheel with its cog. A pipe alone does not provide rotation.", "雾化器需要通过齿轮传入动力");
        w.explain(60, a, "The mist has the same fluid type as the liquid you supply.", "产生的雾与输入流体的种类相同");
        Workshop.Mist mist = w.mist(a, MEDIA, 2);
        w.liquid(a.below().west(2), CMIFluids.LIQUID_MEDIA.get(), 1000);
        w.explain(60, a, "Molten fluids cannot enter this machine. Make their mist with a vaporizing recipe instead.", "熔融流体无法输入雾化器，需要使用汽化配方");
        w.use(a.below(), new ItemStack(CMIFluids.MOLTEN_ROSE_QUARTZ.get().getBucket()));
        w.point(a.below(), PonderPalette.RED);
        w.waitForText();
        w.mistOff(mist); w.finish();
    }

    public static void range(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "atomizer_range", "Reaching the next machine", "调整雾场的范围");
        BlockPos a = p(3, 2, 4), target = p(6, 1, 4);
        w.build(() -> atomizer(w, a, CMIFluids.LIQUID_MEDIA.get()));
        Workshop.Mist mist = w.mist(a, MEDIA, 1);
        w.place(target, AllBlocks.BASIN.get());
        w.text(60, a, "Start with a slow atomizer and keep the machine you want to supply nearby.", "需要用雾的机器应放置在雾化器附近");
        w.explain(60, a, "More speed spreads the mist farther and uses liquid faster.", "提高转速可以扩大雾场，同时增加流体消耗");
        w.scene.effects().rotationSpeedIndicator(a);
        w.speed(a, -128); w.speed(a.south(), 128); w.speed(a.south().below(), 128);
        w.mistSize(mist, 3, MEDIA);
        w.point(target, PonderPalette.GREEN);
        w.text(70, target, "Mist is thinner farther from its source. Put demanding recipes closer to the atomizer.", "距离雾源越远，浓度越低，浓度要求较高的配方需要靠近雾源");
        w.explain(60, a, "An empty tank stops mist production even if the cog is still turning.", "流体耗尽后，雾化器将停止产生雾");
        contents(w, a, CMIFluids.LIQUID_MEDIA.get(), 0);
        w.mistOff(mist);
        w.explain(60, a, "No rotation means no mist either. Check both power and fluid supply.", "没有旋转动力时，雾化器也无法工作");
        contents(w, a, CMIFluids.LIQUID_MEDIA.get(), 1000);
        w.speed(a, 0); w.speed(a.south(), 0); w.speed(a.south().below(), 0);
        w.finish();
    }

    static void coolingLine(Workshop w, BlockPos c) {
        w.place(c, CMIBlocks.CONDENSER.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        w.place(c.below(), AllBlocks.ITEM_DRAIN.get());
        w.place(c.east(3).below(), AllBlocks.COPPER_CASING.get());
        w.tank(c.east(3), CMIFluids.COOLANT.get(), 3000);
        w.pump(c.east(2), Direction.WEST);
        w.pipe(c.east(), Direction.WEST, Direction.EAST);
        w.pipe(c.west(), Direction.WEST, Direction.EAST);
        w.place(c.west(2).below(), AllBlocks.COPPER_CASING.get());
        w.tank(c.west(2), CMIFluids.COOLANT.get(), 0);
    }

    public static void soul(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "soul_recovery", "Collecting the Allay's mist", "收集悦灵燃烧室产生的雾");
        BlockPos b = p(3, 1, 3), c = p(5, 2, 5);
        w.burner(b, false);
        w.explain(60, b, "A burning Allay Burner produces Liquid Soul mist around itself.", "悦灵燃烧室在燃烧时会产生液态灵魂雾");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD)); w.light(b, true);
        Workshop.Mist mist = w.mist(b, SOUL, 3);
        w.explain(70, c, "Place a Condenser inside the mist, with an Item Drain directly below it.", "冷凝管需要位于雾中，且正下方需要放置分液池");
        w.build(() -> coolingLine(w, c));
        w.text(60, c.east(2), "Pump Coolant through the condenser. Ordinary water does not work.", "冷却液需要流经冷凝管，水无法代替冷却液");
        w.explain(70, c.below(), "Cooling consumes Coolant and mist. Liquid Soul collects in the drain below.", "冷凝会消耗冷却液和雾，产物将进入下方的分液池");
        w.liquid(c.below(), CMIFluids.LIQUID_SOUL.get(), 500);
        w.liquid(c.east(3), CMIFluids.COOLANT.get(), 2500);
        w.pipe(c.below().north(), Direction.NORTH, Direction.SOUTH);
        w.pump(c.below().north(2), Direction.NORTH);
        w.tank(c.below().north(3), CMIFluids.LIQUID_SOUL.get(), 500);
        w.liquid(c.below(), CMIFluids.LIQUID_SOUL.get(), 0);
        w.text(60, c.below().north(3), "Use a separate pipe to move the collected soul liquid into storage.", "产物需要通过独立管道输入流体储罐");
        w.waitForText();
        w.mistOff(mist); w.finish();
    }

    public static void condenser(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "condenser_setup", "A working cooling circuit", "配置冷凝管");
        BlockPos c = p(5, 2, 5);
        w.build(() -> coolingLine(w, c));
        w.text(60, c, "Run Coolant along the condenser's pipe axis, with somewhere for the remaining coolant to go.", "冷却液需要沿冷凝管的轴向流动，并保留出口");
        BlockPos b = p(3, 1, 3);
        w.burner(b, true);
        Workshop.Mist mist = w.mist(b, SOUL, 3);
        w.liquid(c.below(), CMIFluids.LIQUID_SOUL.get(), 500);
        w.text(70, c.below(), "The product drops into an Item Drain below; it does not leave through the coolant pipe.", "产物会进入下方的分液池，不会进入冷却液管道");
        w.explain(60, c.below(), "Without the drain directly underneath, nothing can be collected.", "正下方没有分液池时，冷凝管无法收集产物");
        w.remove(c.below());
        w.point(c.below(), PonderPalette.RED);
        w.explain(60, c.below(), "A full drain, or one holding a different liquid, also stops collection.", "分液池已满或装有其他流体时，也会停止收集");
        w.place(c.below(), AllBlocks.ITEM_DRAIN.get());
        w.liquid(c.below(), CMIFluids.LIQUID_SOUL.get(), 1500);
        w.waitForText();
        w.pipe(c.below().north(), Direction.NORTH, Direction.SOUTH);
        w.pump(c.below().north(2), Direction.NORTH);
        w.tank(c.below().north(3), CMIFluids.LIQUID_SOUL.get(), 1500);
        w.liquid(c.below(), CMIFluids.LIQUID_SOUL.get(), 0);
        w.text(70, c.below(), "Pump out the product to keep working. Oxidized and waxed condensers work the same way.", "持续抽出产物可以保持运行，氧化和涂蜡的冷凝管用法相同");
        w.waitForText();
        w.mistOff(mist); w.finish();
    }

    public static void cogwheel(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "mana_cogwheel", "Mist-powered rotation and reversal", "使用雾驱动魔力齿轮");
        BlockPos a = p(3, 2, 4), cog = p(6, 1, 4), mill = p(7, 1, 4);
        w.build(() -> atomizer(w, a, CMIFluids.LIQUID_MANA.get()));
        Workshop.Mist mist = w.mist(a, MANA, 3);
        w.place(cog, CMIBlocks.MANA_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        w.explain(60, cog, "A Mana Cogwheel generates rotation inside Liquid Mana mist.", "魔力齿轮可以在液态魔力雾中产生旋转动力");
        w.speed(cog, 256);
        w.scene.effects().rotationDirectionIndicator(cog);
        w.explain(70, mill, "This rotation can drive Create machines, such as this Millstone. Keep the total load within the cogwheel's stress capacity.", "这些动力可用于驱动石磨等机器，负载不能超过应力容量");
        w.place(mill, AllBlocks.MILLSTONE.get()); w.speed(mill, -256);
        w.use(mill, new ItemStack(Items.WHEAT));
        w.scene.world().modifyBlockEntity(mill,
                com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity.class,
                be -> { be.inputInv.setStackInSlot(0, new ItemStack(Items.WHEAT)); be.timer = 200; });
        w.idle(40);
        w.depot(p(7, 1, 2), AllItems.WHEAT_FLOUR.asStack());
        w.scene.world().modifyBlockEntity(mill,
                com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity.class, be -> {
                    be.inputInv.setStackInSlot(0, ItemStack.EMPTY);
                    for (int i = 0; i < be.outputInv.getSlots(); i++) be.outputInv.setStackInSlot(i, ItemStack.EMPTY);
                });
        w.explain(60, cog, "Liquid Media mist reverses that direction. Plan the connected machines accordingly.", "换用液态媒质雾，可以使齿轮反向转动");
        contents(w, a, CMIFluids.LIQUID_MEDIA.get(), 1000);
        w.liquid(a.below().west(2), CMIFluids.LIQUID_MEDIA.get(), 2000);
        w.mistSize(mist, 3, MEDIA); w.speed(cog, -256); w.speed(mill, 256);
        w.scene.effects().rotationDirectionIndicator(cog);
        w.explain(60, cog, "Andesite and Brass Casings can enclose a Mana Cogwheel without changing its mist-powered operation.", "安山机壳或黄铜机壳可以用于魔力齿轮装壳");
        w.use(cog, AllBlocks.ANDESITE_CASING.asStack());
        w.place(cog, CMIBlocks.ANDESITE_ENCASED_MANA_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        w.speed(cog, -256);
        w.explain(60, cog, "Sneak and right-click with a wrench to remove either casing.", "潜行时手持扳手右击，可以拆下机壳");
        w.scene.overlay().showControls(util.vector().topOf(cog), net.createmod.catnip.math.Pointing.DOWN, 45)
                .rightClick().whileSneaking().withItem(AllItems.WRENCH.asStack());
        w.idle(25);
        w.place(cog, CMIBlocks.MANA_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        w.use(cog, AllBlocks.BRASS_CASING.asStack());
        w.place(cog, CMIBlocks.BRASS_ENCASED_MANA_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        w.speed(cog, -256);
        w.explain(70, cog, "The mist must reach the cogwheel, even when encased. Losing the mist stops its generated rotation.", "装壳后仍需要雾覆盖齿轮，失去雾便会停止产生动力");
        w.mistOff(mist); w.speed(cog, 0); w.speed(mill, 0);
        w.finish();
    }
}
