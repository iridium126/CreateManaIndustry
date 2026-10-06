package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.addldata.ADMediaHolder;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.sideeffects.EvalSound;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.FrameEvaluate;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import at.petrak.hexcasting.api.mod.HexStatistics;
import at.petrak.hexcasting.api.advancements.SpendMediaTrigger;
import at.petrak.hexcasting.api.advancements.SpendMediaTrigger.Instance;
import at.petrak.hexcasting.api.advancements.MinMaxLongs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import at.petrak.hexcasting.api.utils.TreeList;
import com.iridium126.createmanaindustry.compat.hexcasting.OpTick;

/** Optional observer bookkeeping. Scopes hold booleans only and are removed in finally blocks. */
public final class ExecutionScope implements AutoCloseable {
    private static final String TAG_PIGMENT = "hexcasting:pigment";
    private static final ClassValue<Boolean> STANDARD_STAFF_PIGMENT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getPigment").getDeclaringClass() == StaffCastEnv.class;
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
    };
    /** Server casts are single-threaded; keep their hot context out of ThreadLocalMap. */
    private static volatile ExecutionScope serverCurrent;
    /** Preserve isolation for non-server callers and independent worker-thread scopes. */
    private static final ThreadLocal<ExecutionScope> OTHER_THREADS = new ThreadLocal<>();
    private static long lastMotionPushes;
    private static long lastMotionWrites;
    private final ExecutionScope previous;
    private final boolean serverThreadScope;
    private final Thread ownerThread;
    private MotionState motions;
    private boolean compiled;
    private boolean notifying;
    private boolean metacastingFrame;
    private boolean addMotionEffect;
    private final boolean batchMotion;
    private final boolean coalesceDecorations;
    private final boolean reuseTickUserData;
    private final boolean cacheTickChunk;
    private final boolean cacheTickBlockEligibility;
    private final boolean cacheBuddingAmethystState;
    private final boolean directTickMediaExtraction;
    private final boolean cacheTickMediaAvailability;
    private final boolean combineTickSideEffects;
    private final boolean fastBuddingAmethystRandomTick;
    private final boolean loopSpecialization;
    private final boolean fastTickAction;
    private final boolean loopTickBatch;
    private final boolean batchMediaUsedStat;
    private final boolean collectMetrics;
    private final boolean batchTickCounterWrites;
    private final boolean cacheTickStackPop;
    private final boolean coalesceEvalSounds;
    private final long registryGeneration;
    private final boolean patternLookupEnabled;
    private final boolean cacheNormalPatternLookup;
    private final boolean cachePerWorldPatternLookup;
    private Map<ParticleSpray, List<PigmentSnapshot>> emittedParticles;
    private ParticleSpray lastSpray;
    private PigmentSnapshot lastPigment;
    private FrozenPigment lastFrozenPigment;
    private boolean hasLastParticle;
    private CompoundTag tickUserData;
    private boolean hasMaxOpCount;
    private int maxOpCount;
    private CompoundTag pendingTickCounterRoot;
    private CompoundTag pendingTickCounterMap;
    private BlockPos pendingTickCounterPos;
    private String pendingTickCounterKey;
    private String pendingTickCounterTag;
    private int pendingTickCounterValue;
    private boolean hasPreparedTickCounterUpdate;
    private CompoundTag deferredTickCounterRoot;
    private CompoundTag deferredTickCounterMap;
    private BlockPos deferredTickCounterPos;
    private String deferredTickCounterKey;
    private String deferredTickCounterTag;
    private int deferredTickCounterValue;
    private boolean hasDeferredTickCounterWrite;
    private long tickCounterWritesDeferred;
    private long tickCounterWriteCommits;
    private static long lastTickCounterWritesDeferred;
    private static long lastTickCounterWriteCommits;
    private TreeList<?> pendingTickStackPopInput;
    private TreeList<?> pendingTickStackPopSource;
    private CastingImage pendingLoopTickInputImage;
    private TreeList<Iota> pendingLoopTickOutputStack;
    private CompoundTag pendingLoopTickUserData;
    private SpellContinuation pendingLoopTickContinuation;
    private PatternIota pendingLoopTickPattern;
    private List<OperatorSideEffect> pendingLoopTickSideEffects;
    private EvalSound pendingLoopTickSound;
    private long tickStackPopCacheHits;
    private static long lastTickStackPopCacheHits;
    private CastingEnvironment cachedTickRangeEnvironment;
    private BlockPos cachedTickRangePos;
    private CastingEnvironment checkedTickRangeGuardEnvironment;
    private boolean tickRangeGuardChecked;
    private boolean tickRangeGuardEligible;
    private boolean tickRangeCacheEligibleForCurrentAction;
    private long tickRangeCheckCacheHits;
    private static long lastTickRangeCheckCacheHits;
    private CastingEnvironment pendingTickMediaEnvironment;
    private long pendingTickMediaCost;
    private boolean hasPendingTickMediaCost;
    private Object deferredPersonalMediaHolder;
    private long deferredPersonalMediaValue;
    private boolean hasDeferredPersonalMediaValue;
    private ServerPlayer deferredMediaUsedStatPlayer;
    private int deferredMediaUsedStat;
    private boolean hasDeferredMediaUsedStat;
    private SpendMediaTrigger cachedSpendMediaTrigger;
    private SpendMediaTrigger cachedSpendMediaRulesTrigger;
    private ServerPlayer cachedSpendMediaRulesPlayer;
    private SpendMediaListenerRule[] cachedSpendMediaListenerRules;
    private boolean hasCachedSpendMediaListenerRules;
    private long spendMediaTriggerCacheHits;
    private static long lastSpendMediaTriggerCacheHits;
    private EvalSound cachedLoopTickHermesSound;
    private EvalSound cachedLoopTickMishapSound;
    private boolean flushingDeferredPersonalMedia;
    private boolean deferredPersonalMediaWritesClosed;
    private long deferredPersonalMediaWrites;
    private static long lastDeferredPersonalMediaWrites;
    private CastingEnvironment checkedFastMediaPoolEnvironment;
    private boolean fastMediaPoolEnvironmentChecked;
    private boolean fastMediaPoolEnvironmentEligible;
    private CompoundTag cachedTickCounterRoot;
    private CompoundTag cachedTickCounterMap;
    private String cachedTickCounterTag;
    private String cachedTickCounterKey;
    private int cachedTickCounterValue;
    private boolean hasCachedTickCounter;
    private boolean cachedTickCounterMapAttached;
    private Iota tickTargetIota;
    private BlockPos tickTargetPos;
    private BlockPos fastTickAssetsPos;
    private OpTick.FastTickAssets fastTickAssets;
    private Level tickChunkLevel;
    private int tickChunkX;
    private int tickChunkZ;
    private LevelChunk tickChunk;
    private Block tickEligibilityBlock;
    private Boolean tickBlockEligibility;
    private TagLookup[] tagLookups;
    private int nextTagLookup;
    private TagLookup lastTagLookup;
    private ResourceKey<?>[] actionPrecheckKeys;
    private double[] actionPrecheckCostModifiers;
    private int nextActionPrecheck;
    private ResourceKey<?> loopTickActionPrecheckKey;
    private double loopTickActionCostModifier;
    private boolean hasLoopTickActionCostModifier;
    private long loopTickActionPrecheckHits;
    private static long lastLoopTickActionPrecheckHits;
    private Player pigmentPlayer;
    private Tag pigmentTagSnapshot;
    private FrozenPigment castPigment;
    private CastingEnvironment unobservedEnvironment;
    private boolean checkedUnobservedEnvironment;
    private boolean canMutateTickUserDataInPlace;
    private long evalSoundCopiesSkipped;
    private static long lastEvalSoundCopiesSkipped;
    private long emptyPostExecutionCallsSkipped;
    private static long lastEmptyPostExecutionCallsSkipped;
    private List<TreeList<Iota>> frameLoopContinuationSources;
    private TreeList<Iota> pendingTickValidationOutput;
    private long tickSubstackValidationSkips;
    private static long lastTickSubstackValidationSkips;
    private long frameLoopTailHits;
    private long frameLoopFrameHits;
    private long frameLoopContinuationHits;
    private long loopTickDispatches;
    private long loopTickRuns;
    private long activeLoopTickRun;
    private long longestLoopTickRun;
    private long loopTickBatchFolds;
    private long foldedBuddingAmethystActions;
    private long foldedBuddingAmethystTickCalls;
    private boolean loopTickDispatchActive;
    private boolean previousLoopTickDispatchWasTick;
    private boolean foldedLoopTickEffectPending;
    private static long lastFrameLoopTailHits;
    private static long lastFrameLoopFrameHits;
    private static long lastFrameLoopContinuationHits;
    private static long lastLoopTickDispatches;
    private static long lastLoopTickRuns;
    private static long lastLongestLoopTickRun;
    private static long lastLoopTickBatchFolds;
    private static long lastFoldedBuddingAmethystActions;
    private static long lastFoldedBuddingAmethystTickCalls;
    private Level buddingAmethystGateLevel;
    private BlockPos buddingAmethystGatePos;
    private int buddingAmethystGateProbability;
    private int buddingAmethystTicksUntilTick;
    private Random buddingAmethystGateRandom;
    private long buddingAmethystRandomTickCalls;
    private static long lastBuddingAmethystRandomTickCalls;
    private Level cachedBuddingStateLevel;
    private BlockPos cachedBuddingStatePos;
    private BlockState cachedBuddingState;
    private ServerLevel pendingBuddingTickLevel;
    private BlockPos pendingBuddingTickPos;
    private BlockState pendingBuddingTickState;
    private int pendingBuddingRandomTicks;
    private ExecutionScope(boolean batchMotion) {
        ownerThread = Thread.currentThread();
        serverThreadScope = HexJitRuntime.onServerThread();
        registryGeneration = HexJitRuntime.generation();
        previous = serverThreadScope ? serverCurrent : OTHER_THREADS.get();
        motions = previous == null ? null : previous.motions;
        this.batchMotion = batchMotion || previous != null && previous.batchMotion;
        coalesceDecorations = ServerConfig.hexJitCoalesceDecorations
                && JitCompatibility.particleCoalescingReady();
        reuseTickUserData = ServerConfig.hexJitReuseTickUserData;
        cacheTickChunk = ServerConfig.hexJitCacheTickChunk;
        cacheTickBlockEligibility = ServerConfig.hexJitCacheTickBlockEligibility;
        cacheBuddingAmethystState = ServerConfig.hexJitCacheBuddingAmethystState;
        directTickMediaExtraction = ServerConfig.hexJitDirectTickMediaExtraction;
        cacheTickMediaAvailability = ServerConfig.hexJitCacheTickMediaAvailability;
        combineTickSideEffects = ServerConfig.hexJitCombineTickSideEffects;
        fastBuddingAmethystRandomTick = ServerConfig.hexJitFastBuddingAmethystRandomTick;
        loopSpecialization = serverThreadScope
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && ServerConfig.hexJitLoopSpecialization && JitCompatibility.frameTailCacheReady();
        collectMetrics = ServerConfig.hexJitCollectMetrics || previous != null && previous.collectMetrics;
        fastTickAction = serverThreadScope
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && ServerConfig.hexJitFastTickAction && HexJitRuntime.enabled()
                && JitCompatibility.fastAddMotionReady();
        loopTickBatch = loopSpecialization && fastTickAction && ServerConfig.hexJitLoopTickBatch;
        batchMediaUsedStat = loopTickBatch && ServerConfig.hexJitFastSpendMediaTrigger;
        batchTickCounterWrites = reuseTickUserData && loopSpecialization && fastTickAction
                && ServerConfig.hexJitBatchTickCounterWrites;
        cacheTickStackPop = loopSpecialization && fastTickAction && ServerConfig.hexJitCacheTickStackPop;
        coalesceEvalSounds = serverThreadScope
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && ServerConfig.hexJitCoalesceEvalSounds;
        patternLookupEnabled = serverThreadScope
                && ServerConfig.hexJitMode != ServerConfig.HexJitMode.OFF
                && JitCompatibility.specialHandlerLookupReady();
        cacheNormalPatternLookup = patternLookupEnabled
                && ServerConfig.hexJitCacheNormalPatternLookup && JitCompatibility.actionPrechecksReady();
        cachePerWorldPatternLookup = patternLookupEnabled && ServerConfig.hexJitCachePerWorldPatternLookup;
        if (previous == null) {
            lastMotionPushes = 0;
            lastMotionWrites = 0;
        }
        if (serverThreadScope) serverCurrent = this; else OTHER_THREADS.set(this);
    }
    public static ExecutionScope enter(boolean batchMotion) {
        return new ExecutionScope(batchMotion);
    }
    public void startStep() { compiled = false; notifying = false; }
    public void metacastingFrame(boolean value) { metacastingFrame = value; }
    public boolean inMetacastingFrame() { return metacastingFrame; }
    public void notifying(boolean value) { notifying = value; }
    public static void markCompiled() {
        if (!ServerConfig.hexJitSkipObservers) return;
        ExecutionScope scope = current();
        if (scope != null) scope.compiled = true;
    }
    public static ExecutionScope current() {
        return HexJitRuntime.onServerThread() ? serverCurrent : OTHER_THREADS.get();
    }
    /** The caller has already established that it is on HexJit's server thread. */
    public static ExecutionScope currentOnServerThread() { return serverCurrent; }
    public static long lastMotionPushes() { return lastMotionPushes; }
    public static long lastMotionWrites() { return lastMotionWrites; }
    public void addMotionEffect(boolean value) { addMotionEffect = value; }
    public boolean inAddMotionEffect() { return addMotionEffect; }
    public boolean batchMotionEnabled() { return batchMotion; }
    public boolean coalesceDecorationsEnabled() { return coalesceDecorations; }
    public boolean reuseTickUserDataEnabled() { return reuseTickUserData; }
    public boolean cacheTickChunkEnabled() { return cacheTickChunk; }
    public boolean cacheTickBlockEligibilityEnabled() { return cacheTickBlockEligibility; }
    public boolean cacheBuddingAmethystStateEnabled() { return cacheBuddingAmethystState; }
    public boolean directTickMediaExtractionEnabled() { return directTickMediaExtraction; }
    public boolean cacheTickMediaAvailabilityEnabled() { return cacheTickMediaAvailability; }
    public boolean combineTickSideEffectsEnabled() { return combineTickSideEffects; }
    public boolean fastBuddingAmethystRandomTickEnabled() { return fastBuddingAmethystRandomTick; }
    public boolean loopSpecializationEnabled() { return loopSpecialization; }
    public boolean fastTickActionEnabled() { return fastTickAction; }
    public boolean loopTickBatchEnabled() { return loopTickBatch; }
    public boolean collectMetricsEnabled() { return collectMetrics; }
    public boolean batchTickCounterWritesEnabled() { return batchTickCounterWrites; }
    public boolean cacheTickStackPopEnabled() { return cacheTickStackPop; }
    public boolean coalesceEvalSoundsEnabled() { return coalesceEvalSounds; }
    public long registryGeneration() { return registryGeneration; }
    public boolean patternLookupEnabled() { return patternLookupEnabled; }
    public boolean cacheNormalPatternLookupEnabled() { return cacheNormalPatternLookup; }
    public boolean cachePerWorldPatternLookupEnabled() { return cachePerWorldPatternLookup; }
    public int cachedMaxOpCount() { return maxOpCount; }
    public boolean hasCachedMaxOpCount() { return hasMaxOpCount; }
    public void rememberMaxOpCount(int value) { maxOpCount = value; hasMaxOpCount = true; }

    /** Cache the strict observer/environment guard shared by Tick state reuse and empty callback skipping. */
    public boolean canMutateTickUserDataInPlace(CastingEnvironment env) {
        if (!checkedUnobservedEnvironment || unobservedEnvironment != env) {
            unobservedEnvironment = env;
            canMutateTickUserDataInPlace = FastTickAction.mayMutateExecutionState(env);
            checkedUnobservedEnvironment = true;
        }
        return canMutateTickUserDataInPlace;
    }

    public boolean canSkipEmptyCallbacks(CastingEnvironment env) { return canMutateTickUserDataInPlace(env); }

    /** Start a scoped virtual balance for HexOP's exact holder after Tick's fast-pool path is proven. */
    public boolean beginDeferredPersonalMediaWrites(Object holder) {
        if (!ServerConfig.hexJitBatchTickPersonalMediaWrites || !serverThreadScope
                || !JitCompatibility.personalMediaBatchTargetReady() || deferredPersonalMediaWritesClosed
                || holder == null) return false;
        if (deferredPersonalMediaHolder == holder) return true;
        flushDeferredPersonalMediaWrites();
        deferredPersonalMediaHolder = holder;
        deferredPersonalMediaValue = 0;
        hasDeferredPersonalMediaValue = false;
        return true;
    }

    public boolean hasDeferredPersonalMediaValue(Object holder) {
        return deferredPersonalMediaHolder == holder && hasDeferredPersonalMediaValue;
    }

    public long deferredPersonalMediaValue() { return deferredPersonalMediaValue; }

    /**
     * Match ADMediaHolder's default positive withdrawal against the already initialized cast-local
     * balance. This is only used after the personal-media event path was proven empty; the first
     * withdrawal still goes through the holder so its real balance seeds this virtual value.
     */
    public long withdrawDeferredPersonalMedia(Object holder, long amount) {
        if (amount <= 0 || !hasDeferredPersonalMediaValue(holder)) return Long.MIN_VALUE;
        long available = deferredPersonalMediaValue;
        long extracted = Math.min(amount, available);
        if (!deferPersonalMediaWrite(holder, Math.max(0L, available - amount))) return Long.MIN_VALUE;
        return extracted;
    }

    /** Seed from HexOP's normal getter once, including its tryContinue initialization behavior. */
    public void observePersonalMediaRead(Object holder, long actualValue) {
        if (deferredPersonalMediaHolder == holder && !hasDeferredPersonalMediaValue)
            storeDeferredPersonalMediaValue(actualValue);
    }

    /** Replace only the in-cast value; the mixin's matching getMedia reads see this value immediately. */
    public boolean deferPersonalMediaWrite(Object holder, long value) {
        if (deferredPersonalMediaHolder != holder || flushingDeferredPersonalMedia || holder == null) return false;
        storeDeferredPersonalMediaValue(value);
        if (collectMetrics) deferredPersonalMediaWrites++;
        return true;
    }

    private void storeDeferredPersonalMediaValue(long value) {
        // PersonalManaHolder stores a nonnegative double and getMedia converts it back to long.
        deferredPersonalMediaValue = (long) (double) Math.max(0L, value);
        hasDeferredPersonalMediaValue = true;
    }

    /** Commit the final virtual value before any callback that can observe the player's attribute. */
    public void flushDeferredPersonalMediaWrites() {
        Object holder = deferredPersonalMediaHolder;
        boolean hasValue = hasDeferredPersonalMediaValue;
        long value = deferredPersonalMediaValue;
        deferredPersonalMediaHolder = null;
        deferredPersonalMediaValue = 0;
        hasDeferredPersonalMediaValue = false;
        if (holder != null) deferredPersonalMediaWritesClosed = true;
        if (holder != null && hasValue) {
            flushingDeferredPersonalMedia = true;
            try {
                ((ADMediaHolder) holder).setMedia(value);
            } finally {
                flushingDeferredPersonalMedia = false;
            }
        }
    }

    public static long lastDeferredPersonalMediaWrites() { return lastDeferredPersonalMediaWrites; }

    /** Cache cast-stable environment checks shared by the repeated Tick media path. */
    public boolean canUseFastMediaPool(CastingEnvironment env) {
        if (!fastMediaPoolEnvironmentChecked || checkedFastMediaPoolEnvironment != env) {
            checkedFastMediaPoolEnvironment = env;
            fastMediaPoolEnvironmentEligible = FastHexOPMediaPool.supportsEnvironment(env);
            fastMediaPoolEnvironmentChecked = true;
        }
        return fastMediaPoolEnvironmentEligible;
    }

    /** Mark Tick's immutable one-element stack pop so the VM can reuse the input's validated bound. */
    public void rememberTickSubstack(TreeList<Iota> output) {
        pendingTickValidationOutput = output;
    }

    /** Consume the proof only for the exact immutable output TreeList from the preceding Tick. */
    public boolean consumeTickSubstackValidationSkip(Iterable<Iota> stack) {
        TreeList<Iota> output = pendingTickValidationOutput;
        pendingTickValidationOutput = null;
        if (output == null || stack != output) return false;
        if (collectMetrics) tickSubstackValidationSkips++;
        return true;
    }

    public static long lastTickSubstackValidationSkips() { return lastTickSubstackValidationSkips; }

    public void recordEvalSoundCopySkipped() { if (collectMetrics) evalSoundCopiesSkipped++; }
    public static long lastEvalSoundCopiesSkipped() { return lastEvalSoundCopiesSkipped; }
    public void recordEmptyPostExecutionSkipped() { if (collectMetrics) emptyPostExecutionCallsSkipped++; }
    public static long lastEmptyPostExecutionCallsSkipped() { return lastEmptyPostExecutionCallsSkipped; }
    /**
     * Keep a cast-private Tick user-data tag and reuse it while it is the VM's current tag.
     * The caller only uses this after verifying that no post-execution observer can retain an
     * intermediate CastingImage.
     */
    public CompoundTag reuseTickUserData(CompoundTag source, String tickCounterKey, String ravenmindKey) {
        if (tickUserData == source) return source;
        tickUserData = FastCompoundTagCopy.copyForTick(source, tickCounterKey, ravenmindKey);
        return tickUserData;
    }

    /** Carry the Tick count already read for media pricing to the same fast-path side effect. */
    public void prepareTickCounterUpdate(CompoundTag root, CompoundTag counterMap, BlockPos pos,
                                         String counterTag, String counterKey, int nextValue) {
        pendingTickCounterRoot = root;
        pendingTickCounterMap = counterMap;
        pendingTickCounterPos = pos;
        pendingTickCounterTag = counterTag;
        pendingTickCounterKey = counterKey;
        pendingTickCounterValue = nextValue;
        hasPreparedTickCounterUpdate = true;
    }

    /** Reuse the current Tick count while the cast-private user-data compound stays identical. */
    public int cachedTickCounterValue(CompoundTag root, CompoundTag counterMap,
                                     String counterTag, String counterKey) {
        if (!hasCachedTickCounter || cachedTickCounterRoot != root || cachedTickCounterMap != counterMap
                || cachedTickCounterTag != counterTag || cachedTickCounterKey != counterKey) {
            cachedTickCounterRoot = root;
            cachedTickCounterMap = counterMap;
            cachedTickCounterTag = counterTag;
            cachedTickCounterKey = counterKey;
            cachedTickCounterValue = counterMap.getInt(counterKey);
            cachedTickCounterMapAttached = root.get(counterTag) == counterMap;
            hasCachedTickCounter = true;
        }
        return cachedTickCounterValue;
    }

    /** Reuse the counter compound without a root-tag lookup between Tick actions in one cast. */
    public CompoundTag cachedTickCounterMap(CompoundTag root, String counterTag, String counterKey) {
        if (hasCachedTickCounter && cachedTickCounterRoot == root
                && cachedTickCounterTag == counterTag && cachedTickCounterKey == counterKey)
            return cachedTickCounterMap;
        return root.getCompound(counterTag);
    }

    /** Other actions may replace Tick's user-data compound, so forget its cached child map first. */
    public void invalidateCachedTickCounter() {
        hasCachedTickCounter = false;
        cachedTickCounterRoot = null;
        cachedTickCounterMap = null;
        cachedTickCounterTag = null;
        cachedTickCounterKey = null;
        cachedTickCounterMapAttached = false;
    }

    public void prepareTickMediaExtraction(CastingEnvironment env, long cost) {
        pendingTickMediaEnvironment = env;
        pendingTickMediaCost = cost;
        hasPendingTickMediaCost = true;
    }

    /** Returns -1 when no matching media extraction was prepared. */
    public long takePreparedTickMediaCost(CastingEnvironment env) {
        if (!hasPendingTickMediaCost) return -1;
        long cost = pendingTickMediaCost;
        boolean matches = pendingTickMediaEnvironment == env;
        pendingTickMediaEnvironment = null;
        pendingTickMediaCost = 0;
        hasPendingTickMediaCost = false;
        return matches ? cost : -1;
    }

    public boolean applyPreparedTickCounterUpdate(CompoundTag root, BlockPos pos, String counterTag,
                                                   String counterKey) {
        boolean matches = hasPreparedTickCounterUpdate && pendingTickCounterRoot == root
                && pendingTickCounterPos != null
                && (pendingTickCounterPos == pos || pendingTickCounterPos.equals(pos))
                && (counterTag == pendingTickCounterTag || counterTag.equals(pendingTickCounterTag))
                && (counterKey == pendingTickCounterKey || counterKey.equals(pendingTickCounterKey));
        if (matches) {
            boolean sameDeferredCounter = hasDeferredTickCounterWrite
                    && deferredTickCounterRoot == root && deferredTickCounterMap == pendingTickCounterMap
                    && (deferredTickCounterPos == pendingTickCounterPos
                            || deferredTickCounterPos.equals(pendingTickCounterPos))
                    && (deferredTickCounterTag == pendingTickCounterTag
                            || deferredTickCounterTag.equals(pendingTickCounterTag))
                    && (deferredTickCounterKey == pendingTickCounterKey
                            || deferredTickCounterKey.equals(pendingTickCounterKey));
            if (batchTickCounterWrites) {
                if (hasDeferredTickCounterWrite && !sameDeferredCounter) flushTickCounterWrites();
                deferredTickCounterRoot = root;
                deferredTickCounterMap = pendingTickCounterMap;
                deferredTickCounterPos = pendingTickCounterPos;
                deferredTickCounterTag = pendingTickCounterTag;
                deferredTickCounterKey = pendingTickCounterKey;
                deferredTickCounterValue = pendingTickCounterValue;
                hasDeferredTickCounterWrite = true;
                if (collectMetrics) tickCounterWritesDeferred++;
                if (hasCachedTickCounter && cachedTickCounterRoot == root
                        && cachedTickCounterMap == pendingTickCounterMap
                        && cachedTickCounterTag == pendingTickCounterTag
                        && cachedTickCounterKey == pendingTickCounterKey) {
                    cachedTickCounterValue = pendingTickCounterValue;
                }
            } else {
                pendingTickCounterMap.putInt(pendingTickCounterKey, pendingTickCounterValue);
                if (hasCachedTickCounter && cachedTickCounterRoot == root
                        && cachedTickCounterMap == pendingTickCounterMap
                        && cachedTickCounterTag == pendingTickCounterTag
                        && cachedTickCounterKey == pendingTickCounterKey) {
                    cachedTickCounterValue = pendingTickCounterValue;
                    if (!cachedTickCounterMapAttached) {
                        root.put(pendingTickCounterTag, pendingTickCounterMap);
                        cachedTickCounterMapAttached = true;
                    }
                } else {
                    root.put(pendingTickCounterTag, pendingTickCounterMap);
                }
            }
        }
        clearPreparedTickCounterUpdate();
        return matches;
    }

    /** Commit the latest logically applied Tick count before another action can observe user data. */
    public void flushTickCounterWrites() {
        if (!hasDeferredTickCounterWrite) return;
        CompoundTag root = deferredTickCounterRoot;
        CompoundTag map = deferredTickCounterMap;
        String tag = deferredTickCounterTag;
        String key = deferredTickCounterKey;
        map.putInt(key, deferredTickCounterValue);
        if (root.get(tag) != map) root.put(tag, map);
        if (hasCachedTickCounter && cachedTickCounterRoot == root && cachedTickCounterMap == map
                && cachedTickCounterTag == tag && cachedTickCounterKey == key) {
            cachedTickCounterValue = deferredTickCounterValue;
            cachedTickCounterMapAttached = true;
        }
        hasDeferredTickCounterWrite = false;
        deferredTickCounterRoot = null;
        deferredTickCounterMap = null;
        deferredTickCounterPos = null;
        deferredTickCounterTag = null;
        deferredTickCounterKey = null;
        deferredTickCounterValue = 0;
        if (collectMetrics) tickCounterWriteCommits++;
    }

    public static long lastTickCounterWritesDeferred() { return lastTickCounterWritesDeferred; }
    public static long lastTickCounterWriteCommits() { return lastTickCounterWriteCommits; }

    /** Keep one append parent per cast; only an identity-matching Tick stack may consume it. */
    public static void recordTickStackAppendOnServerThread(TreeList<?> previous, TreeList<?> appended) {
        if (!ServerConfig.hexJitCacheTickStackPop) return;
        for (ExecutionScope scope = serverCurrent; scope != null; scope = scope.previous) {
            if (!scope.cacheTickStackPop) continue;
            scope.pendingTickStackPopInput = appended;
            scope.pendingTickStackPopSource = previous;
            return;
        }
    }

    @SuppressWarnings("unchecked")
    public static TreeList<Iota> takeTickStackPopSourceOnServerThread(TreeList<Iota> input) {
        for (ExecutionScope scope = serverCurrent; scope != null; scope = scope.previous) {
            if (!scope.cacheTickStackPop || scope.pendingTickStackPopInput != input) continue;
            TreeList<?> source = scope.pendingTickStackPopSource;
            scope.pendingTickStackPopInput = null;
            scope.pendingTickStackPopSource = null;
            if (source == null) return null;
            if (scope.collectMetrics) scope.tickStackPopCacheHits++;
            return (TreeList<Iota>) source;
        }
        return null;
    }

    public static long lastTickStackPopCacheHits() { return lastTickStackPopCacheHits; }

    /** Skip only a previously successful same-target check for a standard, unobserved player cast. */
    public boolean skipCachedBuddingAmethystRangeCheck(CastingEnvironment env, BlockPos pos) {
        tickRangeCacheEligibleForCurrentAction = false;
        if (!serverThreadScope || !ServerConfig.hexJitCacheTickRangeCheck
                || !buddingRangeGuardEligible(env)) {
            invalidateTickRangeCheck();
            return false;
        }
        if (cachedTickRangeEnvironment == env && cachedTickRangePos != null
                && (cachedTickRangePos == pos || cachedTickRangePos.equals(pos))) {
            // prepareTick and TickSpell.cast run synchronously for this action. Remember the
            // already checked environment guard so the successful-range cache below does not
            // scan the same observer lists and ClassValue a second time for every repeated Tick.
            tickRangeCacheEligibleForCurrentAction = true;
            if (collectMetrics) tickRangeCheckCacheHits++;
            return true;
        }
        invalidateTickRangeCheck();
        tickRangeCacheEligibleForCurrentAction = true;
        return false;
    }

    /** Arm the shortcut only after the real check succeeded and the target was confirmed budding amethyst. */
    public void rememberBuddingAmethystRangeCheck(CastingEnvironment env, BlockPos pos) {
        boolean eligible = tickRangeCacheEligibleForCurrentAction;
        tickRangeCacheEligibleForCurrentAction = false;
        if (!eligible || !serverThreadScope || !ServerConfig.hexJitCacheTickRangeCheck) {
            invalidateTickRangeCheck();
            return;
        }
        cachedTickRangeEnvironment = env;
        cachedTickRangePos = pos.immutable();
    }

    public void invalidateTickRangeCheck() {
        cachedTickRangeEnvironment = null;
        cachedTickRangePos = null;
        tickRangeCacheEligibleForCurrentAction = false;
        checkedTickRangeGuardEnvironment = null;
        tickRangeGuardChecked = false;
        tickRangeGuardEligible = false;
    }

    private boolean buddingRangeGuardEligible(CastingEnvironment env) {
        if (!tickRangeGuardChecked || checkedTickRangeGuardEnvironment != env) {
            checkedTickRangeGuardEnvironment = env;
            tickRangeGuardEligible = FastTickAction.mayCacheBuddingAmethystRangeCheck(env);
            tickRangeGuardChecked = true;
        }
        return tickRangeGuardEligible;
    }

    public static long lastTickRangeCheckCacheHits() { return lastTickRangeCheckCacheHits; }

    private void clearPreparedTickCounterUpdate() {
        pendingTickCounterRoot = null;
        pendingTickCounterMap = null;
        pendingTickCounterPos = null;
        pendingTickCounterKey = null;
        pendingTickCounterTag = null;
        pendingTickCounterValue = 0;
        hasPreparedTickCounterUpdate = false;
    }

    /** Reuse the immutable BlockPos derived from a repeated Tick vector iota. */
    public BlockPos cachedTickTarget(Iota target) {
        if (tickTargetIota == target && tickTargetPos != null) return tickTargetPos;
        if (!(target instanceof Vec3Iota vector)) return null;
        if (tickTargetIota != target || tickTargetPos == null) {
            tickTargetIota = target;
            tickTargetPos = BlockPos.containing(vector.getVec3());
        }
        return tickTargetPos;
    }

    /** Hold target-specific Tick actions for this cast, avoiding global cache probes per loop turn. */
    public OpTick.FastTickAssets cachedFastTickAssets(BlockPos pos) {
        return fastTickAssets != null && (fastTickAssetsPos == pos || fastTickAssetsPos.equals(pos))
                ? fastTickAssets : null;
    }

    public void rememberFastTickAssets(BlockPos pos, OpTick.FastTickAssets assets) {
        fastTickAssetsPos = pos.immutable();
        fastTickAssets = assets;
    }

    /** Reuse each metacast suffix's immutable successor and cast-local continuation pair. */
    public SpellContinuation cachedFrameLoopContinuation(TreeList<Iota> source, SpellContinuation parent) {
        TreeListLoopCacheAccess cache = (TreeListLoopCacheAccess) (Object) source;
        FrameEvaluate successor = cache.cmi$getLoopSuccessor();
        if (successor == null) {
            TreeList<Iota> tail = source.tail();
            if (tail.isEmpty()) return parent;
            successor = new FrameEvaluate(tail, true);
            cache.cmi$setLoopSuccessor(successor);
        } else {
            if (collectMetrics) {
                frameLoopTailHits++;
                frameLoopFrameHits++;
            }
        }

        if (cache.cmi$getLoopScope() == this && cache.cmi$getLoopParent() == parent) {
            if (collectMetrics) frameLoopContinuationHits++;
            return cache.cmi$getLoopContinuation();
        }
        boolean registerForCleanup = cache.cmi$getLoopScope() != this;
        SpellContinuation next = parent.pushFrame(successor);
        cache.cmi$setLoopContinuation(this, parent, next);
        if (registerForCleanup) {
            if (frameLoopContinuationSources == null) frameLoopContinuationSources = new ArrayList<>();
            frameLoopContinuationSources.add(source);
        }
        return next;
    }

    /** Hold a folded Tick outcome without allocating its short-lived CastResult wrapper. */
    public void preparePendingLoopTickResult(PatternIota pattern, SpellContinuation continuation,
                                             CastingImage inputImage, TreeList<Iota> outputStack,
                                             CompoundTag userData, List<OperatorSideEffect> sideEffects,
                                             EvalSound sound) {
        pendingLoopTickPattern = pattern;
        pendingLoopTickContinuation = continuation;
        pendingLoopTickInputImage = inputImage;
        pendingLoopTickOutputStack = outputStack;
        pendingLoopTickUserData = userData;
        pendingLoopTickSideEffects = sideEffects;
        pendingLoopTickSound = sound;
    }

    public boolean hasPendingLoopTickResult() { return pendingLoopTickInputImage != null; }
    public CastingImage pendingLoopTickInputImage() { return pendingLoopTickInputImage; }
    public TreeList<Iota> pendingLoopTickOutputStack() { return pendingLoopTickOutputStack; }
    public CompoundTag pendingLoopTickUserData() { return pendingLoopTickUserData; }
    public SpellContinuation pendingLoopTickContinuation() { return pendingLoopTickContinuation; }
    public PatternIota pendingLoopTickPattern() { return pendingLoopTickPattern; }
    public List<OperatorSideEffect> pendingLoopTickSideEffects() { return pendingLoopTickSideEffects; }

    /** Rebuild the ordinary VM-visible result if this optimized Tick is the end of its run. */
    public CastResult finishPendingLoopTickResult() {
        if (!hasPendingLoopTickResult()) return null;
        CastingImage inputImage = pendingLoopTickInputImage;
        CastingImage outputImage = new CastingImage(pendingLoopTickOutputStack,
                inputImage.getParenCount(), inputImage.getParenthesized(), inputImage.getEscapeNext(),
                inputImage.getSimulateNext(), inputImage.getOpsConsumed() + 1, pendingLoopTickUserData);
        CastResult result = new CastResult(pendingLoopTickPattern, pendingLoopTickContinuation,
                outputImage, pendingLoopTickSideEffects, ResolvedPatternType.EVALUATED,
                pendingLoopTickSound);
        clearPendingLoopTickResult();
        return result;
    }

    public void clearPendingLoopTickResult() {
        pendingLoopTickInputImage = null;
        pendingLoopTickOutputStack = null;
        pendingLoopTickUserData = null;
        pendingLoopTickContinuation = null;
        pendingLoopTickPattern = null;
        pendingLoopTickSideEffects = null;
        pendingLoopTickSound = null;
    }

    public static long lastFrameLoopTailHits() { return lastFrameLoopTailHits; }
    public static long lastFrameLoopFrameHits() { return lastFrameLoopFrameHits; }
    public static long lastFrameLoopContinuationHits() { return lastFrameLoopContinuationHits; }
    public void recordLoopTickDispatch() { if (collectMetrics) loopTickDispatches++; }
    public static long lastLoopTickDispatches() { return lastLoopTickDispatches; }
    public static long lastLoopTickRuns() { return lastLoopTickRuns; }
    public static long lastLongestLoopTickRun() { return lastLongestLoopTickRun; }
    public static long lastLoopTickBatchFolds() { return lastLoopTickBatchFolds; }
    public static long lastSpendMediaTriggerCacheHits() { return lastSpendMediaTriggerCacheHits; }
    public void recordLoopTickBatchFold() { if (collectMetrics) loopTickBatchFolds++; }
    public void recordFoldedBuddingAmethystAction() { if (collectMetrics) foldedBuddingAmethystActions++; }
    public static long lastFoldedBuddingAmethystActions() { return lastFoldedBuddingAmethystActions; }
    public void recordFoldedBuddingAmethystTick() { if (collectMetrics) foldedBuddingAmethystTickCalls++; }
    public static long lastFoldedBuddingAmethystTickCalls() { return lastFoldedBuddingAmethystTickCalls; }

    /** Mark exactly the next AttemptSpell as the already prepared folded loop Tick. */
    public void beginFoldedLoopTickEffect() { foldedLoopTickEffectPending = true; }
    public boolean consumeFoldedLoopTickEffect() {
        boolean pending = foldedLoopTickEffectPending;
        foldedLoopTickEffectPending = false;
        return pending;
    }
    public void endFoldedLoopTickEffect() { foldedLoopTickEffectPending = false; }

    public void loopTickDispatchActive(boolean value) { loopTickDispatchActive = value; }
    public boolean loopTickDispatchActive() { return loopTickDispatchActive; }
    public void loopTickDispatchCompleted(boolean tick) {
        if (collectMetrics) {
            if (tick) {
                if (!previousLoopTickDispatchWasTick) loopTickRuns++;
                activeLoopTickRun++;
                longestLoopTickRun = Math.max(longestLoopTickRun, activeLoopTickRun);
            } else {
                activeLoopTickRun = 0;
            }
        }
        previousLoopTickDispatchWasTick = tick;
    }
    public void invalidateLoopTickParticleColor() {
        loopTickDispatchCompleted(false);
        flushDeferredMediaUsedStat();
        invalidateLoopTickActionPrecheck();
        invalidateTickRangeCheck();
        invalidateSpendMediaTriggerCache();
    }

    /** Batch the scalar stat update during Tick-only runs; advancement hooks flush before rewards. */
    public boolean deferMediaUsedStat(ServerPlayer player, int amount) {
        if (!batchMediaUsedStat || player == null) return false;
        if (hasDeferredMediaUsedStat && deferredMediaUsedStatPlayer != player)
            flushDeferredMediaUsedStat();
        deferredMediaUsedStatPlayer = player;
        deferredMediaUsedStat += amount;
        hasDeferredMediaUsedStat = true;
        return true;
    }

    public void flushDeferredMediaUsedStat() {
        if (!hasDeferredMediaUsedStat) return;
        ServerPlayer player = deferredMediaUsedStatPlayer;
        int amount = deferredMediaUsedStat;
        deferredMediaUsedStatPlayer = null;
        deferredMediaUsedStat = 0;
        hasDeferredMediaUsedStat = false;
        player.awardStat(HexStatistics.MEDIA_USED, amount);
    }

    public SpendMediaTrigger cachedSpendMediaTrigger() { return cachedSpendMediaTrigger; }

    public void rememberSpendMediaTrigger(SpendMediaTrigger trigger) {
        if (loopTickBatch) cachedSpendMediaTrigger = trigger;
    }

    public SpendMediaListenerRule[] cachedSpendMediaListenerRules(SpendMediaTrigger trigger, ServerPlayer player) {
        if (!loopTickBatch || !hasCachedSpendMediaListenerRules
                || cachedSpendMediaRulesTrigger != trigger
                || cachedSpendMediaRulesPlayer != player) return null;
        if (collectMetrics) spendMediaTriggerCacheHits++;
        return cachedSpendMediaListenerRules;
    }

    public void rememberSpendMediaListenerRules(SpendMediaTrigger trigger, ServerPlayer player,
                                                SpendMediaListenerRule[] rules) {
        if (!loopTickBatch) return;
        cachedSpendMediaRulesTrigger = trigger;
        cachedSpendMediaRulesPlayer = player;
        cachedSpendMediaListenerRules = rules;
        hasCachedSpendMediaListenerRules = true;
    }

    public void invalidateSpendMediaTriggerCache() {
        cachedSpendMediaRulesTrigger = null;
        cachedSpendMediaRulesPlayer = null;
        cachedSpendMediaListenerRules = null;
        hasCachedSpendMediaListenerRules = false;
    }

    public EvalSound loopTickHermesSound() {
        if (cachedLoopTickHermesSound == null)
            cachedLoopTickHermesSound = at.petrak.hexcasting.common.lib.hex.HexEvalSounds.HERMES.get();
        return cachedLoopTickHermesSound;
    }

    public EvalSound loopTickMishapSound() {
        if (cachedLoopTickMishapSound == null)
            cachedLoopTickMishapSound = at.petrak.hexcasting.common.lib.hex.HexEvalSounds.MISHAP.get();
        return cachedLoopTickMishapSound;
    }

    /** The direct loop has no intervening actions, so repeated Tick decorations keep the same pigment. */
    public boolean isDuplicateLoopTickParticle(ParticleSpray spray) {
        return loopTickDispatchActive && previousLoopTickDispatchWasTick && hasLastParticle
                && (spray == lastSpray || spray.equals(lastSpray));
    }

    /**
     * Sample the same per-call Bernoulli probability with geometrically spaced successes. The
     * private RNG keeps the gate cheap between random ticks; seeding it advances world RNG only
     * once per cast/target, so enabling this optimization intentionally changes the RNG stream.
     */
    public boolean shouldRunBuddingAmethystRandomTick(Level level, BlockPos pos, int probability) {
        if (probability <= 1) {
            if (collectMetrics) buddingAmethystRandomTickCalls++;
            return true;
        }
        if (buddingAmethystGateRandom == null || buddingAmethystGateLevel != level
                || buddingAmethystGatePos == null || !buddingAmethystGatePos.equals(pos)
                || buddingAmethystGateProbability != probability) {
            buddingAmethystGateLevel = level;
            buddingAmethystGatePos = pos.immutable();
            buddingAmethystGateProbability = probability;
            buddingAmethystGateRandom = new Random(level.getRandom().nextLong());
            buddingAmethystTicksUntilTick = sampleGeometricFailures(probability);
        }
        if (buddingAmethystTicksUntilTick > 0) {
            buddingAmethystTicksUntilTick--;
            return false;
        }
        if (collectMetrics) buddingAmethystRandomTickCalls++;
        buddingAmethystTicksUntilTick = sampleGeometricFailures(probability);
        return true;
    }

    /** Reuse the fixed budding-amethyst state while no other spell effect can change the target. */
    public BlockState cachedBuddingAmethystState(Level level, BlockPos pos) {
        if (cachedBuddingState == null) return null;
        if (cachedBuddingStateLevel != level || cachedBuddingStatePos == null
                || cachedBuddingStatePos != pos && !cachedBuddingStatePos.equals(pos)) {
            // A Tick at another position may random-tick an adjacent budding amethyst and change
            // this target, so a position switch ends the cached-state interval.
            invalidateBuddingAmethystState();
            return null;
        }
        return cachedBuddingState;
    }

    public void rememberBuddingAmethystState(Level level, BlockPos pos, BlockState state) {
        if (!state.is(Blocks.BUDDING_AMETHYST)) return;
        cachedBuddingStateLevel = level;
        cachedBuddingStatePos = pos.immutable();
        cachedBuddingState = state;
    }

    /** Batch only the random-tick block effects; media and per-cast Tick counters remain ordered. */
    public void deferBuddingAmethystRandomTick(ServerLevel level, BlockPos pos, BlockState state) {
        if (pendingBuddingRandomTicks != 0
                && (pendingBuddingTickLevel != level || !pendingBuddingTickPos.equals(pos))) {
            flushBuddingAmethystRandomTicks();
        }
        pendingBuddingTickLevel = level;
        pendingBuddingTickPos = pos;
        pendingBuddingTickState = state;
        pendingBuddingRandomTicks++;
    }

    /** Apply queued random ticks before any other world-mutating spell effect or postCast. */
    public void flushBuddingAmethystRandomTicks() {
        int ticks = pendingBuddingRandomTicks;
        if (ticks == 0) return;
        ServerLevel level = pendingBuddingTickLevel;
        BlockPos pos = pendingBuddingTickPos;
        BlockState state = pendingBuddingTickState;
        pendingBuddingRandomTicks = 0;
        pendingBuddingTickLevel = null;
        pendingBuddingTickPos = null;
        pendingBuddingTickState = null;
        if (level == null || pos == null || state == null || !state.is(Blocks.BUDDING_AMETHYST)) return;
        for (int tick = 0; tick < ticks; tick++)
            state.randomTick(level, pos, level.getRandom());
    }

    public void invalidateBuddingAmethystState() {
        cachedBuddingStateLevel = null;
        cachedBuddingStatePos = null;
        cachedBuddingState = null;
    }

    public static long lastBuddingAmethystRandomTickCalls() { return lastBuddingAmethystRandomTickCalls; }

    private int sampleGeometricFailures(int probability) {
        double unit = buddingAmethystGateRandom.nextDouble();
        return (int) Math.floor(Math.log1p(-unit) / Math.log1p(-1.0 / probability));
    }

    /** Cache only the chunk wrapper; callers still read its live block state on every Tick. */
    public LevelChunk cachedTickChunk(Level level, BlockPos pos) {
        int x = pos.getX() >> 4;
        int z = pos.getZ() >> 4;
        LevelChunk chunk = tickChunk;
        if (chunk == null || tickChunkLevel != level || tickChunkX != x || tickChunkZ != z) {
            chunk = level.getChunkAt(pos);
            tickChunkLevel = level;
            tickChunkX = x;
            tickChunkZ = z;
            tickChunk = chunk;
        }
        return chunk;
    }

    /** The deny-list depends on the block identity and cannot change during this synchronous cast. */
    public Boolean cachedTickBlockEligibility(Block block) {
        return tickEligibilityBlock == block ? tickBlockEligibility : null;
    }

    public void rememberTickBlockEligibility(Block block, boolean allowed) {
        tickEligibilityBlock = block;
        tickBlockEligibility = allowed;
    }


    /**
     * Reuse a decoded StaffCastEnv pigment while the exact stored NBT remains equal. Pigment
     * changes during a cast invalidate this cache before a particle is emitted.
     */
    public FrozenPigment getPigment(CastingEnvironment env) {
        if (!coalesceDecorations
                || !(env instanceof StaffCastEnv) || !STANDARD_STAFF_PIGMENT.get(env.getClass())
                || !(env.getCastingEntity() instanceof Player player)) return env.getPigment();
        Tag currentTag = player.getPersistentData().get(TAG_PIGMENT);
        boolean sameStoredTag = currentTag == null
                ? pigmentTagSnapshot == null
                : currentTag.equals(pigmentTagSnapshot);
        if (player == pigmentPlayer && sameStoredTag) return castPigment;
        FrozenPigment pigment = env.getPigment();
        pigmentPlayer = player;
        pigmentTagSnapshot = currentTag == null ? null : currentTag.copy();
        castPigment = pigment;
        return pigment;
    }

    /** Cache a few immutable tag answers used repeatedly by the current cast's action checks. */
    public Boolean cachedTagMembership(ResourceKey<?> registry, ResourceLocation location, TagKey<?> tag) {
        TagLookup recent = lastTagLookup;
        if (recent != null && recent.matches(registry, location, tag)) return recent.value();
        if (tagLookups == null) return null;
        for (TagLookup lookup : tagLookups) {
            if (lookup != null && lookup != recent && lookup.matches(registry, location, tag))
                return lookup.value();
        }
        return null;
    }

    public void rememberTagMembership(ResourceKey<?> registry, ResourceLocation location,
                                      TagKey<?> tag, boolean value) {
        if (tagLookups == null) tagLookups = new TagLookup[4];
        for (int index = 0; index < tagLookups.length; index++) {
            TagLookup lookup = tagLookups[index];
            if (lookup != null && lookup.matches(registry, location, tag)) {
                TagLookup updated = new TagLookup(registry, location, tag, value);
                tagLookups[index] = updated;
                lastTagLookup = updated;
                return;
            }
        }
        TagLookup added = new TagLookup(registry, location, tag, value);
        tagLookups[nextTagLookup] = added;
        lastTagLookup = added;
        nextTagLookup = (nextTagLookup + 1) % tagLookups.length;
    }

    /** Successful standard action prechecks have stable cost modifiers for this cast. */
    public double cachedActionCostModifier(ResourceKey<?> actionKey) {
        if (actionPrecheckKeys == null) return Double.NaN;
        for (int index = 0; index < actionPrecheckKeys.length; index++) {
            if (actionPrecheckKeys[index] == actionKey) return actionPrecheckCostModifiers[index];
        }
        return Double.NaN;
    }

    public void rememberActionCostModifier(ResourceKey<?> actionKey, double costModifier) {
        if (actionPrecheckKeys == null) {
            actionPrecheckKeys = new ResourceKey<?>[8];
            actionPrecheckCostModifiers = new double[8];
        }
        for (int index = 0; index < actionPrecheckKeys.length; index++) {
            if (actionPrecheckKeys[index] == actionKey) {
                actionPrecheckCostModifiers[index] = costModifier;
                return;
            }
        }
        actionPrecheckKeys[nextActionPrecheck] = actionKey;
        actionPrecheckCostModifiers[nextActionPrecheck] = costModifier;
        nextActionPrecheck = (nextActionPrecheck + 1) % actionPrecheckKeys.length;
    }

    /** Reuse the exact Tick action's successful precheck inside a metacasting loop. */
    public double cachedLoopTickActionCostModifier(ResourceKey<?> actionKey) {
        if (!hasLoopTickActionCostModifier || loopTickActionPrecheckKey != actionKey) return Double.NaN;
        if (collectMetrics) loopTickActionPrecheckHits++;
        return loopTickActionCostModifier;
    }

    public boolean canReuseLoopTickActionPrecheck(ResourceKey<?> actionKey) {
        return loopTickBatch && hasLoopTickActionCostModifier && loopTickActionPrecheckKey == actionKey;
    }

    public void recordLoopTickActionPrecheckHit() {
        if (collectMetrics) loopTickActionPrecheckHits++;
    }

    public void invalidateLoopTickActionPrecheck() {
        hasLoopTickActionCostModifier = false;
        loopTickActionPrecheckKey = null;
        loopTickActionCostModifier = 0;
    }

    public void rememberLoopTickActionCostModifier(ResourceKey<?> actionKey, double costModifier) {
        loopTickActionPrecheckKey = actionKey;
        loopTickActionCostModifier = costModifier;
        hasLoopTickActionCostModifier = true;
    }

    public static long lastLoopTickActionPrecheckHits() { return lastLoopTickActionPrecheckHits; }

    /** Preserve each Vec3.add rounding step while deferring only Entity's field writes and allocations. */
    public void accumulateMotion(Entity entity, double x, double y, double z) {
        MotionState state = motionState();
        PendingMotion pending = state.get(entity);
        if (pending == null) {
            Vec3 current = entity.getDeltaMovement();
            pending = new PendingMotion(current.x, current.y, current.z);
            state.put(entity, pending);
        }
        pending.add(x, y, z);
        if (collectMetrics) state.pushes++;
    }

    /** Null means no buffered motion exists for this entity. The returned vector keeps stable identity until a push. */
    public Vec3 pendingMotion(Entity entity) {
        MotionState state = motions;
        PendingMotion pending = state == null ? null : state.get(entity);
        return pending == null ? null : pending.view();
    }

    /** Commit before an explicit setter or a method that reads Entity's private velocity field directly. */
    public void flushMotion(Entity entity) {
        MotionState state = motions;
        if (state == null) return;
        PendingMotion pending = state.remove(entity);
        if (pending != null) {
            entity.setDeltaMovement(pending.view());
            if (collectMetrics) state.writes++;
        }
    }

    public void flushAllMotion() {
        MotionState state = motions;
        while (state != null && state.hasPending()) flushMotion(state.nextEntity());
    }

    public static boolean maySkip() {
        ExecutionScope scope = current();
        return scope != null && scope.compiled && scope.notifying;
    }

    /** Returns true when this exact spray/pigment pair has already been emitted in this cast. */
    public boolean isDuplicateParticle(ParticleSpray spray, FrozenPigment pigment) {
        return hasLastParticle && (spray == lastSpray || spray.equals(lastSpray))
                && (pigment == lastFrozenPigment || lastPigment.matches(pigment));
    }

    /** Returns true when this exact spray/pigment pair should still be sent to clients. */
    public boolean emitParticle(ParticleSpray spray, FrozenPigment pigment) {
        if (!coalesceDecorations) return true;
        if (hasLastParticle && spray.equals(lastSpray) && lastPigment.matches(pigment)) {
            lastFrozenPigment = pigment;
            return false;
        }
        if (emittedParticles == null) emittedParticles = new HashMap<>();
        List<PigmentSnapshot> colors = emittedParticles.get(spray);
        if (colors != null) {
            for (PigmentSnapshot color : colors) {
                if (color.matches(pigment)) {
                    lastSpray = spray;
                    lastPigment = color;
                    lastFrozenPigment = pigment;
                    hasLastParticle = true;
                    return false;
                }
            }
        } else {
            colors = new ArrayList<>(1);
            emittedParticles.put(spray, colors);
        }
        PigmentSnapshot snapshot = PigmentSnapshot.of(pigment);
        colors.add(snapshot);
        lastSpray = spray;
        lastPigment = snapshot;
        lastFrozenPigment = pigment;
        hasLastParticle = true;
        return true;
    }

    private record PigmentSnapshot(ItemStack item, UUID owner, boolean isNull) {
        private static PigmentSnapshot of(FrozenPigment pigment) {
            return pigment == null ? new PigmentSnapshot(null, null, true)
                    : new PigmentSnapshot(pigment.item().copy(), pigment.owner(), false);
        }
        private boolean matches(FrozenPigment pigment) {
            if (isNull) return pigment == null;
            return pigment != null && owner.equals(pigment.owner()) && ItemStack.matches(item, pigment.item());
        }
    }

    @Override public void close() {
        if (Thread.currentThread() != ownerThread) return;
        try {
            flushDeferredPersonalMediaWrites();
            flushDeferredMediaUsedStat();
            flushTickCounterWrites();
            if (previous == null) flushAllMotion();
            if (previous == null) flushBuddingAmethystRandomTicks();
            if (emittedParticles != null) emittedParticles.clear();
            tickUserData = null;
            cachedTickCounterRoot = null;
            cachedTickCounterMap = null;
            cachedTickCounterTag = null;
            cachedTickCounterKey = null;
            hasCachedTickCounter = false;
            cachedTickCounterMapAttached = false;
            hasMaxOpCount = false;
            pendingTickValidationOutput = null;
            clearPendingLoopTickResult();
            clearPreparedTickCounterUpdate();
            pendingTickMediaEnvironment = null;
            pendingTickMediaCost = 0;
            hasPendingTickMediaCost = false;
            cachedSpendMediaTrigger = null;
            invalidateSpendMediaTriggerCache();
            cachedLoopTickHermesSound = null;
            cachedLoopTickMishapSound = null;
            checkedFastMediaPoolEnvironment = null;
            fastMediaPoolEnvironmentChecked = false;
            fastMediaPoolEnvironmentEligible = false;
            tickChunkLevel = null;
            tickChunk = null;
            fastTickAssetsPos = null;
            fastTickAssets = null;
            tagLookups = null;
            lastTagLookup = null;
            actionPrecheckKeys = null;
            actionPrecheckCostModifiers = null;
            loopTickActionPrecheckKey = null;
            hasLoopTickActionCostModifier = false;
            loopTickDispatchActive = false;
            previousLoopTickDispatchWasTick = false;
            foldedLoopTickEffectPending = false;
            tickEligibilityBlock = null;
            tickBlockEligibility = null;
            cachedBuddingStateLevel = null;
            cachedBuddingStatePos = null;
            cachedBuddingState = null;
            pendingBuddingTickLevel = null;
            pendingBuddingTickPos = null;
            pendingBuddingTickState = null;
            pendingBuddingRandomTicks = 0;
            buddingAmethystGateLevel = null;
            buddingAmethystGatePos = null;
            buddingAmethystGateRandom = null;
            pigmentPlayer = null;
            pigmentTagSnapshot = null;
            castPigment = null;
            lastFrozenPigment = null;
            unobservedEnvironment = null;
        } finally {
            if (frameLoopContinuationSources != null) {
                for (TreeList<Iota> source : frameLoopContinuationSources) {
                    ((TreeListLoopCacheAccess) (Object) source).cmi$clearLoopContinuation(this);
                }
                frameLoopContinuationSources = null;
            }
            FastHexOPMediaPool.endCast(this);
            if (previous == null) {
                lastFrameLoopTailHits = collectMetrics ? frameLoopTailHits : 0;
                lastFrameLoopFrameHits = collectMetrics ? frameLoopFrameHits : 0;
                lastFrameLoopContinuationHits = collectMetrics ? frameLoopContinuationHits : 0;
                lastLoopTickDispatches = collectMetrics ? loopTickDispatches : 0;
                lastLoopTickRuns = collectMetrics ? loopTickRuns : 0;
                lastLongestLoopTickRun = collectMetrics ? longestLoopTickRun : 0;
                lastLoopTickBatchFolds = collectMetrics ? loopTickBatchFolds : 0;
                lastFoldedBuddingAmethystActions = collectMetrics ? foldedBuddingAmethystActions : 0;
                lastFoldedBuddingAmethystTickCalls = collectMetrics ? foldedBuddingAmethystTickCalls : 0;
                lastSpendMediaTriggerCacheHits = collectMetrics ? spendMediaTriggerCacheHits : 0;
                lastTickSubstackValidationSkips = collectMetrics ? tickSubstackValidationSkips : 0;
                lastLoopTickActionPrecheckHits = collectMetrics ? loopTickActionPrecheckHits : 0;
                lastEvalSoundCopiesSkipped = collectMetrics ? evalSoundCopiesSkipped : 0;
                lastEmptyPostExecutionCallsSkipped = collectMetrics ? emptyPostExecutionCallsSkipped : 0;
                lastBuddingAmethystRandomTickCalls = collectMetrics ? buddingAmethystRandomTickCalls : 0;
                lastMotionPushes = !collectMetrics || motions == null ? 0 : motions.pushes;
                lastMotionWrites = !collectMetrics || motions == null ? 0 : motions.writes;
                lastDeferredPersonalMediaWrites = collectMetrics ? deferredPersonalMediaWrites : 0;
                lastTickCounterWritesDeferred = collectMetrics ? tickCounterWritesDeferred : 0;
                lastTickCounterWriteCommits = collectMetrics ? tickCounterWriteCommits : 0;
                lastTickStackPopCacheHits = collectMetrics ? tickStackPopCacheHits : 0;
                lastTickRangeCheckCacheHits = collectMetrics ? tickRangeCheckCacheHits : 0;
            } else {
                previous.buddingAmethystRandomTickCalls += buddingAmethystRandomTickCalls;
                previous.tickStackPopCacheHits += tickStackPopCacheHits;
                previous.tickRangeCheckCacheHits += tickRangeCheckCacheHits;
            }
            pendingTickStackPopInput = null;
            pendingTickStackPopSource = null;
            if (serverThreadScope) {
                serverCurrent = previous;
            } else if (previous == null) {
                OTHER_THREADS.remove();
            } else {
                OTHER_THREADS.set(previous);
            }
        }
    }

    public record SpendMediaListenerRule(CriterionTrigger.Listener<?> listener,
                                         long minSpent, long maxSpent,
                                         long minWasted, long maxWasted) {
        public static SpendMediaListenerRule from(CriterionTrigger.Listener<?> listener, Instance instance) {
            MinMaxLongs spent = instance.mediaSpent();
            MinMaxLongs wasted = instance.mediaWasted();
            return new SpendMediaListenerRule(listener,
                    spent.min().orElse(Long.MIN_VALUE), spent.max().orElse(Long.MAX_VALUE),
                    wasted.min().orElse(Long.MIN_VALUE), wasted.max().orElse(Long.MAX_VALUE));
        }

        public boolean matches(long mediaSpent, long mediaWasted) {
            return mediaSpent >= minSpent && mediaSpent <= maxSpent
                    && mediaWasted >= minWasted && mediaWasted <= maxWasted;
        }
    }

    private record TagLookup(ResourceKey<?> registry, ResourceLocation location, TagKey<?> tag, boolean value) {
        private boolean matches(ResourceKey<?> registry, ResourceLocation location, TagKey<?> tag) {
            return (this.registry == registry || this.registry.equals(registry))
                    && (this.location == location || this.location.equals(location))
                    && (this.tag == tag || this.tag.equals(tag));
        }
    }

    private MotionState motionState() {
        MotionState state = motions;
        if (state == null) {
            state = new MotionState();
            for (ExecutionScope scope = this; scope != null; scope = scope.previous) scope.motions = state;
        }
        return state;
    }

    private static final class MotionState {
        private Entity singleEntity;
        private PendingMotion singlePending;
        private IdentityHashMap<Entity, PendingMotion> additional;
        private long pushes;
        private long writes;

        private PendingMotion get(Entity entity) {
            if (singleEntity == entity) return singlePending;
            return additional == null ? null : additional.get(entity);
        }
        private void put(Entity entity, PendingMotion motion) {
            if (singleEntity == entity) {
                singlePending = motion;
            } else if (additional != null && additional.containsKey(entity)) {
                additional.put(entity, motion);
            } else if (singlePending == null) {
                singleEntity = entity;
                singlePending = motion;
            } else {
                if (additional == null) additional = new IdentityHashMap<>();
                additional.put(entity, motion);
            }
        }
        private PendingMotion remove(Entity entity) {
            if (singleEntity == entity) {
                PendingMotion result = singlePending;
                singleEntity = null;
                singlePending = null;
                return result;
            }
            if (additional == null) return null;
            PendingMotion result = additional.remove(entity);
            if (additional.isEmpty()) additional = null;
            return result;
        }
        private boolean hasPending() { return singlePending != null || additional != null && !additional.isEmpty(); }
        private Entity nextEntity() {
            return singlePending != null ? singleEntity : additional.keySet().iterator().next();
        }
    }

    private static final class PendingMotion {
        private double x, y, z;
        private Vec3 view;
        private PendingMotion(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        private void add(double dx, double dy, double dz) {
            x += dx;
            y += dy;
            z += dz;
            view = null;
        }
        private Vec3 view() {
            if (view == null) view = new Vec3(x, y, z);
            return view;
        }
    }
}
