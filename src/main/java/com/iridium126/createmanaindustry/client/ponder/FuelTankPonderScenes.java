package com.iridium126.createmanaindustry.client.ponder;

import static com.iridium126.createmanaindustry.client.ponder.Workshop.*;
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
        Workshop w = new Workshop(builder, util, "fuel_tank_storage", "Storage that fits the workshop", "使用熔盐燃料罐存储流体");
        BlockPos tank = p(4, 1, 3);
        cell(w, tank);
        cell(w, tank.east()); cell(w, tank.east(2)); cell(w, tank.east(2).south());
        w.text(70, tank.east(2), "Build an L shape to fit around a corner. The group does not have to be a rectangular box.", "熔盐燃料罐可以连接为 L 形，无须组成长方体");
        w.tank(tank.west(3), CMIFluids.LIQUID_MEDIA.get(), 4000);
        w.pump(tank.west(2), Direction.EAST); w.pipe(tank.west(), Direction.WEST, Direction.EAST);
        group(w, tank, tank, tank.east(), tank.east(2), tank.east(2).south());
        w.liquid(tank, CMIFluids.LIQUID_MEDIA.get(), FuelTankBlockEntity.getCapacityPerBlock() * 2);
        w.text(70, tank.west(), "Connected tanks share their fluid storage. One pipe connection can fill the whole group.", "相连的罐体共享流体，一处管道连接即可向整组输入");
        w.point(tank.east(), PonderPalette.GREEN);
        w.text(70, tank.east(), "Only face-to-face neighbours connect. A diagonal contact is not enough.", "罐体需要通过面接触相连，对角接触无法建立连接");
        w.text(80, tank, "More tanks add capacity, within the configured group limit. Keep different fluids in separate groups.", "增加罐体可以扩容，规模上限由配置决定，不同流体需要分组存储");
        w.clearWorkArea();
        BlockPos bearing = p(4, 2, 3), carried = bearing.above(), port = carried.east();
        w.place(bearing, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.place(bearing.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        cell(w, carried);
        w.liquid(carried, CMIFluids.LIQUID_MEDIA.get(), 1000);
        w.explain(70, carried, "Fuel Tanks can travel with an assembled Create contraption and carry their fluid.", "熔盐燃料罐可以随动态结构移动，并携带其中的流体");
        w.place(port, AllBlocks.PORTABLE_FLUID_INTERFACE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        w.scene.effects().superGlue(carried, Direction.EAST, true);
        var section = w.scene.world().makeSectionIndependent(w.util.select().fromTo(carried, port));
        w.scene.world().configureCenterOfRotation(section, Vec3.atCenterOf(bearing));
        w.scene.world().rotateSection(section, 0, 360, 0, 70);
        w.scene.world().rotateBearing(bearing, 360, 70);
        w.idle(80);
        w.text(70, carried, "Fixed pipes cannot access a moving tank. Use Portable Fluid Interfaces for the exchange.", "管道无法与移动的罐体直接交互，需要使用移动式流体接口");
        BlockPos fixed = port.east(2);
        w.place(fixed, AllBlocks.PORTABLE_FLUID_INTERFACE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
        w.scene.world().modifyBlockEntityNBT(w.util.select().fromTo(port, fixed),
                com.simibubi.create.content.contraptions.actors.psi.PortableFluidInterfaceBlockEntity.class, nbt -> {
                    nbt.putFloat("Distance", 1); nbt.putFloat("Timer", 14);
                });
        w.pipe(fixed.south(), Direction.NORTH, Direction.SOUTH);
        w.pump(fixed.south(2), Direction.SOUTH);
        w.tank(fixed.south(3), CMIFluids.LIQUID_MEDIA.get(), 1000);
        w.explain(70, fixed, "At a connected stop, a pump on the stationary side transfers the stored fluid.", "接口连接时，固定端的泵可以转移动态结构中的流体");
        w.liquid(carried, CMIFluids.LIQUID_MEDIA.get(), 0);
        w.finish();
    }

    public static void decoration(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "fuel_tank_windows", "A tank to match your workshop", "调整熔盐燃料罐的外观");
        BlockPos tank = p(4, 1, 3);
        cell(w, tank); cell(w, tank.above());
        w.liquid(tank, CMIFluids.LIQUID_MEDIA.get(), FuelTankBlockEntity.getCapacityPerBlock() / 2);
        w.text(70, tank, "The tank's shell accepts copycat materials while its windows show the liquid.", "燃料罐的外壳可以覆盖其他材料，观察窗仍会显示流体");
        w.explain(70, tank, "Right-click with a suitable solid block to apply that material to this tank.", "手持合适的实心方块右击罐体，可以覆盖其外壳");
        w.use(tank, new ItemStack(Blocks.COPPER_BLOCK));
        w.scene.world().modifyBlockEntity(tank, FuelTankBlockEntity.class, be -> be.setMaterial(Blocks.COPPER_BLOCK.defaultBlockState()));
        w.explain(60, tank, "Use the wrench on a side to open or close the side windows.", "使用扳手可以打开或关闭侧面的观察窗");
        w.use(tank, AllItems.WRENCH.asStack());
        w.scene.world().modifyBlock(tank, s -> s.setValue(FuelTankBlock.SIDE_OPEN, false), false);
        w.explain(60, tank.above(), "The top window has its own setting. Choose which parts you want to see.", "顶部观察窗可以单独打开或关闭");
        w.use(tank.above(), AllItems.WRENCH.asStack());
        w.scene.world().modifyBlock(tank.above(), s -> s.setValue(FuelTankBlock.TOP_OPEN, false), false);
        w.scene.world().modifyBlock(tank, s -> s.setValue(FuelTankBlock.SIDE_OPEN, true), false);
        w.text(60, tank, "Window and material changes alter the appearance, not the stored fluid.", "调整外观不会影响罐体中的流体");
        w.finish();
    }

    public static void structure(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "fuel_rod_structure", "Building the glass enclosure", "搭建燃料棒结构");
        BlockPos center = p(4, 1, 3);
        cell(w, center);
        w.text(70, center, "Start the smallest fuel-rod layer with one Fuel Tank at its centre.", "最小燃料棒结构的每层中心需要放置一个燃料罐");
        w.explain(60, center, "Surround its four horizontal faces with glass. The corner spaces can remain empty.", "在罐体的四个水平面放置玻璃，角落可以留空");
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST})
            w.place(center.relative(d), Blocks.GLASS);
        w.explain(60, center.above(), "Repeat directly above, keeping every layer's centre on the same vertical line.", "向上重复搭建时，各层中心需要保持对齐");
        cell(w, center.above());
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST})
            w.place(center.above().relative(d), Blocks.GLASS);
        w.scene.world().modifyBlockEntity(center, FuelTankBlockEntity.class,
                be -> be.setRodData(new FuelRodStructure.RodData(center, new int[]{2, 2}), false));
        w.explain(60, center.east(), "A missing glass face breaks recognition. Replace it to restore the enclosure.", "缺少玻璃会使结构失效，补回后可以恢复");
        w.remove(center.east()); w.point(center.east(), PonderPalette.RED);
        w.scene.world().modifyBlockEntity(center, FuelTankBlockEntity.class, be -> be.setRodData(null, false));
        w.waitForText();
        w.place(center.east(), Blocks.GLASS);
        w.scene.world().modifyBlockEntity(center, FuelTankBlockEntity.class,
                be -> be.setRodData(new FuelRodStructure.RodData(center, new int[]{2, 2}), false));
        w.text(80, center, "Larger layers use a diamond outline of glass with tanks inside. This demonstrates enclosure recognition, not electricity generation.", "较大的结构需要用玻璃围成菱形，并在内部填入罐体，此结构不会发电");
        w.finish();
    }
}
