package com.iridium126.createmanaindustry.collision;

/** Test-visible view of the bounded, per-iterator chunk-getter memoization. */
public interface BlockCollisionChunkCacheAccess {
    int cmi$getCachedChunkGetterCount();

    long cmi$getChunkGetterCacheHitCount();

    long cmi$getOriginalChunkGetterCallCount();

    /** Test hook for comparing the same Level getter with and without memoization. */
    void cmi$configureChunkGetterCacheForTest(boolean enabled, boolean countOriginalCalls);

    /** Enables call counting without overriding the query-size activation heuristic. */
    void cmi$countOriginalChunkGetterCallsForTest();
}
