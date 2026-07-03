package com.iridium126.createmanaindustry.content.kinetics.bnb;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

public final class BnBChainRenderContext {
	private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
	private static final float VELOCITY_EXPIRY_SECONDS = 2.0f;
	private static final Map<BlockPos, CachedVelocity> CHAIN_ANGULAR_VELOCITIES = new ConcurrentHashMap<>();
	private static final double SCALE = BnBKineticsCoreNodes.KINETICS_CORE_RADIUS
			/ BnBKineticsCoreNodes.BNB_SMALL_COGWHEEL_RADIUS;

	private BnBChainRenderContext() {}

	public static void begin(BlockEntity be) {
		if (be != null && be.getLevel() != null)
			CURRENT.set(new Context(be.getLevel(), be.getBlockPos()));
		else
			CURRENT.remove();
	}

	public static void end() { CURRENT.remove(); }

	public static Vec3 adjustKineticsCorePosition(BlockPos relativePos,
			Vec3 nodeOffset, Vec3 original) {
		Context ctx = CURRENT.get();
		if (ctx == null) return original;

		BlockPos worldPos = ctx.origin.offset(relativePos);
		if (!BnBKineticsCoreNodes.isModularSpellConstruct(ctx.level, worldPos))
			return original;
		if (!BnBKineticsCoreNodes.hasAnyKineticsCore(ctx.level, worldPos))
			return original;

		Vec3 originLower = Vec3.atLowerCornerOf(ctx.origin);
		Vec3 originalWorld = originLower.add(original);
		Vec3 coreCenter = BnBKineticsCoreNodes.getNearestCoreCenter(
				ctx.level, worldPos, originalWorld);

		return coreCenter.subtract(originLower).add(nodeOffset.scale(SCALE));
	}

	// ========================================================================
	// Angular velocity cache
	// ========================================================================

	public static void putChainAngularVelocity(BlockPos spellPos, float av) {
		CHAIN_ANGULAR_VELOCITIES.put(spellPos.immutable(),
				new CachedVelocity(av, AnimationTickHolder.getRenderTime()));
	}

	public static float getChainAngularVelocity(BlockPos spellPos) {
		CachedVelocity e = CHAIN_ANGULAR_VELOCITIES.get(spellPos);
		if (e == null) return 0f;
		if (AnimationTickHolder.getRenderTime() - e.renderTime
				> VELOCITY_EXPIRY_SECONDS) return 0f;
		return e.angularVelocity;
	}

	private record CachedVelocity(float angularVelocity, float renderTime) {}
	private record Context(Level level, BlockPos origin) {}
}
