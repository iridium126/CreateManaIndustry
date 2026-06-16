package com.iridium126.createtricks.content.kinetics.bnb;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createtricks.CreateTricks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Centralised lazy-init cache for reflection into the Bits'n'Bobs cogwheel-chain
 * classes. Follows the same pattern as {@code TricksterReflection}.
 * <p>
 * Every {@code Class.forName} / {@code getMethod} / {@code getField} call is
 * hoisted into a one-time static initialiser so that hot paths (render, stress,
 * execution limit) pay only the {@code Method.invoke} cost.
 */
public final class BnBReflection {

	// --- BnB CogwheelChainBlockEntity --------------------------------------

	private static volatile boolean chainBEInitialized;
	private static Class<?> chainBEClass;
	private static Method isControllerMethod;
	private static Method getChainMethod;
	private static Method getSpeedMethod;
	private static Method getChainRotationFactorMethod;

	// --- BnB CogwheelChain --------------------------------------------------

	private static volatile boolean chainInitialized;
	private static Class<?> chainClass;
	private static Method getChainPathCogwheelNodesMethod;

	// --- BnB PathedCogwheelNode --------------------------------------------

	private static volatile boolean nodeInitialized;
	private static Method localPosMethod;
	private static Method sideFactorMethod;
	private static Method rotationAxisMethod;
	private static Method isLargeMethod;
	private static Method offsetForSmallCogwheelMethod;

	// --- Trickster ModularSpellConstructBlock -------------------------------

	private static volatile boolean facingInitialized;
	@Nullable
	private static Property<Direction> facingProperty;

	private BnBReflection() {}

	// ========================================================================
	// CogwheelChainBlockEntity
	// ========================================================================

	private static synchronized void ensureChainBEInit() {
		if (chainBEInitialized)
			return;
		chainBEInitialized = true;
		try {
			chainBEClass = Class.forName(
					"com.kipti.bnb.content.cogwheel_chain.block.CogwheelChainBlockEntity");
			isControllerMethod = chainBEClass.getMethod("isController");
			getChainMethod = chainBEClass.getMethod("getChain");
			getSpeedMethod = chainBEClass.getMethod("getSpeed");
			getChainRotationFactorMethod = chainBEClass.getMethod("getChainRotationFactor");
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.warn("BnB chain block entity reflection unavailable", e);
		}
	}

	public static boolean isChainBE(Object be) {
		ensureChainBEInit();
		return chainBEClass != null && chainBEClass.isInstance(be);
	}

	public static boolean isController(Object be) {
		ensureChainBEInit();
		try {
			return (Boolean) isControllerMethod.invoke(be);
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	@Nullable
	public static Object getChain(Object be) {
		ensureChainBEInit();
		try {
			return getChainMethod.invoke(be);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	public static float getSpeed(Object be) {
		ensureChainBEInit();
		try {
			return (Float) getSpeedMethod.invoke(be);
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	public static float getChainRotationFactor(Object be) {
		ensureChainBEInit();
		try {
			return (Float) getChainRotationFactorMethod.invoke(be);
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	// ========================================================================
	// CogwheelChain
	// ========================================================================

	private static synchronized void ensureChainInit() {
		if (chainInitialized)
			return;
		chainInitialized = true;
		try {
			chainClass = Class.forName("com.kipti.bnb.content.cogwheel_chain.graph.CogwheelChain");
			getChainPathCogwheelNodesMethod = chainClass.getMethod("getChainPathCogwheelNodes");
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.warn("BnB chain reflection unavailable", e);
		}
	}

	@SuppressWarnings("unchecked")
	public static List<Object> getChainPathCogwheelNodes(Object chain) {
		ensureChainInit();
		try {
			return (List<Object>) getChainPathCogwheelNodesMethod.invoke(chain);
		} catch (ReflectiveOperationException e) {
			return List.of();
		}
	}

	// ========================================================================
	// PathedCogwheelNode
	// ========================================================================

	private static synchronized void ensureNodeInit() {
		if (nodeInitialized)
			return;
		nodeInitialized = true;
		try {
			Class<?> nodeClass = Class.forName(
					"com.kipti.bnb.content.cogwheel_chain.graph.PathedCogwheelNode");
			localPosMethod = nodeClass.getMethod("localPos");
			sideFactorMethod = nodeClass.getMethod("sideFactor");
			rotationAxisMethod = nodeClass.getMethod("rotationAxis");
			isLargeMethod = nodeClass.getMethod("isLarge");
			offsetForSmallCogwheelMethod = nodeClass.getMethod("offsetForSmallCogwheel");
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.warn("BnB path node reflection unavailable", e);
		}
	}

	public static BlockPos localPos(Object node) {
		ensureNodeInit();
		try {
			return (BlockPos) localPosMethod.invoke(node);
		} catch (ReflectiveOperationException e) {
			return BlockPos.ZERO;
		}
	}

	public static float sideFactor(Object node) {
		ensureNodeInit();
		try {
			return (Float) sideFactorMethod.invoke(node);
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	public static Direction.Axis rotationAxis(Object node) {
		ensureNodeInit();
		try {
			return (Direction.Axis) rotationAxisMethod.invoke(node);
		} catch (ReflectiveOperationException e) {
			return Direction.Axis.Y;
		}
	}

	public static boolean isLarge(Object node) {
		ensureNodeInit();
		try {
			return (Boolean) isLargeMethod.invoke(node);
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	public static boolean offsetForSmallCogwheel(Object node) {
		ensureNodeInit();
		try {
			return (Boolean) offsetForSmallCogwheelMethod.invoke(node);
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	// ========================================================================
	// ModularSpellConstructBlock.FACING
	// ========================================================================

	private static synchronized void ensureFacingInit() {
		if (facingInitialized)
			return;
		facingInitialized = true;
		try {
			Class<?> blockClass = Class.forName(
					"dev.enjarai.trickster.block.ModularSpellConstructBlock");
			Field facingField = blockClass.getField("FACING");
			@SuppressWarnings("unchecked")
			Property<Direction> prop = (Property<Direction>) facingField.get(null);
			facingProperty = prop;
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.warn("ModularSpellConstructBlock.FACING unavailable", e);
			facingProperty = null;
		}
	}

	@Nullable
	public static Property<Direction> facingProperty() {
		ensureFacingInit();
		return facingProperty;
	}

	// ========================================================================
	// Per-tick chain speed cache (used by KineticsSpellCoreItemMixin)
	// ========================================================================

	private static final Map<BlockPos, CachedSpeed> SPEED_CACHE = new ConcurrentHashMap<>();

	public static float getCachedChainSpeed(BlockPos spellPos, long gameTime) {
		CachedSpeed entry = SPEED_CACHE.get(spellPos);
		return entry != null && entry.gameTime == gameTime ? entry.speed : -1f;
	}

	public static void putCachedChainSpeed(BlockPos spellPos, float speed, long gameTime) {
		SPEED_CACHE.put(spellPos.immutable(), new CachedSpeed(speed, gameTime));
	}

	private record CachedSpeed(float speed, long gameTime) {}
}
