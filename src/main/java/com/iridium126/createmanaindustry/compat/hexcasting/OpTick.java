package com.iridium126.createmanaindustry.compat.hexcasting;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastCompoundTagCopy;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastHexOPMediaPool;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;

import at.petrak.hexcasting.api.casting.OperatorUtils;
import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.RenderedSpell;
import at.petrak.hexcasting.api.casting.castables.SpellAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.Iota;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Hexal's Tick Acceleration great spell. */
public final class OpTick implements SpellAction {

    public static final OpTick INSTANCE = new OpTick();
    public static final ResourceLocation ACTION_ID = ResourceLocation.fromNamespaceAndPath("createmanaindustry", "tick");
    public static final String TAG_TIMES_TICKED = "hexal:times_ticked";
    private static final Map<BlockPos, FastTickAssets[]> FAST_TICK_ASSETS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockPos, FastTickAssets[]> eldest) {
            return size() > 128;
        }
    };
    private static BlockPos lastFastAssetPos;
    private static FastTickAssets[] lastFastAssets;

    private OpTick() {}

    @Override
    public int getArgc() {
        return 1;
    }

    @Override
    public Result execute(List<? extends Iota> args, CastingEnvironment env) {
        throw new IllegalStateException("call executeWithUserdata instead.");
    }

    @Override
    public Result executeWithUserdata(List<? extends Iota> args, CastingEnvironment env, CompoundTag userData) {
        BlockPos pos = OperatorUtils.getBlockPos(args, 0, getArgc());
        long cost = prepareTick(pos, env, userData, false, false, null, null);
        return new Result(new TickSpell(pos, null, false, false, null), cost,
                List.of(ParticleSpray.cloud(Vec3.atCenterOf(pos), 1.0, 5)), 1L);
    }

    public long executeForFastPath(BlockPos pos, CastingEnvironment env, CompoundTag userData,
                                   boolean mutateUserDataInPlace, ExecutionScope scope,
                                   FastTickAssets fastAssets) {
        return prepareTick(pos, env, userData, true, mutateUserDataInPlace, scope, fastAssets);
    }

    /** Cost-only preparation for the uninterrupted folded Tick body with its counter already cached. */
    public long executeForFoldedLoopPath(BlockPos pos, CastingEnvironment env, CompoundTag userData,
                                         ExecutionScope scope, FastTickAssets fastAssets) {
        String posKey = fastAssets.posKey();
        if (!scope.hasCachedTickCounterFor(userData, TAG_TIMES_TICKED, posKey))
            return executeForFastPath(pos, env, userData, true, scope, fastAssets);

        boolean cachedRangeCheck = ServerConfig.hexJitCacheTickRangeCheck
                && scope.skipCachedBuddingAmethystRangeCheck(env, pos);
        if (!cachedRangeCheck) env.assertVecInRange(fastAssets.center());
        int timesTicked = scope.currentCachedTickCounterValue();
        return ServerConfig.tickConstantCost + ServerConfig.tickCostPerTicked * timesTicked;
    }

    private long prepareTick(BlockPos pos, CastingEnvironment env, CompoundTag userData,
                             boolean fastUserDataCopy, boolean mutateUserDataInPlace,
                             ExecutionScope currentScope, FastTickAssets fastAssets) {
        boolean cachedRangeCheck = currentScope != null && mutateUserDataInPlace
                && ServerConfig.hexJitCacheTickRangeCheck
                && currentScope.skipCachedBuddingAmethystRangeCheck(env, pos);
        if (!cachedRangeCheck) env.assertVecInRange(fastAssets == null ? Vec3.atCenterOf(pos) : fastAssets.center());
        String posKey = fastAssets == null ? pos.toShortString() : fastAssets.posKey();
        boolean cacheTickCounter = fastUserDataCopy && mutateUserDataInPlace && currentScope != null
                && currentScope.reuseTickUserDataEnabled();
        CompoundTag timesTickedMap = cacheTickCounter
                ? currentScope.cachedTickCounterMap(userData, TAG_TIMES_TICKED, posKey)
                : userData.getCompound(TAG_TIMES_TICKED);
        int timesTicked = cacheTickCounter
                ? currentScope.cachedTickCounterValue(userData, timesTickedMap, TAG_TIMES_TICKED, posKey)
                : timesTickedMap.getInt(posKey);
        if (fastAssets != null && mutateUserDataInPlace) {
            ExecutionScope scope = currentScope;
            if (scope != null) scope.prepareTickCounterUpdate(userData, timesTickedMap, pos,
                    TAG_TIMES_TICKED, posKey, timesTicked + 1);
        }
        long cost = ServerConfig.tickConstantCost + ServerConfig.tickCostPerTicked * timesTicked;
        return cost;
    }

    public static boolean isTickSpell(RenderedSpell spell) {
        return spell instanceof TickSpell;
    }

    public static FastTickAssets fastAssets(BlockPos pos, boolean mutateUserDataInPlace) {
        return fastAssets(pos, mutateUserDataInPlace, null);
    }

    public static FastTickAssets fastAssets(BlockPos pos, boolean mutateUserDataInPlace,
                                            ExecutionScope scope) {
        if (scope != null && mutateUserDataInPlace) {
            FastTickAssets cached = scope.cachedFastTickAssets(pos);
            if (cached != null) return cached;
            FastTickAssets created = createFastTickAssets(pos, true, scope);
            scope.rememberFastTickAssets(pos, created);
            return created;
        }
        if (lastFastAssetPos != null && pos.equals(lastFastAssetPos)) {
            FastTickAssets cached = lastFastAssets[mutateUserDataInPlace ? 1 : 0];
            if (cached != null) return cached;
        }
        BlockPos key = pos.immutable();
        FastTickAssets[] variants = FAST_TICK_ASSETS.computeIfAbsent(key, ignored -> new FastTickAssets[2]);
        lastFastAssetPos = key;
        lastFastAssets = variants;
        int variant = mutateUserDataInPlace ? 1 : 0;
        if (variants[variant] == null) {
            variants[variant] = createFastTickAssets(key, mutateUserDataInPlace, null);
        }
        return variants[variant];
    }

    private static FastTickAssets createFastTickAssets(BlockPos pos, boolean mutateUserDataInPlace,
                                                       ExecutionScope scope) {
        BlockPos stablePos = pos.immutable();
        String posKey = stablePos.toShortString();
        TickSpell effect = new TickSpell(stablePos, posKey, true, mutateUserDataInPlace, scope);
        List<ParticleSpray> particles = List.of(ParticleSpray.cloud(Vec3.atCenterOf(stablePos), 1.0, 5));
        OperatorSideEffect.AttemptSpell attempt = new OperatorSideEffect.AttemptSpell(effect, true, true);
        return new FastTickAssets(
                posKey,
                particles,
                Vec3.atCenterOf(stablePos),
                attempt,
                List.of(attempt),
                List.of(attempt, new OperatorSideEffect.Particles(particles.get(0))));
    }

    public record FastTickAssets(String posKey, List<ParticleSpray> particles,
                                 Vec3 center, OperatorSideEffect.AttemptSpell attempt,
                                 List<OperatorSideEffect> attemptSideEffects,
                                 List<OperatorSideEffect> attemptAndParticleSideEffects) {}

    private record TickSpell(BlockPos pos, String posKey, boolean fastUserDataCopy,
                             boolean mutateUserDataInPlace, ExecutionScope boundScope) implements RenderedSpell {
        @Override
        public void cast(CastingEnvironment env) {
            throw new IllegalStateException("call cast(env, image) instead.");
        }

        @Override
        @SuppressWarnings("unchecked")
        public CastingImage cast(CastingEnvironment env, CastingImage image) {
            ExecutionScope activeScope = fastUserDataCopy
                    ? boundScope == null ? ExecutionScope.current() : boundScope : null;
            ExecutionScope scope = mutateUserDataInPlace ? activeScope : null;
            boolean foldedLoopTick = scope != null && scope.consumeFoldedLoopTickEffect();
            if (foldedLoopTick && fastUserDataCopy && !image.getSimulateNext()
                    && scope.loopTickBatchEnabled() && scope.combineTickSideEffectsEnabled()
                    && scope.canMutateTickUserDataInPlace(env)) {
                ServerLevel level = env.getWorld();
                BlockState cachedBuddingState = scope.cachedBuddingAmethystState(level, pos);
                if (cachedBuddingState != null && cachedBuddingState.is(Blocks.BUDDING_AMETHYST))
                    return castFoldedBuddingAmethystTick(env, image, scope, level, cachedBuddingState);
            }
            if (scope != null && scope.combineTickSideEffectsEnabled()) {
                long mediaCost = scope.takePreparedTickMediaCost(env);
                if (mediaCost > 0) {
                    try {
                        boolean extractedDirectly = scope.directTickMediaExtractionEnabled()
                                && FastHexOPMediaPool.extractPreparedTickMediaDirectly(env, mediaCost);
                        if (!extractedDirectly) {
                            scope.flushTickCounterWrites();
                            env.extractMedia(mediaCost, false);
                        }
                    } finally {
                        FastHexOPMediaPool.end(env);
                    }
                }
            }
            CompoundTag userData = mutateUserDataInPlace
                    ? image.getUserData()
                    : fastUserDataCopy ? FastCompoundTagCopy.copy(image.getUserData()) : image.getUserData().copy();
            String key = posKey == null ? pos.toShortString() : posKey;
            boolean appliedFastLoopCounter = scope != null
                    && scope.applyFoldedTickCounterFastUpdate(userData, pos, TAG_TIMES_TICKED, key);
            if (!appliedFastLoopCounter && (scope == null || !scope.applyPreparedTickCounterUpdate(userData, pos,
                    TAG_TIMES_TICKED, key))) {
                CompoundTag timesTickedMap = userData.getCompound(TAG_TIMES_TICKED);
                timesTickedMap.putInt(key, timesTickedMap.getInt(key) + 1);
                userData.put(TAG_TIMES_TICKED, timesTickedMap);
            }

            // Preserve every other VM field while returning an image with the updated per-cast counter.
            CastingImage newImage = mutateUserDataInPlace ? image : new CastingImage(
                    image.getStack(), image.getParenCount(), image.getParenthesized(),
                    image.getEscapeNext(), image.getSimulateNext(), image.getOpsConsumed(), userData);

            ServerLevel level = env.getWorld();
            boolean fastBuddingRandom = activeScope == null
                    ? ServerConfig.hexJitFastBuddingAmethystRandomTick
                    : activeScope.fastBuddingAmethystRandomTickEnabled();
            boolean cacheBuddingState = scope != null && scope.cacheBuddingAmethystStateEnabled();
            boolean reuseBuddingState = scope != null && (cacheBuddingState || fastBuddingRandom);
            BlockState blockState = reuseBuddingState
                    ? scope.cachedBuddingAmethystState(level, pos) : null;
            boolean buddingStateWasCached = blockState != null;
            if (buddingStateWasCached) {
                // A cached state is installed only after this fixed target was confirmed both
                // budding amethyst and allowed. Its randomTick updates neighbors, not itself.
                activeScope.rememberBuddingAmethystRangeCheck(env, pos);
                if (fastBuddingRandom) {
                    if (blockState.isRandomlyTicking()
                            && activeScope.shouldRunBuddingAmethystRandomTick(
                                    level, pos, ServerConfig.tickRandomTickIProb))
                        scope.deferBuddingAmethystRandomTick(level, pos, blockState);
                    return newImage;
                }
                if (cacheBuddingState) {
                    if (blockState.isRandomlyTicking()
                            && level.getRandom().nextInt(ServerConfig.tickRandomTickIProb) == 0) {
                        scope.flushDeferredMediaUsedStat();
                        scope.invalidateLoopTickActionPrecheck();
                        blockState.randomTick(level, pos, level.getRandom());
                        activeScope.invalidateSpendMediaTriggerCache();
                    }
                    return newImage;
                }
            }
            var chunk = blockState != null || activeScope == null || !activeScope.cacheTickChunkEnabled()
                    ? null : activeScope.cachedTickChunk(level, pos);
            if (blockState == null)
                blockState = chunk == null ? level.getBlockState(pos) : chunk.getBlockState(pos);
            Block block = blockState.getBlock();
            boolean buddingAmethyst = blockState.is(Blocks.BUDDING_AMETHYST);
            Boolean cachedEligibility = activeScope != null && activeScope.cacheTickBlockEligibilityEnabled()
                    ? activeScope.cachedTickBlockEligibility(block) : null;
            boolean allowed = cachedEligibility != null ? cachedEligibility
                    : ServerConfig.isHexTickAccelerateAllowed(BuiltInRegistries.BLOCK.getKey(block));
            if (cachedEligibility == null && activeScope != null && activeScope.cacheTickBlockEligibilityEnabled())
                activeScope.rememberTickBlockEligibility(block, allowed);
            if (!allowed)
                return newImage;

            if (activeScope != null && buddingAmethyst)
                activeScope.rememberBuddingAmethystRangeCheck(env, pos);

            if (reuseBuddingState && !buddingStateWasCached && buddingAmethyst)
                scope.rememberBuddingAmethystState(level, pos, blockState);

            if (fastUserDataCopy && fastBuddingRandom
                    && buddingAmethyst) {
                if (blockState.isRandomlyTicking()) {
                    boolean randomTick = activeScope != null
                            ? activeScope.shouldRunBuddingAmethystRandomTick(level, pos, ServerConfig.tickRandomTickIProb)
                            : level.getRandom().nextInt(ServerConfig.tickRandomTickIProb) == 0;
                    if (randomTick) {
                        if (mutateUserDataInPlace && scope != null)
                            scope.deferBuddingAmethystRandomTick(level, pos, blockState);
                        else {
                            if (scope != null) {
                                scope.flushDeferredMediaUsedStat();
                                scope.invalidateLoopTickActionPrecheck();
                            }
                            blockState.randomTick(level, pos, level.getRandom());
                            if (activeScope != null) activeScope.invalidateSpendMediaTriggerCache();
                        }
                    }
                }
                return newImage;
            }

            // Budding Amethyst has no block entity. On the opted-in fixed-block path, keep the
            // original per-call world RNG and randomTick behavior while avoiding a redundant
            // Level.getBlockEntity lookup for this known state.
            if (fastUserDataCopy && cacheBuddingState && buddingAmethyst) {
                if (blockState.isRandomlyTicking()
                        && level.getRandom().nextInt(ServerConfig.tickRandomTickIProb) == 0) {
                    scope.flushDeferredMediaUsedStat();
                    scope.invalidateLoopTickActionPrecheck();
                    blockState.randomTick(level, pos, level.getRandom());
                    activeScope.invalidateSpendMediaTriggerCache();
                }
                return newImage;
            }

            BlockEntity targetBE = chunk == null ? level.getBlockEntity(pos) : chunk.getBlockEntity(pos);
            if (targetBE != null) {
                BlockEntityType<BlockEntity> type = (BlockEntityType<BlockEntity>) targetBE.getType();
                BlockEntityTicker<BlockEntity> ticker = targetBE.getBlockState().getTicker(level, type);
                if (ticker != null) {
                    if (scope != null) {
                        scope.flushDeferredMediaUsedStat();
                        scope.invalidateLoopTickActionPrecheck();
                    }
                    ticker.tick(level, pos, targetBE.getBlockState(), targetBE);
                    if (activeScope != null) activeScope.invalidateSpendMediaTriggerCache();
                }
            } else if (blockState.isRandomlyTicking()
                    && level.getRandom().nextInt(ServerConfig.tickRandomTickIProb) == 0) {
                if (scope != null) {
                    scope.flushDeferredMediaUsedStat();
                    scope.invalidateLoopTickActionPrecheck();
                }
                blockState.randomTick(level, pos, level.getRandom());
                if (activeScope != null) activeScope.invalidateSpendMediaTriggerCache();
            }

            return newImage;
        }

        /**
         * Loop-specialized body for a fixed, cached budding amethyst. The ordinary TickSpell path
         * has already validated the target/range and prepared media plus counter updates; there
         * are no observers between these folded calls, so this retains only the per-Tick effects.
         */
        private CastingImage castFoldedBuddingAmethystTick(CastingEnvironment env, CastingImage image,
                                                            ExecutionScope scope, ServerLevel level,
                                                            BlockState state) {
            scope.recordFoldedBuddingAmethystTick();
            long mediaCost = scope.takePreparedTickMediaCost(env);
            if (mediaCost > 0) {
                try {
                    boolean extractedDirectly = scope.directTickMediaExtractionEnabled()
                            && FastHexOPMediaPool.extractPreparedTickMediaDirectly(env, mediaCost);
                    if (!extractedDirectly) {
                        scope.flushTickCounterWrites();
                        env.extractMedia(mediaCost, false);
                    }
                } finally {
                    FastHexOPMediaPool.end(env);
                }
            }

            CompoundTag userData = image.getUserData();
            String key = posKey == null ? pos.toShortString() : posKey;
            boolean appliedFastLoopCounter = scope.applyFoldedTickCounterFastUpdate(
                    userData, pos, TAG_TIMES_TICKED, key);
            if (!appliedFastLoopCounter
                    && !scope.applyPreparedTickCounterUpdate(userData, pos, TAG_TIMES_TICKED, key)) {
                CompoundTag timesTickedMap = userData.getCompound(TAG_TIMES_TICKED);
                timesTickedMap.putInt(key, timesTickedMap.getInt(key) + 1);
                userData.put(TAG_TIMES_TICKED, timesTickedMap);
            }

            scope.rememberBuddingAmethystRangeCheck(env, pos);
            if (state.isRandomlyTicking()) {
                if (scope.fastBuddingAmethystRandomTickEnabled()) {
                    if (scope.shouldRunBuddingAmethystRandomTick(
                            level, pos, ServerConfig.tickRandomTickIProb))
                        scope.deferBuddingAmethystRandomTick(level, pos, state);
                } else if (level.getRandom().nextInt(ServerConfig.tickRandomTickIProb) == 0) {
                    scope.flushDeferredMediaUsedStat();
                    scope.invalidateLoopTickActionPrecheck();
                    state.randomTick(level, pos, level.getRandom());
                    scope.invalidateSpendMediaTriggerCache();
                }
            }
            return image;
        }
    }
}
