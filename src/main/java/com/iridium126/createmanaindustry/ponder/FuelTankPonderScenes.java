package com.iridium126.createmanaindustry.ponder;

import static com.iridium126.createmanaindustry.ponder.Workshop.*;
import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankBlock;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankBlockEntity;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelRodStructure;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

public final class FuelTankPonderScenes {
    private FuelTankPonderScenes() {}
    private static void cell(Workshop w, BlockPos pos) {
        w.place(pos, CMIBlocks.MOLTEN_SALT_FUEL_TANK.get());
        w.scene.world().modifyBlockEntity(pos, FuelTankBlockEntity.class, be -> be.applyFluidTankSize(1));
    }

    private static void group(Workshop w, BlockPos controller, BlockPos... cells) {
        for (BlockPos pos : cells) {
            w.scene.world().modifyBlockEntityNBT(w.util.select().position(pos), FuelTankBlockEntity.class, nbt -> {
                nbt.putInt("Count", cells.length);
                if (!pos.equals(controller)) {
                    nbt.put("Controller", net.minecraft.nbt.NbtUtils.writeBlockPos(controller));
                    return;
                }
                net.minecraft.nbt.CompoundTag geometry = new net.minecraft.nbt.CompoundTag();
                net.minecraft.nbt.ListTag positions = new net.minecraft.nbt.ListTag();
                BlockPos max = controller;
                for (BlockPos cell : cells) {
                    positions.add(net.minecraft.nbt.NbtUtils.writeBlockPos(cell.subtract(controller)));
                    max = new BlockPos(Math.max(max.getX(), cell.getX()), Math.max(max.getY(), cell.getY()), Math.max(max.getZ(), cell.getZ()));
                }
                net.minecraft.nbt.CompoundTag basin = new net.minecraft.nbt.CompoundTag();
                basin.put("Cells", positions);
                net.minecraft.nbt.ListTag basins = new net.minecraft.nbt.ListTag();
                basins.add(basin);
                geometry.put("Basins", basins);
                geometry.put("Min", net.minecraft.nbt.NbtUtils.writeBlockPos(controller));
                geometry.put("Max", net.minecraft.nbt.NbtUtils.writeBlockPos(max));
                com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankConnectivity.BasinData
                        .writeSurfaces(geometry, "Surfaces", new float[]{controller.getY() + .5f});
                nbt.put("Basins", geometry);
                nbt.put("BasinGeometry", geometry.copy());
                nbt.put("Surfaces", geometry.get("Surfaces").copy());
            });
        }
    }

