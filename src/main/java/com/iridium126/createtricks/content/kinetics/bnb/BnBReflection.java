package com.iridium126.createtricks.content.kinetics.bnb;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createtricks.CreateTricks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Centralised lazy-init cache for reflection into the Bits'n'Bobs cogwheel-chain
 * classes. Follows the same pattern as {@code TricksterReflection}.
 * <p>
 * Every {@code Class.forName} / {@code getMethod} / {@code getField} call is
 * hoisted into a one-time static initialiser so that hot paths (render, stress,
 * execution limit) pay only the {@code Method.invoke} cost.
 * <p>
 * Updated for the behaviour-based BnB architecture where
 * {@code CogwheelChainBehaviour} replaces the removed
 * {@code CogwheelChainBlockEntity}.
 */
public final class BnBReflection {

	// --- BnB CogwheelChainBehaviour -----------------------------------------

	private static volatile boolean behaviourInitialized;
	private static Class<?> behaviourClass;
	private static Object behaviourTypeInstance;
	private static Method isControllerMethod;
	private static Method getControlledChainMethod;
	private static Method getChainRotationFactorMethod;

	// --- BnB SuperBlockEntityBehaviour --------------------------------------

	private static volatile boolean superBEInitialized;
	private static Method superBEGetOptionalMethod;

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
	private static Method hasSmallCogwheelOffsetMethod;

	// --- Trickster ModularSpellConstructBlock -------------------------------

	private static volatile boolean facingInitialized;
	@Nullable
	private static Property<Direction> facingProperty;

	private BnBReflection() {}

	// ========================================================================
	// CogwheelChainBehaviour
	// ========================================================================

	private static synchronized void ensureBehaviourInit() {
		if (behaviourInitialized)
			return;
		behaviourInitialized = true;
		try {
			behaviourClass = Class.forName(
					"com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviour");
			behaviourTypeInstance = behaviourClass.getField("TYPE").get(null);
			isControllerMethod = behaviourClass.getMethod("isController");
			getControlledChainMethod = behaviourClass.getMethod("getControlledChain");
			getChainRotationFactorMethod = behaviourClass.getMethod("getChainRotationFactor");
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.warn("BnB chain behaviour reflection unavailable", e);
		}
	}

	// ========================================================================
	// SuperBlockEntityBehaviour
	// ========================================================================

	private static synchronized void ensureSuperBEInit() {
		if (superBEInitialized)
			return;
		superBEInitialized = true;
		try {
			Class<?> superBEClass = Class.forName(
					"com.cake.azimuth.behaviour.SuperBlockEntityBehaviour");
			superBEGetOptionalMethod = superBEClass.getMethod("getOptional",
					Level.class, BlockPos.class, Class.forName(
							"com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType"));
		} catch (ReflectiveOperationException e) {
			CreateTricks.LOGGER.warn("BnB SuperBlockEntityBehaviour reflection unavailable", e);
		}
	}

	/**
	 * Returns the {@code CogwheelChainBehaviour} attached to a block entity, or
	 * {@code null} if the behaviour is not present.
	 */
	@Nullable
	private static Object getBehaviour(Object be) {
		ensureBehaviourInit();
		ensureSuperBEInit();
		if (behaviourClass == null || superBEGetOptionalMethod == null || behaviourTypeInstance == null)
			return null;
		if (!(be instanceof BlockEntity blockEntity))
			return null;
		Level level = blockEntity.getLevel();
		if (level == null)
			return null;

		try {
			@SuppressWarnings("unchecked")
			Optional<Object> optional = (Optional<Object>) superBEGetOptionalMethod.invoke(
					null, level, blockEntity.getBlockPos(), behaviourTypeInstance);
			return optional.orElse(null);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	// ========================================================================
	// Behaviour-based chain accessors
	// ========================================================================

	public static boolean isChainBE(Object be) {
		return getBehaviour(be) != null;
	}

	public static boolean isController(Object be) {
		Object behaviour = getBehaviour(be);
		if (behaviour == null)
			return false;
		try {
			return (Boolean) isControllerMethod.invoke(behaviour);
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	@Nullable
	public static Object getChain(Object be) {
		Object behaviour = getBehaviour(be);
		if (behaviour == null)
			return null;
		try {
			return getControlledChainMethod.invoke(behaviour);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	public static float getSpeed(Object be) {
		if (be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kbe)
			return kbe.getSpeed();
		return 0;
	}

	public static float getChainRotationFactor(Object be) {
		Object behaviour = getBehaviour(be);
		if (behaviour == null)
			return 0;
		try {
			return (Float) getChainRotationFactorMethod.invoke(behaviour);
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
			chainClass = Class.forName(
					"com.kipti.bnb.content.kinetics.cogwheel_chain.graph.CogwheelChain");
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
					"com.kipti.bnb.content.kinetics.cogwheel_chain.graph.PathedCogwheelNode");
			localPosMethod = nodeClass.getMethod("localPos");
			sideFactorMethod = nodeClass.getMethod("sideFactor");
			rotationAxisMethod = nodeClass.getMethod("rotationAxis");
			isLargeMethod = nodeClass.getMethod("isLarge");
			hasSmallCogwheelOffsetMethod = nodeClass.getMethod("hasSmallCogwheelOffset");
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

	public static boolean hasSmallCogwheelOffset(Object node) {
		ensureNodeInit();
		try {
			return (Boolean) hasSmallCogwheelOffsetMethod.invoke(node);
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
