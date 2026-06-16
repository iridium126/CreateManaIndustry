package com.iridium126.createtricks.content.kinetics.bnb;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

public final class BnBChainRenderContext {
	private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

	/**
	 * Entries older than this (in seconds of render time) are considered stale and
	 * return 0. Covers the case where a chain is destroyed and its renderer stops
	 * being called, leaving orphaned cache entries.
	 */
	private static final float VELOCITY_EXPIRY_SECONDS = 2.0f;

	/**
	 * Shared cache of angular velocity (radians per second of
	 * {@code AnimationTickHolder.getRenderTime()}) for each modular spell
	 * construct position connected to a cogwheel chain. Each entry records the
	 * render time at which it was written so stale entries can be expired.
	 * <p>
	 * Populated by the chain renderer and read by the spell construct
	 * renderer's kinetics core pass.
	 */
	private static final Map<BlockPos, CachedVelocity> CHAIN_ANGULAR_VELOCITIES = new ConcurrentHashMap<>();

	private BnBChainRenderContext() {}

	public static void begin(Object blockEntity) {
		if (blockEntity instanceof BlockEntity be && be.getLevel() != null) {
			CURRENT.set(new Context(be.getLevel(), be.getBlockPos()));
			return;
		}
		CURRENT.remove();
	}

	public static void end() {
		CURRENT.remove();
	}

	public static Vec3 adjustKineticsCorePosition(BlockPos relativePos, Vec3 nodeOffset, Vec3 original) {
		Context context = CURRENT.get();
		if (context == null)
			return original;

		BlockPos worldPos = context.origin.offset(relativePos);
		if (!BnBKineticsCoreNodes.hasAnyKineticsCore(context.level, worldPos))
			return original;

		Vec3 origin = Vec3.atLowerCornerOf(context.origin);
		Vec3 originalWorld = origin.add(original);
		Vec3 coreCenter = BnBKineticsCoreNodes.getNearestCoreCenter(context.level, worldPos, originalWorld);
		double scale = BnBKineticsCoreNodes.KINETICS_CORE_RADIUS / BnBKineticsCoreNodes.BNB_SMALL_COGWHEEL_RADIUS;
		return coreCenter.subtract(origin).add(nodeOffset.scale(scale));
	}

	/**
	 * Stores the angular velocity for a spell construct connected to a cogwheel
	 * chain, so its kinetics cores can rotate in sync with the chain.
	 *
	 * @param spellPos        the world position of the modular spell construct
	 * @param angularVelocity radians per second of render time (same formula as
	 *                        the chain: {@code 2π × rotationFactor × speed / 1200})
	 */
	public static void putChainAngularVelocity(BlockPos spellPos, float angularVelocity) {
		CHAIN_ANGULAR_VELOCITIES.put(spellPos.immutable(),
				new CachedVelocity(angularVelocity, AnimationTickHolder.getRenderTime()));
	}

	/**
	 * Returns the cached angular velocity for a spell construct position, or 0 if
	 * no connected chain was recorded or the cached entry has expired.
	 */
	public static float getChainAngularVelocity(BlockPos spellPos) {
		CachedVelocity entry = CHAIN_ANGULAR_VELOCITIES.get(spellPos);
		if (entry == null)
			return 0f;
		if (AnimationTickHolder.getRenderTime() - entry.renderTime > VELOCITY_EXPIRY_SECONDS)
			return 0f;
		return entry.angularVelocity;
	}

	private record CachedVelocity(float angularVelocity, float renderTime) {}

	private record Context(Level level, BlockPos origin) {}
}
