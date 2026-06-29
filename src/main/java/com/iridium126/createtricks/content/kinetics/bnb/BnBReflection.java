package com.iridium126.createtricks.content.kinetics.bnb;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.cake.azimuth.behaviour.SuperBlockEntityBehaviour;
import com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviour;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain;
import com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PathedCogwheelNode;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Direct-type accessors for the Bits'n'Bobs cogwheel-chain API.
 * <p>
 * All methods degrade gracefully via {@link NoClassDefFoundError} guarding
 * so that non-BnB callers (e.g. {@code KineticsSpellCoreItemMixin}) work
 * without BnB installed.
 */
public final class BnBReflection {

	private BnBReflection() {}

	// ========================================================================
	// Behaviour look-up
	// ========================================================================

	@Nullable
	private static CogwheelChainBehaviour getBehaviour(Object be) {
		if (!(be instanceof BlockEntity blockEntity))
			return null;
		Level level = blockEntity.getLevel();
		if (level == null)
			return null;
		try {
			return SuperBlockEntityBehaviour.getOptional(level, blockEntity.getBlockPos(),
					CogwheelChainBehaviour.TYPE).orElse(null);
		} catch (NoClassDefFoundError e) {
			return null;
		}
	}

	// ========================================================================
	// Chain membership queries
	// ========================================================================

	public static boolean isChainBE(Object be) {
		return getBehaviour(be) != null;
	}

	public static boolean isController(Object be) {
		try {
			CogwheelChainBehaviour behaviour = getBehaviour(be);
			return behaviour != null && behaviour.isController();
		} catch (NoClassDefFoundError e) {
			return false;
		}
	}

	@Nullable
	public static CogwheelChain getChain(Object be) {
		try {
			CogwheelChainBehaviour behaviour = getBehaviour(be);
			return behaviour != null ? behaviour.getControlledChain() : null;
		} catch (NoClassDefFoundError e) {
			return null;
		}
	}

	public static float getSpeed(Object be) {
		if (be instanceof KineticBlockEntity kbe)
			return kbe.getSpeed();
		return 0;
	}

	public static float getChainRotationFactor(Object be) {
		try {
			CogwheelChainBehaviour behaviour = getBehaviour(be);
			return behaviour != null ? behaviour.getChainRotationFactor() : 0;
		} catch (NoClassDefFoundError e) {
			return 0;
		}
	}

	// ========================================================================
	// CogwheelChain
	// ========================================================================

	public static List<PathedCogwheelNode> getChainPathCogwheelNodes(Object chain) {
		try {
			return ((CogwheelChain) chain).getChainPathCogwheelNodes();
		} catch (NoClassDefFoundError | ClassCastException e) {
			return List.of();
		}
	}

	// ========================================================================
	// PathedCogwheelNode
	// ========================================================================

	public static BlockPos localPos(Object node) {
		try {
			return ((PathedCogwheelNode) node).localPos();
		} catch (NoClassDefFoundError | ClassCastException e) {
			return BlockPos.ZERO;
		}
	}

	public static float sideFactor(Object node) {
		try {
			return ((PathedCogwheelNode) node).sideFactor();
		} catch (NoClassDefFoundError | ClassCastException e) {
			return 0;
		}
	}

	public static Direction.Axis rotationAxis(Object node) {
		try {
			return ((PathedCogwheelNode) node).rotationAxis();
		} catch (NoClassDefFoundError | ClassCastException e) {
			return Direction.Axis.Y;
		}
	}

	public static boolean isLarge(Object node) {
		try {
			return ((PathedCogwheelNode) node).isLarge();
		} catch (NoClassDefFoundError | ClassCastException e) {
			return false;
		}
	}

	public static boolean hasSmallCogwheelOffset(Object node) {
		try {
			return ((PathedCogwheelNode) node).hasSmallCogwheelOffset();
		} catch (NoClassDefFoundError | ClassCastException e) {
			return false;
		}
	}
}
