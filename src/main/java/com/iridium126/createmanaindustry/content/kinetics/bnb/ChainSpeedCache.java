package com.iridium126.createmanaindustry.content.kinetics.bnb;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;

/**
 * Lightweight per-tick chain speed cache used by {@code KineticsSpellCoreItem}.
 */
public final class ChainSpeedCache {

    private static final Map<BlockPos, CachedSpeed> SPEED_CACHE = new ConcurrentHashMap<>();

    private ChainSpeedCache() {}

    public static float getCachedChainSpeed(BlockPos spellPos, long gameTime) {
        CachedSpeed entry = SPEED_CACHE.get(spellPos);
        if (entry == null)
            return -1f;
        if (entry.gameTime != gameTime) {
            SPEED_CACHE.remove(spellPos);
            return -1f;
        }
        return entry.speed;
    }

    public static void putCachedChainSpeed(BlockPos spellPos, float speed, long gameTime) {
        SPEED_CACHE.put(spellPos.immutable(), new CachedSpeed(speed, gameTime));
    }

    private record CachedSpeed(float speed, long gameTime) {}
}
