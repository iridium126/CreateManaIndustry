package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import java.lang.reflect.Field;

import org.joml.Vector3f;

import com.iridium126.createtricks.content.items.KineticsSpellCoreItem;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class CogwheelChainNodes {

	public static final int NO_SLOT = -1;
	public static final double SMALL_COGWHEEL_RADIUS = 0.5;
	public static final double LARGE_COGWHEEL_RADIUS = 0.75;
	public static final double KINETICS_CORE_RADIUS = 0.14;
	public static final double SMALL_COGWHEEL_CHAIN_WIDTH = 0.56;
	public static final double LARGE_COGWHEEL_CHAIN_WIDTH = 0.82;
	public static final double KINETICS_CORE_CHAIN_WIDTH = 0.24;

	private static final String MODULAR_SPELL_CONSTRUCT_BLOCK =
		"dev.enjarai.trickster.block.ModularSpellConstructBlock";

	private CogwheelChainNodes() {}

	public static CogwheelChainNode tryCreate(Level level, BlockPos pos, BlockHitResult hit) {
		BlockState state = level.getBlockState(pos);
		if (ICogWheel.isSmallCog(state) || ICogWheel.isLargeCog(state)) {
			Direction.Axis axis = getAxis(state);
			boolean isLarge = ICogWheel.isLargeCog(state);
			return new CogwheelChainNode(pos, axis, isLarge, CogwheelChainNode.Type.COGWHEEL, NO_SLOT);
		}

		int slot = getTargetKineticsCoreSlot(level, pos, hit);
		if (slot != NO_SLOT) {
			return new CogwheelChainNode(pos, Direction.Axis.Y, false, CogwheelChainNode.Type.KINETICS_CORE, slot);
		}

		return null;
	}

	public static boolean isValidEndpoint(Level level, CogwheelChainNode node) {
		if (node.isCogwheel()) {
			BlockState state = level.getBlockState(node.pos());
			return (ICogWheel.isSmallCog(state) || ICogWheel.isLargeCog(state))
				&& ICogWheel.isLargeCog(state) == node.isLarge();
		}

		return hasKineticsCoreInSlot(level, node.pos(), node.slot());
	}

	public static boolean isValidInteractTarget(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return ICogWheel.isSmallCog(state) || ICogWheel.isLargeCog(state) || hasAnyKineticsCore(level, pos);
	}

	public static Vec3 getRenderPosition(Level level, CogwheelChainNode node) {
		if (node.isCogwheel())
			return Vec3.atCenterOf(node.pos());
		return getCoreCenter(level, node.pos(), node.slot());
	}

	public static double getRadius(CogwheelChainNode node) {
		if (node.isKineticsCore())
			return KINETICS_CORE_RADIUS;
		return node.isLarge() ? LARGE_COGWHEEL_RADIUS : SMALL_COGWHEEL_RADIUS;
	}

	public static double getChainWidth(CogwheelChainNode node) {
		if (node.isKineticsCore())
			return KINETICS_CORE_CHAIN_WIDTH;
		return node.isLarge() ? LARGE_COGWHEEL_CHAIN_WIDTH : SMALL_COGWHEEL_CHAIN_WIDTH;
	}

	private static Direction.Axis getAxis(BlockState state) {
		try {
			return state.getValue(BlockStateProperties.AXIS);
		} catch (IllegalArgumentException e) {
			return Direction.Axis.Y;
		}
	}

	private static int getTargetKineticsCoreSlot(Level level, BlockPos pos, BlockHitResult hit) {
		if (!isModularSpellConstruct(level, pos))
			return NO_SLOT;

		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof Container container))
			return NO_SLOT;

		int onlySlot = NO_SLOT;
		int kineticsCoreCount = 0;
		for (int slot = 1; slot < container.getContainerSize(); slot++) {
			if (KineticsSpellCoreItem.is(container.getItem(slot))) {
				onlySlot = slot;
				kineticsCoreCount++;
			}
		}
		if (kineticsCoreCount == 0)
			return NO_SLOT;
		if (kineticsCoreCount == 1)
			return onlySlot;

		Vec3 hitLocation = hit.getLocation();
		double nearestDistance = Double.MAX_VALUE;
		int nearestSlot = NO_SLOT;
		for (int slot = 1; slot < container.getContainerSize(); slot++) {
			if (!KineticsSpellCoreItem.is(container.getItem(slot)))
				continue;
			double distance = hitLocation.distanceToSqr(getCoreCenter(level, pos, slot));
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearestSlot = slot;
			}
		}
		return nearestSlot;
	}

	private static boolean hasAnyKineticsCore(Level level, BlockPos pos) {
		if (!isModularSpellConstruct(level, pos))
			return false;

		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof Container container))
			return false;

		for (int slot = 1; slot < container.getContainerSize(); slot++) {
			if (KineticsSpellCoreItem.is(container.getItem(slot)))
				return true;
		}
		return false;
	}

	private static boolean hasKineticsCoreInSlot(Level level, BlockPos pos, int slot) {
		if (!isModularSpellConstruct(level, pos))
			return false;

		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof Container container))
			return false;
		if (slot < 1 || slot >= container.getContainerSize())
			return false;

		return KineticsSpellCoreItem.is(container.getItem(slot));
	}

	private static boolean isModularSpellConstruct(Level level, BlockPos pos) {
		try {
			return Class.forName(MODULAR_SPELL_CONSTRUCT_BLOCK).isInstance(level.getBlockState(pos).getBlock());
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	private static Vec3 getCoreCenter(Level level, BlockPos pos, int slot) {
		int index = Math.max(0, slot - 1);
		int x = index % 2;
		int z = index / 2;
		Vec3 local = new Vec3((18f / 2 * x + 3.5f) / 16f, 10.5f / 16f, (18f / 2 * z + 3.5f) / 16f);

		Direction facing = getFacing(level, pos);
		Vector3f offset = new Vector3f((float) local.x - 0.5f, (float) local.y - 0.5f, (float) local.z - 0.5f);
		offset.rotate(facing.getRotation());

		return Vec3.atLowerCornerOf(pos)
			.add(0.5f + offset.x, 0.5f + offset.y, 0.5f + offset.z);
	}

	private static Direction getFacing(Level level, BlockPos pos) {
		try {
			Field facingField = Class.forName(MODULAR_SPELL_CONSTRUCT_BLOCK).getField("FACING");
			@SuppressWarnings("unchecked")
			Property<Direction> facingProperty = (Property<Direction>) facingField.get(null);
			return level.getBlockState(pos).getValue(facingProperty);
		} catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException e) {
			return Direction.UP;
		}
	}
}
