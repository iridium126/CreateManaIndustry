package com.iridium126.createmanaindustry.content.kinetics.bnb;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;

/**
 * Lightweight per-tick chain speed cache used by {@code KineticsSpellCoreItemMixin}.
 * Separated from {@code BnBReflection} so that class can freely import BnB types
 * without causing class-load failures when BnB is not installed.
 */
public final class ChainSpeedCache {

	private static final Map<BlockPos, CachedSpeed> SPEED_CACHE = new ConcurrentHashMap<>();

	private ChainSpeedCache() {}

	public static float getCachedChainSpeed(BlockPos spellPos, long gameTime) {
		CachedSpeed entry = SPEED_CACHE.get(spellPos);
		return entry != null && entry.gameTime == gameTime ? entry.speed : -1f;
	}

	public static void putCachedChainSpeed(BlockPos spellPos, float speed, long gameTime) {
		SPEED_CACHE.put(spellPos.immutable(), new CachedSpeed(speed, gameTime));
	}

	private record CachedSpeed(float speed, long gameTime) {}
}
