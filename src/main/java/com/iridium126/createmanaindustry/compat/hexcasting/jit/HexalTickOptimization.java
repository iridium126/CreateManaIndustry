package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.RenderedSpell;
import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ram.talia.hexal.Hexal;
import ram.talia.hexal.api.config.HexalConfig;
import ram.talia.hexal.common.casting.actions.spells.great.OpTick;
import ram.talia.hexal.common.lib.hex.HexalImageComponents;

/** Implements the specialized effect used only by the verified Hexal action's JIT path. */
public final class HexalTickOptimization {
    public static final ResourceLocation ACTION_ID = ResourceLocation.fromNamespaceAndPath("hexal", "tick");
    private static final Map<BlockPos, Assets> ASSETS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<BlockPos, Assets> eldest) {
            return size() > 128;
        }
    };

    private HexalTickOptimization() {}

    static boolean isAction(Action action) { return action == OpTick.INSTANCE; }

    static int argumentCount() { return OpTick.INSTANCE.getArgc(); }

    public static Map<BlockPos, Integer> counters(CastingImage image) {
        return image.getComponent(HexalImageComponents.TIMES_TICKED_MAP);
    }

    private static long cost(int count) {
        return Hexal.toMediaFromDust(HexalConfig.Server.TICK_CONSTANT_COST.get()
                + HexalConfig.Server.TICK_COST_PER_TICKED.get() * count);
    }

    static long prepare(BlockPos pos, CastingEnvironment env, CastingImage image,
                        ExecutionScope scope, Assets assets, boolean foldedCounter) {
        if (scope != null) {
            if (!scope.skipCachedBuddingAmethystRangeCheck(env, pos)) env.assertVecInRange(assets.center());
        } else {
            env.assertVecInRange(assets.center());
        }
        Map<BlockPos, Integer> counters = counters(image);
        if (foldedCounter) return cost(scope.currentCachedTickCounterValue());
        boolean owned = scope != null && scope.ownsTickCounters(counters);
        int count = owned ? scope.cachedTickCounterValue(counters, pos)
                : counters == null ? 0 : counters.getOrDefault(pos, 0);
        if (owned) scope.prepareTickCounterUpdate(counters, pos, count + 1);
        return cost(count);
    }

    static Assets assets(BlockPos pos, ExecutionScope scope) {
        if (scope != null) {
            Assets cached = scope.cachedFastTickAssets(pos);
            if (cached != null) return cached;
            Assets created = createAssets(pos, scope);
            scope.rememberFastTickAssets(pos, created);
            return created;
        }
        synchronized (ASSETS) {
            return ASSETS.computeIfAbsent(pos.immutable(), key -> createAssets(key, null));
        }
    }

    private static Assets createAssets(BlockPos pos, ExecutionScope scope) {
        BlockPos target = pos.immutable();
        Vec3 center = target.getCenter();
        var particle = new OperatorSideEffect.Particles(ParticleSpray.cloud(center, 1.0, 5));
        var attempt = new OperatorSideEffect.AttemptSpell(new TickEffect(target, scope), true, true);
        return new Assets(center, particle, attempt, List.of(attempt), List.of(attempt, particle));
    }

    record Assets(Vec3 center, OperatorSideEffect.Particles particle, OperatorSideEffect.AttemptSpell attempt,
                  List<OperatorSideEffect> attemptOnly, List<OperatorSideEffect> withParticles) {}

    public static boolean isOptimizedEffect(RenderedSpell spell) { return spell instanceof TickEffect; }

    private record TickEffect(BlockPos pos, ExecutionScope scope) implements RenderedSpell {
        @Override public void cast(CastingEnvironment env) {
            throw new IllegalStateException("call cast(env, image) instead.");
        }

        @Override public CastingImage cast(CastingEnvironment env, CastingImage image) {
            if (scope != null) {
                long mediaCost = scope.takePreparedTickMediaCost(env);
                if (mediaCost > 0) {
                    try {
                        if (!scope.directTickMediaExtractionEnabled()
                                || !FastHexOPMediaPool.extractPreparedTickMediaDirectly(env, mediaCost)) {
                            scope.flushTickCounterWrites();
                            env.extractMedia(mediaCost, false);
                        }
                    } finally {
                        FastHexOPMediaPool.end(env);
                    }
                }
            }

            Map<BlockPos, Integer> counters = counters(image);
            boolean reuse = scope != null && scope.reuseTickUserDataEnabled()
                    && scope.canMutateTickUserDataInPlace(env);
            // AttemptSpell publishes the new image after the block operation returns.
            // Preserve that boundary when the counter map is shared with the current image.
            tickBlock(env.getWorld(), env);
            if (reuse && scope.ownsTickCounters(counters)) {
                scope.incrementTickCounter(counters, pos);
                return image;
            }
            if (scope != null) scope.flushTickCounterWrites();
            Map<BlockPos, Integer> updated = counters == null ? new HashMap<>() : new HashMap<>(counters);
            updated.put(pos, updated.getOrDefault(pos, 0) + 1);
            if (reuse) scope.ownTickCounters(updated);
            return image.withComponent(HexalImageComponents.TIMES_TICKED_MAP, updated);
        }

        @SuppressWarnings("unchecked")
        private void tickBlock(ServerLevel level, CastingEnvironment env) {
            boolean folded = scope != null && scope.consumeFoldedLoopTickEffect();
            boolean reuseBuddingState = scope != null && scope.cacheBuddingAmethystStateEnabled();
            BlockState state = reuseBuddingState ? scope.cachedBuddingAmethystState(level, pos) : null;
            if (state != null && state.is(Blocks.BUDDING_AMETHYST)) {
                if (folded) scope.recordFoldedBuddingAmethystTick();
                scope.rememberBuddingAmethystRangeCheck(env, pos);
                if (isAllowed(state)) {
                    tickRandomly(level, state);
                    return;
                }
                scope.invalidateBuddingAmethystState();
            }

            LevelChunk chunk = scope != null && scope.cacheTickChunkEnabled()
                    && level.getClass() == ServerLevel.class && !level.isOutsideBuildHeight(pos)
                    ? scope.cachedTickChunk(level, pos) : null;
            state = chunk == null ? level.getBlockState(pos) : chunk.getBlockState(pos);
            BlockEntity entity = chunk == null ? level.getBlockEntity(pos)
                    : chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
            if (!isAllowed(state)) return;

            if (entity != null) {
                var ticker = entity.getBlockState().getTicker(level, (BlockEntityType<BlockEntity>) entity.getType());
                if (ticker != null) {
                    beforeWorldEffect();
                    ticker.tick(level, pos, entity.getBlockState(), entity);
                }
            } else {
                boolean budding = state.is(Blocks.BUDDING_AMETHYST);
                if (budding && scope != null) {
                    scope.rememberBuddingAmethystRangeCheck(env, pos);
                    if (reuseBuddingState) scope.rememberBuddingAmethystState(level, pos, state);
                }
                tickRandomly(level, state);
            }
        }

        private boolean isAllowed(BlockState state) {
            var block = state.getBlock();
            if (scope == null || !scope.cacheTickBlockEligibilityEnabled())
                return HexalConfig.Server.isAccelerateAllowed(BuiltInRegistries.BLOCK.getKey(block));
            var denyList = HexalConfig.Server.DISALLOWED_BLOCKS_TICK.get();
            Boolean cached = scope.cachedTickBlockEligibility(block, denyList);
            if (cached != null) return cached;
            boolean allowed = !denyList.contains(BuiltInRegistries.BLOCK.getKey(block).toString());
            scope.rememberTickBlockEligibility(block, denyList, allowed);
            return allowed;
        }

        private void tickRandomly(ServerLevel level, BlockState state) {
            if (state.isRandomlyTicking()
                    && level.getRandom().nextInt(HexalConfig.Server.TICK_RANDOM_TICK_I_PROB.get()) == 0) {
                beforeWorldEffect();
                if (scope != null) scope.recordRandomTick();
                state.randomTick(level, pos, level.getRandom());
            }
        }

        private void beforeWorldEffect() {
            if (scope == null) return;
            scope.flushTickCounterWrites();
            scope.flushDeferredMediaUsedStat();
            scope.flushDeferredPersonalMediaWrites();
            scope.invalidateLoopTickActionPrecheck();
            scope.invalidateTickRangeCheck();
            scope.invalidateCachedTickCounter();
            scope.invalidateBuddingAmethystState();
            scope.invalidateSpendMediaTriggerCache();
        }
    }
}
