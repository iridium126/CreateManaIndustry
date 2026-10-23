package com.iridium126.createmanaindustry.client.ponder;

import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankBlockEntity;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Scene-local staging only: never registers real-world mist or mutates server state. */
final class Workshop {
    final CreateSceneBuilder scene;
    final SceneBuildingUtil util;
    private int timelineTicks;
    private int textEndsAt;
    private java.util.List<BlockPos> building;
    private final java.util.List<BlockPos> pumps = new java.util.ArrayList<>();

    Workshop(SceneBuilder builder, SceneBuildingUtil util, String id, String english, String chinese) {
        this.scene = new CreateSceneBuilder(builder);
        this.util = util;
        scene.title(id, english);
        scene.configureBasePlate(0, 0, 9);
        scene.scaleSceneView(.72f);
        scene.showBasePlate();
        scene.world().showSection(util.select().fromTo(0, 1, 7, 8, 3, 8), Direction.DOWN);
        idle(20);
    }

    void idle(int ticks) {
        scene.idle(ticks);
        timelineTicks += ticks;
    }

    static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }
    static Block block(String id) { return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)); }
    static ItemStack item(String id) { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))); }
    static Fluid fluid(String id) { return BuiltInRegistries.FLUID.get(ResourceLocation.parse(id)); }

    /** Reveal a prebuilt workstation together, as Create's schematic scenes do. */
    void build(Runnable placements) {
        building = new java.util.ArrayList<>();
        placements.run();
        var positions = building;
        building = null;
        if (positions.isEmpty()) return;
        var selection = util.select().position(positions.getFirst());
        for (int i = 1; i < positions.size(); i++)
            selection = selection.add(util.select().position(positions.get(i)));
        scene.world().showSection(selection, Direction.DOWN);
        idle(20);
    }

    void place(BlockPos pos, BlockState state) {
        scene.world().setBlock(pos, state, false);
        // Dynamically built machines need the same virtual flag as schematic BEs.
        scene.world().modifyBlockEntity(pos, net.minecraft.world.level.block.entity.BlockEntity.class, be -> {
            if (be instanceof net.createmod.ponder.api.VirtualBlockEntity virtual) virtual.markVirtual();
        });
        if (building != null) {
            building.add(pos);
            return;
        }
        scene.world().showSection(util.select().position(pos), Direction.DOWN);
        idle(8);
    }
    void place(BlockPos pos, Block block) { place(pos, block.defaultBlockState()); }
    void remove(BlockPos pos) {
        scene.world().hideSection(util.select().position(pos), Direction.UP);
        idle(15);
        scene.world().setBlock(pos, Blocks.AIR.defaultBlockState(), false);
    }

    /** Start another demonstration without disturbing the floor or backdrop. */
    void clearWorkArea() {
        waitForText();
        var area = util.select().fromTo(0, 1, 0, 8, 6, 6);
        scene.world().hideSection(area, Direction.UP);
        idle(20);
        scene.world().setBlocks(area, Blocks.AIR.defaultBlockState(), false);
        pumps.clear();
    }

    // Chinese is harvested alongside the English text by scripts/ponder/resources.py.
    // Keep each scene's text calls in playback order for Ponder's text_N keys.
    void text(int duration, BlockPos pos, String english, String chinese) {
        explain(duration, pos, english, chinese);
        waitForText();
    }

    /** Caption first, then run the demonstration while it remains on screen. */
    void explain(int duration, BlockPos pos, String english, String chinese) {
        waitForText();
        // Rebuild the scene's pipe flow after the staged connections are in place.
        pumps.forEach(scene.world()::propagatePipeChange);
        scene.overlay().showText(duration).attachKeyFrame().text(english)
                .pointAt(Vec3.atCenterOf(pos)).placeNearTarget();
        // Ponder adds five ticks of fade-in and fade-out to the supplied duration.
        textEndsAt = timelineTicks + duration + 10;
        idle(20);
    }
    void waitForText() {
        if (timelineTicks < textEndsAt) idle(textEndsAt - timelineTicks);
    }
    void point(BlockPos pos, PonderPalette palette) {
        scene.overlay().showOutline(palette, pos, util.select().position(pos), 70);
    }
    void use(BlockPos pos, ItemStack stack) {
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 60)
                .rightClick().withItem(stack);
        idle(15);
    }
    void finish() {
        waitForText();
        idle(20);
        scene.markAsFinished();
    }
    void burner(BlockPos pos, boolean lit) {
        place(pos, CMIBlocks.ALLAY_BURNER.getDefaultState().setValue(AllayBurnerBlock.HEAT_LEVEL,
                lit ? AllayBurnerBlock.HeatLevel.ALLAYHEATED : AllayBurnerBlock.HeatLevel.IDLE));
    }
    void light(BlockPos pos, boolean lit) {
        scene.world().modifyBlock(pos, s -> s.setValue(AllayBurnerBlock.HEAT_LEVEL,
                lit ? AllayBurnerBlock.HeatLevel.ALLAYHEATED : AllayBurnerBlock.HeatLevel.IDLE), false);
        scene.effects().indicateSuccess(pos);
    }
    void depot(BlockPos pos, ItemStack stack) {
        place(pos, AllBlocks.DEPOT.get());
        display(pos, stack);
    }
    void display(BlockPos pos, ItemStack stack) {
        scene.world().removeItemsFromBelt(pos);
        if (!stack.isEmpty()) scene.world().createItemOnBeltLike(pos, Direction.UP, stack);
    }
    void basinItems(BlockPos pos, ItemStack... stacks) {
        scene.world().modifyBlockEntity(pos, BasinBlockEntity.class, be -> {
            for (int i = 0; i < be.inputInventory.getSlots(); i++) be.inputInventory.setStackInSlot(i, ItemStack.EMPTY);
            for (int i = 0; i < stacks.length; i++) be.inputInventory.setStackInSlot(i, stacks[i].copy());
        });
    }
    void liquid(BlockPos pos, Fluid fluid, int amount) {
        scene.world().modifyBlockEntity(pos, SmartBlockEntity.class, be -> {
            FluidStack stack = amount == 0 ? FluidStack.EMPTY : new FluidStack(fluid, amount);
            if (be instanceof FluidTankBlockEntity tank) {
                tank.getTankInventory().setFluid(stack);
            } else if (be instanceof FuelTankBlockEntity tank) {
                tank.getTankInventory().setFluid(stack);
            } else {
                SmartFluidTankBehaviour tank = be instanceof BasinBlockEntity basin ? basin.inputTank
                        : be.getBehaviour(SmartFluidTankBehaviour.TYPE);
                if (tank != null) {
                    tank.getPrimaryHandler().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
                    tank.allowInsertion();
                    tank.getPrimaryHandler().fill(stack, FluidAction.EXECUTE);
                }
            }
        });
    }
    void tank(BlockPos pos, Fluid fluid, int amount) {
        place(pos, AllBlocks.FLUID_TANK.get());
        liquid(pos, fluid, amount);
    }
    void pipe(BlockPos pos, Direction a, Direction b) {
        BlockState state = AllBlocks.FLUID_PIPE.getDefaultState();
        for (var property : state.getProperties()) {
            if (property instanceof net.minecraft.world.level.block.state.properties.BooleanProperty bool) {
                for (Direction direction : Direction.values())
                    if (bool.getName().equals(direction.getSerializedName()))
                        state = state.setValue(bool, direction == a || direction == b);
            }
        }
        place(pos, state);
    }
    void speed(BlockPos pos, float rpm) {
        scene.world().setKineticSpeed(util.select().position(pos), rpm);
    }
    void pump(BlockPos pos, Direction direction) {
        pumps.add(pos);
        place(pos, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(BlockStateProperties.FACING, direction));
        // Pump's small cog meshes with an adjacent, parallel small cog.
        BlockPos gear = pos.above();
        place(gear, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, direction.getAxis()));
        place(gear.relative(direction.getOpposite()), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(BlockStateProperties.FACING, direction));
        speed(gear.relative(direction.getOpposite()), 32);
        speed(gear, 32);
        speed(pos, -32);
        scene.effects().rotationDirectionIndicator(pos);
    }
    void drive(BlockPos machine, Direction.Axis axis) {
        drive(machine, axis, 32);
    }
    void drive(BlockPos machine, Direction.Axis axis, float rpm) {
        // Visible demonstration drive, as in Create's own Ponder schematics.
        Direction negative = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
        BlockPos shaft = machine.relative(negative);
        place(shaft, AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, axis));
        BlockPos motor = shaft.relative(negative);
        place(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(BlockStateProperties.FACING, negative.getOpposite()));
        scene.world().modifyBlockEntityNBT(util.select().position(motor),
                com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity.class,
                nbt -> nbt.putInt("ScrollValue", (int) rpm));
        speed(motor, rpm);
        speed(shaft, rpm);
        speed(machine, rpm);
    }
    void cogDrive(BlockPos machine) {
        cogDrive(machine, 32);
    }
    void cogDrive(BlockPos machine, float rpm) {
        BlockPos gear = machine.west();
        place(gear, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        place(gear.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        speed(gear.below(), rpm);
        speed(gear, rpm);
        speed(machine, -rpm);
    }
    void press(BlockPos pos, boolean basin) {
        scene.world().modifyBlockEntity(pos, MechanicalPressBlockEntity.class,
                be -> be.getPressingBehaviour().start(basin ? PressingBehaviour.Mode.BASIN : PressingBehaviour.Mode.BELT));
        // At the workshop's 32 RPM the full 240-step press cycle takes 60 ticks.
        // Keep the item locked until the head has returned, not just until contact.
        idle(65);
        scene.world().modifyBlockEntity(pos, MechanicalPressBlockEntity.class, be -> {
            var pressing = be.getPressingBehaviour();
            pressing.running = false;
            pressing.prevRunningTicks = pressing.runningTicks = 0;
        });
    }
    void mix(BlockPos pos) {
        scene.world().modifyBlockEntity(pos, MechanicalMixerBlockEntity.class, be -> {
            be.running = true;
            be.runningTicks = 0;
            // Fixed visual cycle: lower for 20 ticks, mix for 20, then raise.
            be.processingTicks = 20;
        });
        idle(65);
        scene.world().modifyBlockEntity(pos, MechanicalMixerBlockEntity.class, be -> {
            be.running = false;
            be.runningTicks = 0;
            be.processingTicks = -1;
        });
    }

    void arm(BlockPos arm, BlockPos input, BlockPos output, String outputType) {
        place(arm, AllBlocks.MECHANICAL_ARM.get());
        // Match Create's arm scene: 48 RPM reaches each target within 24 ticks.
        cogDrive(arm, 48);
        scene.world().modifyBlockEntityNBT(util.select().position(arm),
                com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity.class, nbt -> {
            net.minecraft.nbt.ListTag points = new net.minecraft.nbt.ListTag();
            for (int i = 0; i < 2; i++) {
                net.minecraft.nbt.CompoundTag point = new net.minecraft.nbt.CompoundTag();
                point.putString("Type", i == 0 ? "create:depot" : outputType);
                point.put("Pos", net.minecraft.nbt.NbtUtils.writeBlockPos((i == 0 ? input : output).subtract(arm)));
                point.putString("Mode", i == 0 ? "TAKE" : "DEPOSIT");
                points.add(point);
            }
            nbt.put("InteractionPoints", points);
        });
        idle(20);
    }
    void armTransfer(BlockPos arm, BlockPos input, ItemStack stack) {
        scene.world().instructArm(arm, com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity.Phase.MOVE_TO_INPUT, ItemStack.EMPTY, 0);
        idle(24);
        display(input, ItemStack.EMPTY);
        scene.world().instructArm(arm, com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity.Phase.SEARCH_OUTPUTS, stack, -1);
        idle(20);
        scene.world().instructArm(arm, com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity.Phase.MOVE_TO_OUTPUT, stack, 0);
        idle(24);
        scene.world().instructArm(arm, com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity.Phase.SEARCH_INPUTS, ItemStack.EMPTY, -1);
    }

    /** The holder is overwritten on EVERY replay before any later instruction uses it. */
    Mist mist(BlockPos pos, int color, float radius) {
        Mist handle = new Mist();
        scene.addInstruction(s -> {
            handle.element = new PonderMistElement(pos, radius, .10f, color);
            handle.element.setActive(true);
            s.addElement(handle.element);
        });
        return handle;
    }
    void mistSize(Mist mist, float radius, int color) {
        scene.addInstruction(s -> mist.element.setAppearance(radius, color));
    }
    void mistOff(Mist mist) { scene.addInstruction(s -> mist.element.setActive(false)); }
    static final class Mist { private PonderMistElement element; }
}
