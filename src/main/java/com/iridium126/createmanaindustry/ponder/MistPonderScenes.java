package com.iridium126.createmanaindustry.ponder;

import static com.iridium126.createmanaindustry.ponder.Workshop.*;
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
        Workshop w = new Workshop(builder, util, "atomizer_setup", "Turning liquid into mist", "把液体变成雾");
        BlockPos a = p(5, 2, 3);
        w.place(a, CMIBlocks.KINETIC_ATOMIZER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.text(a, "An Atomizer turns supplied liquid into a mist field while rotating.", "雾化器接入液体和旋转动力后，就会产生雾场。");
        atomizer(w, a, CMIFluids.LIQUID_MEDIA.get());
        w.point(a.below(), PonderPalette.INPUT);
        w.text(a.below(), "Feed the back of the nozzle. An upward-facing atomizer takes liquid from below.", "液体从喷口背面进入。喷口朝上时，就从下方接管。");
        w.point(a.south(), PonderPalette.GREEN);
        w.text(a.south(), "Mesh a small cogwheel with its cog. A pipe alone does not provide rotation.", "用小齿轮与雾化器啮合传动，只有管道还不能运转。");
        Workshop.Mist mist = w.mist(a, MEDIA, 2);
        w.liquid(a.below().west(2), CMIFluids.LIQUID_MEDIA.get(), 1000);
        w.text(a, "The mist has the same fluid type as the liquid you supply.", "输入液态媒质，就得到媒质雾；雾的种类由输入液体决定。");
        w.use(a.below(), new ItemStack(CMIFluids.MOLTEN_ROSE_QUARTZ.get().getBucket()));
        w.point(a.below(), PonderPalette.RED);
        w.text(a, "Molten fluids cannot enter this machine. Make their mist with a vaporizing recipe instead.", "熔融流体不能送入雾化器，需要用汽化配方产生对应的雾。");
        w.mistOff(mist); w.finish();
    }

    public static void range(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "atomizer_range", "Reaching the next machine", "让雾覆盖目标机器");
        BlockPos a = p(5, 2, 3), target = p(7, 2, 4);
        atomizer(w, a, CMIFluids.LIQUID_MEDIA.get());
        Workshop.Mist mist = w.mist(a, MEDIA, 1);
        w.place(target, AllBlocks.BASIN.get());
        w.text(a, "Start with a slow atomizer and keep the machine you want to supply nearby.", "先让雾化器低速运转，把需要雾的机器放在附近。");
        w.scene.effects().rotationSpeedIndicator(a);
        w.speed(a, -128); w.speed(a.south(), 128); w.speed(a.south().below(), 128);
        w.mistSize(mist, 3, MEDIA);
        w.text(a, "More speed spreads the mist farther and uses liquid faster.", "提高转速，雾会扩散得更远，液体也消耗得更快。");
        w.point(target, PonderPalette.GREEN);
        w.text(target, "Mist is thinner farther from its source. Put demanding recipes closer to the atomizer.", "距离雾源越远，雾越稀薄。要求较高的配方要靠近雾源。");
        contents(w, a, CMIFluids.LIQUID_MEDIA.get(), 0);
        w.mistOff(mist);
        w.text(a, "An empty tank stops mist production even if the cog is still turning.", "液体耗尽后，即使齿轮还在转，也无法继续产雾。");
        contents(w, a, CMIFluids.LIQUID_MEDIA.get(), 1000);
        w.speed(a, 0); w.speed(a.south(), 0); w.speed(a.south().below(), 0);
        w.text(a, "No rotation means no mist either. Check both power and fluid supply.", "停止转动也会停止产雾。排查时同时检查动力和供液。");
        w.finish();
    }

    static void coolingLine(Workshop w, BlockPos c) {
        w.place(c, CMIBlocks.CONDENSER.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        w.place(c.below(), AllBlocks.ITEM_DRAIN.get());
        w.tank(c.west(3), CMIFluids.COOLANT.get(), 3000);
        w.pump(c.west(2), Direction.EAST);
        w.pipe(c.west(), Direction.WEST, Direction.EAST);
        w.pipe(c.east(), Direction.WEST, Direction.EAST);
        w.tank(c.east(2), CMIFluids.COOLANT.get(), 0);
    }

    public static void soul(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "soul_recovery", "Collecting the Allay's mist", "收集悦灵产生的灵魂雾");
        BlockPos b = p(5, 1, 5), c = p(4, 2, 3);
        w.burner(b, false);
        w.use(b, new ItemStack(Items.AMETHYST_SHARD)); w.light(b, true);
        Workshop.Mist mist = w.mist(b, SOUL, 3);
        w.text(b, "A burning Allay Burner produces Liquid Soul mist around itself.", "悦灵燃烧室燃烧时，会在周围产生液态灵魂雾。");
        coolingLine(w, c);
        w.text(c, "Place a Condenser inside the mist, with an Item Drain directly below it.", "在雾中放置冷凝管，并在它正下方放一个动力排泄口。");
        w.text(c.west(2), "Pump Coolant through the condenser. Ordinary water does not work.", "让冷却液流经冷凝管。普通的水不能代替冷却液。");
        w.liquid(c.below(), CMIFluids.LIQUID_SOUL.get(), 500);
        w.liquid(c.west(3), CMIFluids.COOLANT.get(), 2500);
        w.text(c.below(), "Cooling consumes Coolant and mist. Liquid Soul collects in the drain below.", "冷凝会消耗冷却液和雾，液态灵魂收集在下方的排泄口中。");
        w.pipe(c.below().south(), Direction.NORTH, Direction.SOUTH);
        w.pump(c.below().south(2), Direction.SOUTH);
        w.tank(c.below().south(3), CMIFluids.LIQUID_SOUL.get(), 500);
        w.liquid(c.below(), CMIFluids.LIQUID_SOUL.get(), 0);
        w.text(c.below().south(3), "Use a separate pipe to move the collected soul liquid into storage.", "另外接一条管路，把收集到的液态灵魂送进储罐。");
        w.mistOff(mist); w.finish();
    }

    public static void condenser(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "condenser_setup", "A working cooling circuit", "搭建冷凝回路");
        BlockPos c = p(4, 2, 3);
        coolingLine(w, c);
        w.text(c, "Run Coolant along the condenser's pipe axis, with somewhere for the remaining coolant to go.", "沿冷凝管的轴向输送冷却液，并为剩余冷却液连接出口。");
        w.burner(p(5, 1, 5), true);
        Workshop.Mist mist = w.mist(p(5, 1, 5), SOUL, 3);
        w.liquid(c.below(), CMIFluids.LIQUID_SOUL.get(), 500);
        w.text(c.below(), "The product drops into an Item Drain below; it does not leave through the coolant pipe.", "产物进入正下方的动力排泄口，不会从冷却液管路流出。");
        w.remove(c.below());
        w.point(c.below(), PonderPalette.RED);
        w.text(c.below(), "Without the drain directly underneath, nothing can be collected.", "正下方没有动力排泄口，就无法收集产物。");
        w.place(c.below(), AllBlocks.ITEM_DRAIN.get());
        w.liquid(c.below(), CMIFluids.LIQUID_SOUL.get(), 1500);
        w.text(c.below(), "A full drain, or one holding a different liquid, also stops collection.", "排泄口已满，或里面装着另一种液体，也会停止收集。");
        w.pipe(c.below().south(), Direction.NORTH, Direction.SOUTH);
        w.pump(c.below().south(2), Direction.SOUTH);
        w.tank(c.below().south(3), CMIFluids.LIQUID_SOUL.get(), 1500);
        w.liquid(c.below(), CMIFluids.LIQUID_SOUL.get(), 0);
        w.text(c.below(), "Pump out the product to keep working. Oxidized and waxed condensers work the same way.", "及时抽走产物，就能继续工作。氧化或涂蜡的冷凝管用法相同。");
        w.mistOff(mist); w.finish();
    }

    public static void cogwheel(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "mana_cogwheel", "Mist-powered milling", "用雾驱动磨石");
        BlockPos a = p(5, 2, 3), cog = p(6, 2, 4), mill = p(7, 2, 4);
        atomizer(w, a, CMIFluids.LIQUID_MANA.get());
        Workshop.Mist mist = w.mist(a, MANA, 3);
        w.place(cog, CMIBlocks.MANA_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        w.text(cog, "A Mana Cogwheel generates rotation inside Liquid Mana mist.", "魔力齿轮处于液态魔力雾中时，就能输出旋转动力。");
        w.speed(cog, 256);
        w.scene.effects().rotationDirectionIndicator(cog);
        w.text(cog, "The mist must reach the cogwheel itself. No mist means no generated power.", "雾必须覆盖齿轮本身，离开雾场就无法继续产生动力。");
        w.place(mill, AllBlocks.MILLSTONE.get()); w.speed(mill, -256);
        w.use(mill, new ItemStack(Items.WHEAT));
        w.scene.world().modifyBlockEntity(mill,
                com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity.class,
                be -> { be.inputInv.setStackInSlot(0, new ItemStack(Items.WHEAT)); be.timer = 200; });
        w.text(mill, "Mesh the cogwheel with a Millstone and add wheat for a simple flour mill.", "让齿轮与磨石啮合，放入小麦，就是一台简单的面粉机。");
        w.depot(p(7, 1, 2), AllItems.WHEAT_FLOUR.asStack());
        w.scene.world().modifyBlockEntity(mill,
                com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity.class, be -> {
                    be.inputInv.setStackInSlot(0, ItemStack.EMPTY);
                    for (int i = 0; i < be.outputInv.getSlots(); i++) be.outputInv.setStackInSlot(i, ItemStack.EMPTY);
                });
        w.text(p(7, 1, 2), "The Millstone produces wheat flour. The same rotation can drive other Create machines.", "磨石产出小麦粉，这股动力也可以传给其他机械动力机器。");
        w.mistOff(mist); w.speed(cog, 0); w.speed(mill, 0);
        w.text(cog, "Keep supplying the mist source, and stay within the cogwheel's stress capacity.", "保持雾源供液，并注意机器负载不要超过齿轮的应力容量。");
        w.finish();
    }

    public static void reversal(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "mana_reversal", "Changing direction and adding a casing", "反转与包覆机壳");
        BlockPos a = p(5, 2, 3), cog = p(6, 2, 4);
        atomizer(w, a, CMIFluids.LIQUID_MANA.get());
        w.place(cog, CMIBlocks.MANA_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        Workshop.Mist mist = w.mist(a, MANA, 3); w.speed(cog, 256);
        w.scene.effects().rotationDirectionIndicator(cog);
        w.text(cog, "Liquid Mana mist turns a Mana Cogwheel in one direction.", "液态魔力雾会让魔力齿轮朝一个方向旋转。");
        contents(w, a, CMIFluids.LIQUID_MEDIA.get(), 1000);
        w.liquid(a.below().west(2), CMIFluids.LIQUID_MEDIA.get(), 2000);
        w.mistSize(mist, 3, MEDIA); w.speed(cog, -256);
        w.scene.effects().rotationDirectionIndicator(cog);
        w.text(cog, "Liquid Media mist reverses that direction. Plan the connected machines accordingly.", "换成液态媒质雾，转向就会反过来。连接机器时要留意方向。");
        w.use(cog, AllBlocks.ANDESITE_CASING.asStack());
        w.place(cog, CMIBlocks.ANDESITE_ENCASED_MANA_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        w.speed(cog, -256);
        w.text(cog, "Right-click with an Andesite Casing to enclose the cogwheel.", "手持安山机壳右键，就能包住魔力齿轮。");
        w.scene.overlay().showControls(util.vector().topOf(cog), net.createmod.catnip.math.Pointing.DOWN, 45)
                .rightClick().whileSneaking().withItem(AllItems.WRENCH.asStack());
        w.scene.idle(25);
        w.place(cog, CMIBlocks.MANA_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        w.use(cog, AllBlocks.BRASS_CASING.asStack());
        w.place(cog, CMIBlocks.BRASS_ENCASED_MANA_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        w.speed(cog, -256);
        w.text(cog, "Brass Casings are another option. Sneak and right-click with a wrench to remove the casing.", "也可以使用黄铜机壳；潜行时手持扳手右键，能够拆下机壳。");
        w.text(cog, "A casing does not replace the mist supply. Keep the cogwheel inside the field.", "包上机壳后仍然需要雾场，别切断雾的供应。");
        w.mistOff(mist); w.finish();
    }
}
