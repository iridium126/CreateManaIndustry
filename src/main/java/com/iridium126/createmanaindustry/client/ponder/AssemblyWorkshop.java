package com.iridium126.createmanaindustry.client.ponder;

import static com.iridium126.createmanaindustry.client.ponder.Workshop.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.fluids.spout.SpoutBlockEntity;
import com.simibubi.create.foundation.ponder.element.BeltItemElement;
import net.createmod.ponder.api.element.ElementLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;

/** A short, fully connected belt with three independently powered workstations. */
final class AssemblyWorkshop {
    final Workshop w;
    final BlockPos deployer = p(2, 3, 3), spout = p(4, 3, 3), press = p(6, 3, 3);
    private ElementLink<BeltItemElement> item;

    AssemblyWorkshop(Workshop w, Fluid liquid) {
        this.w = w;
        for (int x = 1; x <= 7; x++) {
            BlockPos pos = p(x, 1, 3);
            int index = x - 1;
            w.place(pos, AllBlocks.BELT.getDefaultState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                    .setValue(BeltBlock.SLOPE, BeltSlope.HORIZONTAL)
                    .setValue(BeltBlock.PART, x == 1 ? BeltPart.START : x == 7 ? BeltPart.END : BeltPart.MIDDLE));
            w.scene.world().modifyBlockEntityNBT(w.util.select().position(pos), BeltBlockEntity.class, nbt -> {
                nbt.putBoolean("IsController", index == 0);
                nbt.put("Controller", NbtUtils.writeBlockPos(p(1, 1, 3)));
                nbt.putInt("Length", 7); nbt.putInt("Index", index);
            });
            w.speed(pos, -32);
        }
        // Facing EAST makes positive motor rotation move items west, so the
        // power source and every belt segment share the same negative RPM.
        w.drive(p(1, 1, 3), Direction.Axis.Z, -32);
        w.place(deployer, AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false));
        w.drive(deployer, Direction.Axis.Z);
        w.place(spout, AllBlocks.SPOUT.get());
        w.tank(spout.south(3), liquid, 2000);
        w.pump(spout.south(2), Direction.NORTH);
        w.pipe(spout.south(), Direction.NORTH, Direction.SOUTH);
        w.liquid(spout, liquid, 1000);
        w.place(press, AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        w.drive(press, Direction.Axis.Z);
    }
    void input(ItemStack stack) {
        item = w.scene.world().createItemOnBelt(deployer.below(2), Direction.UP, stack);
        w.scene.world().stallBeltItem(item, true);
    }
    void deploy(ItemStack tool, ItemStack result) {
        w.scene.world().modifyBlockEntityNBT(w.util.select().position(deployer), DeployerBlockEntity.class,
                nbt -> nbt.put("HeldItem", tool.saveOptional(w.scene.world().getHolderLookupProvider())));
        w.use(deployer, tool);
        w.scene.world().moveDeployer(deployer, 1, 20); w.scene.idle(25);
        w.scene.world().changeBeltItemTo(item, result);
        w.scene.world().moveDeployer(deployer, -1, 20); w.scene.idle(25);
    }
    void moveTo(BlockPos station, ItemStack stack) {
        w.scene.world().stallBeltItem(item, false);
        w.scene.idle(32);
        // Restage at the exact processing centre, avoiding timing drift on skipped keyframes.
        clearBelt();
        item = w.scene.world().createItemOnBelt(station.below(2), Direction.UP, stack);
        w.scene.world().stallBeltItem(item, true);
    }
    void fill(Fluid fluid, ItemStack result) {
        w.liquid(spout, fluid, 1000);
        w.scene.world().modifyBlockEntity(spout, SpoutBlockEntity.class, be -> be.processingTicks = 20);
        w.scene.idle(20);
        w.liquid(spout, fluid, 0);
        w.scene.world().changeBeltItemTo(item, result);
        w.scene.idle(20);
    }
    void press(ItemStack result) {
        w.press(press, false);
        w.scene.world().changeBeltItemTo(item, result);
        w.scene.effects().indicateSuccess(press.below(2));
    }
    void output(ItemStack stack) {
        w.scene.world().stallBeltItem(item, false); w.scene.idle(20);
        clearBelt();
        w.depot(p(8, 1, 3), stack);
    }
    private void clearBelt() {
        w.scene.world().changeBeltItemTo(item, ItemStack.EMPTY);
        // The Create helper removes items near one segment, not the whole belt.
        for (int x = 1; x <= 7; x++) w.scene.world().removeItemsFromBelt(p(x, 1, 3));
    }
}
