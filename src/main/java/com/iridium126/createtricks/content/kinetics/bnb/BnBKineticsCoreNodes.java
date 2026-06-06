package com.iridium126.createtricks.content.kinetics.bnb;

import java.lang.reflect.Field;

import org.joml.Vector3f;

import com.iridium126.createtricks.content.items.KineticsSpellCoreItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class BnBKineticsCoreNodes {
	public static final int NO_SLOT = -1;

	private static final String MODULAR_SPELL_CONSTRUCT_BLOCK =
		"dev.enjarai.trickster.block.ModularSpellConstructBlock";

	private BnBKineticsCoreNodes() {}

	public static KineticsCoreCogwheelNode tryCreate(Level level, BlockPos pos, BlockHitResult hit) {
		int slot = getTargetKineticsCoreSlot(level, pos, hit);
		if (slot == NO_SLOT)
			return null;
		return create(level, pos, slot);
	}

	public static KineticsCoreCogwheelNode create(Level level, BlockPos pos, int slot) {
		if (!hasKineticsCoreInSlot(level, pos, slot))
			return null;
		return new KineticsCoreCogwheelNode(pos, getFacing(level, pos).getAxis(), slot, getCoreCenter(level, pos, slot));
	}

	public static boolean isModularSpellConstruct(Level level, BlockPos pos) {
		return isModularSpellConstructBlock(level.getBlockState(pos).getBlock());
	}

	public static boolean isModularSpellConstructBlock(Block block) {
		try {
			return Class.forName(MODULAR_SPELL_CONSTRUCT_BLOCK).isInstance(block);
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	public static boolean hasAnyKineticsCore(Level level, BlockPos pos) {
		if (!isModularSpellConstruct(level, pos))
			return false;

		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof Container container))
			return false;

		for (int slot = 1; slot < container.getContainerSize(); slot++) {
			if (isKineticsCore(container.getItem(slot)))
				return true;
		}
		return false;
	}

	public static int getKineticsCoreCount(Level level, BlockPos pos) {
		if (!isModularSpellConstruct(level, pos))
			return 0;

		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof Container container))
			return 0;

		int count = 0;
		for (int slot = 1; slot < container.getContainerSize(); slot++) {
			if (isKineticsCore(container.getItem(slot)))
				count++;
		}
		return count;
	}

	public static boolean hasKineticsCoreInSlot(Level level, BlockPos pos, int slot) {
		if (!isModularSpellConstruct(level, pos))
			return false;

		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof Container container))
			return false;
		if (slot < 1 || slot >= container.getContainerSize())
			return false;

		return isKineticsCore(container.getItem(slot));
	}

	private static boolean isKineticsCore(ItemStack stack) {
		return KineticsSpellCoreItem.is(stack);
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
			if (isKineticsCore(container.getItem(slot))) {
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
			if (!isKineticsCore(container.getItem(slot)))
				continue;
			double distance = hitLocation.distanceToSqr(getCoreCenter(level, pos, slot));
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearestSlot = slot;
			}
		}
		return nearestSlot;
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

	public record KineticsCoreCogwheelNode(BlockPos pos, Direction.Axis rotationAxis, int slot, Vec3 center) {}
}
