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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

public final class BnBKineticsCoreNodes {
	public static final double BNB_SMALL_COGWHEEL_RADIUS = 0.5;
	public static final double KINETICS_CORE_RADIUS = 0.14;

	public static final ThreadLocal<Boolean> LAST_NODE_IS_SPELL = new ThreadLocal<>();

	private static final String MODULAR_SPELL_CONSTRUCT_BLOCK =
		"dev.enjarai.trickster.block.ModularSpellConstructBlock";

	private static volatile boolean facingInitTried;
	private static Property<Direction> cachedFacingProperty;

	private BnBKineticsCoreNodes() {}

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

	public static Vec3 getNearestCoreCenter(Level level, BlockPos pos, Vec3 target) {
		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof Container container))
			return Vec3.atCenterOf(pos);

		Vec3 nearest = Vec3.atCenterOf(pos);
		double nearestDistance = Double.MAX_VALUE;
		for (int slot = 1; slot < container.getContainerSize(); slot++) {
			if (!isKineticsCore(container.getItem(slot)))
				continue;
			Vec3 center = getCoreCenter(level, pos, slot);
			double dist = center.distanceToSqr(target);
			if (dist < nearestDistance) {
				nearest = center;
				nearestDistance = dist;
			}
		}
		return nearest;
	}

	public static boolean isAlreadyLinked(Level level, BlockPos pos) {
		int r = 8;
		for (BlockPos cp : BlockPos.betweenClosed(
				pos.offset(-r, -r, -r), pos.offset(r, r, r))) {
			BlockEntity be = level.getBlockEntity(cp);
			var chain = BnBReflection.getChainIfController(be);
			if (chain == null)
				continue;
			for (var node : chain.getChainPathCogwheelNodes()) {
				if (cp.immutable().offset(node.localPos()).equals(pos))
					return true;
			}
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	public static Direction getFacing(BlockState state) {
		try {
			return state.getValue(getFacingProperty());
		} catch (IllegalArgumentException e) {
			return Direction.UP;
		}
	}

	// ---- internals ----------------------------------------------------------

	private static boolean isKineticsCore(ItemStack stack) {
		return KineticsSpellCoreItem.is(stack);
	}

	private static Vec3 getCoreCenter(Level level, BlockPos pos, int slot) {
		int index = Math.max(0, slot - 1);
		int x = index % 2;
		int z = index / 2;
		Vec3 local = new Vec3((18.0 / 2.0 * x + 3.5) / 16.0, 10.5 / 16.0,
				(18.0 / 2.0 * z + 3.5) / 16.0);

		Direction facing = getFacing(level.getBlockState(pos));
		Vector3f offset = new Vector3f((float) local.x - 0.5f,
				(float) local.y - 0.5f, (float) local.z - 0.5f);
		offset.rotate(facing.getRotation());

		return Vec3.atLowerCornerOf(pos)
			.add(0.5f + offset.x, 0.5f + offset.y, 0.5f + offset.z);
	}

	@SuppressWarnings("unchecked")
	private static Property<Direction> getFacingProperty() {
		if (!facingInitTried) {
			facingInitTried = true;
			try {
				Class<?> blockClass = Class.forName(
						MODULAR_SPELL_CONSTRUCT_BLOCK);
				Field facingField = blockClass.getField("FACING");
				cachedFacingProperty = (Property<Direction>) facingField
						.get(null);
			} catch (ReflectiveOperationException e) {
				cachedFacingProperty = null;
			}
		}
		return cachedFacingProperty;
	}
}
