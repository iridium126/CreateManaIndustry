package com.iridium126.createtricks.content.kinetics.bnb;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

public final class BnBChainRenderContext {
	private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

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

	private record Context(Level level, BlockPos origin) {}
}