    public static void storage(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "fuel_tank_storage", "Storage that fits the workshop", "按工坊形状搭建储液罐");
        BlockPos tank = p(4, 1, 3);
        cell(w, tank);
        w.text(tank, "Molten Salt Fuel Tanks store fluids and connect to adjacent tanks.", "熔盐燃料罐可以储存液体，与相邻的罐体连接。");
        cell(w, tank.east()); cell(w, tank.east(2)); cell(w, tank.east(2).south());
        w.text(tank.east(2), "Build an L shape to fit around a corner. The group does not have to be a rectangular box.", "沿墙角搭成 L 形也可以，不必组成长方体。");
        w.tank(tank.west(3), CMIFluids.LIQUID_MEDIA.get(), 4000);
        w.pump(tank.west(2), Direction.EAST); w.pipe(tank.west(), Direction.WEST, Direction.EAST);
        group(w, tank, tank, tank.east(), tank.east(2), tank.east(2).south());
        w.liquid(tank, CMIFluids.LIQUID_MEDIA.get(), FuelTankBlockEntity.getCapacityPerBlock() * 2);
        w.text(tank.west(), "Use pipes and a powered pump to fill the group through one of its tanks.", "通过管道和有动力的泵，从任意一个罐体向整组灌液。");
        w.point(tank.east(), PonderPalette.GREEN);
        w.text(tank.east(), "Only face-to-face neighbours connect. A diagonal contact is not enough.", "只有面贴面的罐体才能连接，仅对角接触不算相连。");
        w.text(tank, "More tanks add capacity, within the configured group limit. Keep different fluids in separate groups.", "增加罐体可扩大容量，但有配置规定的规模上限。不同液体要分组储存。");
        w.finish();
    }

    public static void decoration(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "fuel_tank_windows", "A tank to match your workshop", "给储罐换上工坊外衣");
        BlockPos tank = p(4, 1, 3);
        cell(w, tank); cell(w, tank.above());
        w.liquid(tank, CMIFluids.LIQUID_MEDIA.get(), FuelTankBlockEntity.getCapacityPerBlock() / 2);
        w.text(tank, "The tank's shell accepts copycat materials while its windows show the liquid.", "罐体外壳可以覆盖材料，观察窗仍然能看见液体。");
        w.use(tank, new ItemStack(Blocks.COPPER_BLOCK));
        w.scene.world().modifyBlockEntity(tank, FuelTankBlockEntity.class, be -> be.setMaterial(Blocks.COPPER_BLOCK.defaultBlockState()));
        w.text(tank, "Right-click with a suitable solid block to apply that material to this tank.", "手持合适的实心方块右键，为这个罐体覆盖材料。");
        w.use(tank, AllItems.WRENCH.asStack());
        w.scene.world().modifyBlock(tank, s -> s.setValue(FuelTankBlock.SIDE_OPEN, false), false);
        w.text(tank, "Use the wrench on a side to open or close the side windows.", "用扳手操作侧面，可以开关侧面的观察窗。");
        w.use(tank.above(), AllItems.WRENCH.asStack());
        w.scene.world().modifyBlock(tank.above(), s -> s.setValue(FuelTankBlock.TOP_OPEN, false), false);
        w.text(tank.above(), "The top window has its own setting. Choose which parts you want to see.", "顶部观察窗可以单独设置，按需要决定哪些位置可见。");
        w.scene.world().modifyBlock(tank, s -> s.setValue(FuelTankBlock.SIDE_OPEN, true), false);
        w.text(tank, "Window and material changes alter the appearance, not the stored fluid.", "开关观察窗或更换外壳，只改变外观，不会改变储存的液体。");
        w.finish();
    }

    public static void moving(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "fuel_tank_moving", "Carrying fluid on a contraption", "让运动结构携带液体");
        BlockPos bearing = p(4, 1, 4), tank = bearing.above(), port = tank.east();
        w.place(bearing, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.place(bearing.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        cell(w, tank);
        w.liquid(tank, CMIFluids.LIQUID_MEDIA.get(), 1000);
        w.text(tank, "Fuel Tanks can travel with an assembled Create contraption and carry their fluid.", "熔盐燃料罐可以随机械动力运动结构一起移动，并携带内部液体。");
        w.place(port, AllBlocks.PORTABLE_FLUID_INTERFACE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        w.scene.effects().superGlue(tank, Direction.EAST, true);
        w.use(tank, AllItems.SUPER_GLUE.asStack());
        w.text(port, "Glue a Portable Fluid Interface to the moving tank before assembly.", "组装之前，用强力胶把移动端流体接口粘到罐体上。");
        var section = w.scene.world().makeSectionIndependent(util.select().fromTo(tank, port));
        w.scene.world().configureCenterOfRotation(section, Vec3.atCenterOf(bearing));
        w.scene.world().rotateSection(section, 0, 360, 0, 100);
        w.scene.world().rotateBearing(bearing, 360, 100);
        w.scene.idle(110);
        w.text(tank, "The tank moves as part of the contraption. Fixed pipes cannot follow it.", "罐体会成为运动结构的一部分，固定管道不能跟着它移动。");
        BlockPos fixed = port.east(2);
        w.place(fixed, AllBlocks.PORTABLE_FLUID_INTERFACE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
        w.scene.world().modifyBlockEntityNBT(util.select().fromTo(port, fixed),
                com.simibubi.create.content.contraptions.actors.psi.PortableFluidInterfaceBlockEntity.class, nbt -> {
                    nbt.putFloat("Distance", 1); nbt.putFloat("Timer", 14);
                });
        w.text(fixed, "Face a stationary interface towards it, leaving a one-block gap at the docking position.", "在停靠位置对向放置固定端接口，中间留一格空隙。");
        w.pipe(fixed.south(), Direction.NORTH, Direction.SOUTH);
        w.pump(fixed.south(2), Direction.SOUTH);
        w.tank(fixed.south(3), CMIFluids.LIQUID_MEDIA.get(), 1000);
        w.liquid(tank, CMIFluids.LIQUID_MEDIA.get(), 0);
        w.text(fixed, "At a connected stop, a pump on the stationary side transfers the stored fluid.", "接口停靠连接后，由固定端的泵转移储存的液体。");
        w.finish();
    }

    public static void structure(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "fuel_rod_structure", "Building the glass enclosure", "搭建玻璃包围结构");
        BlockPos center = p(4, 1, 3);
        cell(w, center);
        w.text(center, "Start the smallest fuel-rod layer with one Fuel Tank at its centre.", "最小的燃料棒结构，每层从中央的一个燃料罐开始。");
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST})
            w.place(center.relative(d), Blocks.GLASS);
        w.text(center, "Surround its four horizontal faces with glass. The corner spaces can remain empty.", "在前后左右四个面放上玻璃，四个角可以留空。");
        cell(w, center.above());
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST})
            w.place(center.above().relative(d), Blocks.GLASS);
        w.scene.world().modifyBlockEntity(center, FuelTankBlockEntity.class,
                be -> be.setRodData(new FuelRodStructure.RodData(center, new int[]{2, 2}), false));
        w.text(center.above(), "Repeat directly above, keeping every layer's centre on the same vertical line.", "向上重复搭建，让每层中心保持在同一条竖线上。");
        w.remove(center.east()); w.point(center.east(), PonderPalette.RED);
        w.scene.world().modifyBlockEntity(center, FuelTankBlockEntity.class, be -> be.setRodData(null, false));
        w.text(center.east(), "A missing glass face breaks recognition. Replace it to restore the enclosure.", "缺少一面玻璃会破坏结构识别，补回即可恢复。");
        w.place(center.east(), Blocks.GLASS);
        w.scene.world().modifyBlockEntity(center, FuelTankBlockEntity.class,
                be -> be.setRodData(new FuelRodStructure.RodData(center, new int[]{2, 2}), false));
        w.text(center, "Larger layers use a diamond outline of glass with tanks inside. This demonstrates enclosure recognition, not electricity generation.", "更大的一层用玻璃围成菱形，内部填满罐体。这里演示的是结构识别功能，不是发电。");
        w.finish();
    }
}
