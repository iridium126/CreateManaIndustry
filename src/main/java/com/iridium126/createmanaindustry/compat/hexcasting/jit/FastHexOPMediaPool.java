package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.addldata.ADMediaHolder;
import at.petrak.hexcasting.api.advancements.HexAdvancementTriggers;
import at.petrak.hexcasting.api.advancements.SpendMediaTrigger;
import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedCastEnv;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.mod.HexStatistics;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

/** Uses HexOP's first-priority personal pool without scanning unrelated inventory slots. */
public final class FastHexOPMediaPool {
    private static final String PERSONAL_MEDIA_HOLDER_CLASS =
            "io.yukkuric.hexop.personal_mana.PersonalManaHolder";
    private static volatile Pending serverPending;
    private static final ThreadLocal<Pending> OTHER_THREADS = new ThreadLocal<>();
    private static long lastDirectExtractions;
    private static final ClassValue<Boolean> STANDARD_STAFF_ENVIRONMENT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("postExecution", at.petrak.hexcasting.api.casting.eval.CastResult.class)
                                .getDeclaringClass() == StaffCastEnv.class
                        && type.getMethod("extractMediaEnvironment", long.class, boolean.class)
                                .getDeclaringClass() == StaffCastEnv.class
                        && declaringClass(type, "extractMediaFromInventory", long.class,
                                boolean.class, boolean.class) == PlayerBasedCastEnv.class
                        && declaringClass(type, "canOvercast") == PlayerBasedCastEnv.class;
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
    };
    private static volatile Access access;
    private static volatile boolean accessResolved;

    private static Class<?> declaringClass(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                return method.getDeclaringClass();
            } catch (NoSuchMethodException ignored) {
                // Continue with the superclass implementation.
            }
        }
        return null;
    }

    private FastHexOPMediaPool() {}

    public static boolean supportsEnvironment(CastingEnvironment env) {
        return env instanceof StaffCastEnv
                && (env.getClass() == StaffCastEnv.class || env instanceof TickStateReuseEnvironment)
                && env instanceof CastingEnvironmentObserverAccess hooks
                && STANDARD_STAFF_ENVIRONMENT.get(env.getClass())
                && hooks.cmi$getPostExecutions().isEmpty()
                && hooks.cmi$getPreMediaExtract().isEmpty()
                && hooks.cmi$getPostMediaExtract().isEmpty()
                && env.getCastingEntity() instanceof ServerPlayer;
    }

    public static boolean begin(CastingEnvironment env, ExecutionScope scope) {
        if (!ServerConfig.hexJitFastHexOPMediaPool || !JitCompatibility.mediaPoolTargetReady()
                || (scope == null ? !supportsEnvironment(env) : !scope.canUseFastMediaPool(env))) return false;
        Pending pending = currentPending();
        if (pending != null && pending.active) return false;
        ServerPlayer player = (ServerPlayer) env.getCastingEntity();
        if (ServerConfig.hexJitCacheTickMediaHolder && scope != null && pending != null
                && pending.scope == scope && pending.env() == env && pending.player() == player
                && pending.canProvide()) {
            pending.prepare(env, player, pending.holder());
            return true;
        }
        Access api = access();
        if (api == null) return false;
        try {
            if (!(boolean) api.enabled().invokeExact((Player) player)) return false;
            Object holder = (Object) api.get().invokeExact((Player) player);
            if (!(holder instanceof ADMediaHolder media) || !media.canProvide()) return false;
            if (scope != null && pending != null && pending.scope == scope) {
                pending.prepare(env, player, media);
            } else {
                setCurrentPending(new Pending(env, player, media, scope));
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Reuse a proven personal pool only between adjacent successful loop Ticks. */
    public static boolean prepareRepeatedTickPreflight(CastingEnvironment env, long rawCost, ExecutionScope scope) {
        if (scope == null || !scope.repeatedTickMediaPreflightEnabled() || rawCost <= 0) return false;
        Pending pending = serverPending; // The loop dispatcher is exclusively server-thread owned.
        if (pending == null || pending.active || pending.scope != scope || pending.env != env
                || !pending.personalManaHolder || !pending.hasCachedPersonalMediaAvailability
                || !pending.canDeferMediaWrites || !scope.hasDeferredPersonalMediaValue(pending.holder)
                || pending.player.isCreative()) return false;
        long cost = (long) (rawCost * ((CastingEnvironmentObserverAccess) env).cmi$getCostModifier());
        long available = scope.deferredPersonalMediaValue();
        if (cost <= 0 || available < cost) return false;
        // Use the holder's exact rounded virtual balance, including double-to-long conversion.
        pending.cachePersonalMediaAvailability(available);
        pending.active = true;
        pending.forced = pending.holder;
        pending.coverageChecked = true;
        pending.checkedCost = cost;
        scope.recordRepeatedTickPreflight();
        return true;
    }

    /** Called by the guarded PlayerBasedCastEnv wrapper before it scans inventory sources. */
    public static boolean beginExtraction(CastingEnvironment env, long cost, boolean simulate) {
        if (!ServerConfig.hexJitFastHexOPMediaPool) return false;
        Pending pending = currentPending();
        if (pending == null || !pending.active || pending.env() != env || cost <= 0) return false;
        if (pending.coverageChecked && pending.checkedCost == cost) {
            pending.coverageChecked = false;
            boolean personalPoolCovers = pending.forced() == pending.holder();
            if (!personalPoolCovers && ServerConfig.hexJitReuseTickMediaScan)
                pending.prepareFallbackScan(cost, simulate);
            return personalPoolCovers;
        }
        ADMediaHolder holder = pending.holder();
        if (!pending.canProvide() || holder.withdrawMedia(-1, true) < cost) {
            if (ServerConfig.hexJitReuseTickMediaScan) pending.prepareFallbackScan(cost, simulate);
            return false;
        }
        pending.forced(holder);
        return true;
    }

    /**
     * Withdraw directly after the Tick preflight proved HexOP alone covers this exact request.
     * The caller must retain PlayerBasedCastEnv's stat and Spend Media trigger behavior.
     */
    public static long extractPreparedPersonalPool(CastingEnvironment env, long cost, boolean simulate) {
        if (simulate || !ServerConfig.hexJitDirectTickMediaPreflight || !ServerConfig.hexJitFastHexOPMediaPool)
            return Long.MIN_VALUE;
        Pending pending = currentPending();
        if (pending == null || !pending.active || pending.env() != env || !pending.coverageChecked
                || pending.checkedCost != cost || pending.forced() != pending.holder()) return Long.MIN_VALUE;
        pending.coverageChecked = false;
        boolean deferredPersonalWrites = pending.mayDeferPersonalMediaWrites();
        long extracted = deferredPersonalWrites && cost > 0
                ? pending.scope.withdrawDeferredPersonalMedia(pending.holder(), cost)
                : Long.MIN_VALUE;
        if (extracted == Long.MIN_VALUE)
            extracted = pending.holder().withdrawMedia(cost, false);
        pending.recordDirectPersonalMediaWithdrawal(extracted);
        pending.directWithdrawalInProgress = true;
        if (pending.collectMetrics) pending.directExtractions++;
        return cost - extracted;
    }

    /**
     * Extract a prepared Tick cost without re-entering CastingEnvironment.extractMedia. The
     * preflight established that the standard, hook-free environment's HexOP pool covers this
     * exact scaled cost; keep the per-extraction statistics and advancement trigger intact.
     */
    public static boolean extractPreparedTickMediaDirectly(CastingEnvironment env, long rawCost) {
        if (!ServerConfig.hexJitDirectTickMediaExtraction || rawCost <= 0
                || !(env instanceof CastingEnvironmentObserverAccess access)) return false;
        long scaledCost = (long) (rawCost * access.cmi$getCostModifier());
        long remaining = extractPreparedPersonalPool(env, scaledCost, false);
        if (remaining == Long.MIN_VALUE) return false;
        finishDirectExtraction(env, scaledCost, remaining);
        return true;
    }

    /** Preserve PlayerBasedCastEnv's bookkeeping for a successful direct pool withdrawal. */
    public static void finishDirectExtraction(CastingEnvironment env, long cost, long remaining) {
        try {
            ServerPlayer player = (ServerPlayer) env.getCastingEntity();
            long extracted = cost - remaining;
            Pending pending = currentPending();
            if (pending == null || pending.scope == null
                    || !pending.scope.deferMediaUsedStat(player, (int) extracted))
                player.awardStat(HexStatistics.MEDIA_USED, (int) extracted);
            ExecutionScope scope = pending == null ? null : pending.scope;
            SpendMediaTrigger spendMediaTrigger = scope != null && scope.loopTickBatchEnabled()
                    ? scope.cachedSpendMediaTrigger() : null;
            if (spendMediaTrigger == null) {
                spendMediaTrigger = HexAdvancementTriggers.SPEND_MEDIA_TRIGGER.get();
                if (scope != null && scope.loopTickBatchEnabled())
                    scope.rememberSpendMediaTrigger(spendMediaTrigger);
            }
            boolean dispatched = ServerConfig.hexJitFastSpendMediaTrigger
                    && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                    && HexJitRuntime.enabled() && HexJitRuntime.onServerThread()
                    && scope != null && scope.loopTickBatchEnabled()
                    && SpendMediaTriggerCache.tryDispatchCached(
                            scope, spendMediaTrigger, player, extracted, remaining < 0 ? -remaining : 0);
            if (!dispatched)
                spendMediaTrigger.trigger(player, extracted, remaining < 0 ? -remaining : 0);
        } catch (RuntimeException | Error failure) {
            endExtractionFailure(env);
            throw failure;
        }
        endExtraction(env, cost, false, remaining);
    }

    public static long lastDirectExtractions() { return lastDirectExtractions; }

    /** Flush before a criterion listener can run commands or nested casts that observe player attributes. */
    public static void flushDeferredPersonalMediaWrites(ServerPlayer player) {
        Pending pending = currentPending();
        if (pending != null && pending.player() == player && pending.scope != null) {
            pending.scope.flushDeferredPersonalMediaWrites();
            pending.scope.flushDeferredMediaUsedStat();
            pending.scope.invalidateLoopTickActionPrecheck();
        }
    }

    public static void flushDeferredPersonalMediaWrites(PlayerAdvancements advancements) {
        Pending pending = currentPending();
        if (pending != null && pending.player().getAdvancements() == advancements && pending.scope != null) {
            pending.scope.flushDeferredPersonalMediaWrites();
            pending.scope.flushDeferredMediaUsedStat();
            pending.scope.invalidateLoopTickActionPrecheck();
        }
    }

    private static boolean personalManaExtractEventsEmpty() {
        try {
            Class<?> type = Class.forName("io.yukkuric.hexop.personal_mana.PersonalManaEvents");
            Field field = type.getDeclaredField("onExtract");
            if (!field.trySetAccessible()) return false;
            Object callbacks = field.get(null);
            return callbacks instanceof Collection<?> collection && collection.isEmpty();
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    /** The allow-overcast flag is irrelevant when the personal pool covers the full request. */
    public static boolean skipOvercastCheckWhenCovered(CastingEnvironment env, long cost) {
        if (!ServerConfig.hexJitFastHexOPMediaPool) return false;
        Pending pending = currentPending();
        if (pending == null || !pending.active || pending.env() != env || cost <= 0) return false;
        if (pending.coverageChecked && pending.checkedCost == cost)
            return pending.forced() == pending.holder();
        pending.coverageChecked = true;
        pending.checkedCost = cost;
        ADMediaHolder holder = pending.holder();
        if (!pending.canProvide() || holder.withdrawMedia(-1, true) < cost) {
            pending.forced(null);
            return false;
        }
        pending.forced(holder);
        return true;
    }

    /**
     * Skip CastingEnvironment's simulated extraction only after the standard Tick cost modifier
     * and the current personal pool prove that HexOP alone covers the request.
     */
    public static boolean preflightCoveredByPersonalPool(CastingEnvironment env, long cost,
                                                          ExecutionScope scope) {
        if (!ServerConfig.hexJitDirectTickMediaPreflight || !ServerConfig.hexJitFastHexOPMediaPool
                || !JitCompatibility.directMediaPreflightReady()) return false;
        if (scope == null || !scope.canUseFastMediaPool(env)
                || !(env instanceof CastingEnvironmentObserverAccess access)) return false;
        ServerPlayer player = (ServerPlayer) env.getCastingEntity();
        if (player.isCreative()) return false;
        Pending pending = currentPending();
        if (pending == null || !pending.active || pending.env() != env || pending.player() != player
                || !pending.canProvide()) return false;
        long scaledCost = (long) (cost * access.cmi$getCostModifier());
        long available;
        if (pending.canCachePersonalMediaAvailability(scope)) {
            available = pending.hasCachedPersonalMediaAvailability
                    ? pending.cachedPersonalMediaAvailability
                    : pending.holder().withdrawMedia(-1, true);
            pending.cachePersonalMediaAvailability(available);
            if (scaledCost > 0 && available < scaledCost) {
                pending.invalidateCachedPersonalMediaAvailability();
                pending.forced(null);
                pending.coverageChecked = false;
                return false;
            }
        } else {
            long extracted = pending.holder().withdrawMedia(scaledCost, true);
            available = extracted;
        }
        if (scaledCost > 0 && available < scaledCost) {
            pending.forced(null);
            pending.coverageChecked = false;
            return false;
        }
        pending.forced(pending.holder());
        pending.coverageChecked = true;
        pending.checkedCost = scaledCost;
        return true;
    }

    /** A null result asks the mixin to run Hexcasting's original inventory/Curios scan. */
    public static List<ADMediaHolder> forcedSources(CastingEnvironment env, ServerPlayer player) {
        if (!ServerConfig.hexJitFastHexOPMediaPool) return null;
        Pending pending = currentPending();
        if (pending == null || !pending.active || pending.env() != env || pending.player() != player) return null;
        ADMediaHolder forced = pending.forced();
        if (forced != null) return pending.sources();
        if (pending.usingSimulatedSources()) return pending.simulatedSources();
        return null;
    }

    public static void rememberScannedSources(CastingEnvironment env, ServerPlayer player,
                                              List<ADMediaHolder> sources) {
        if (!ServerConfig.hexJitFastHexOPMediaPool) return;
        Pending pending = currentPending();
        if (pending == null || !pending.active || pending.env() != env || pending.player() != player) return;
        if (pending.recordingSimulation() && ServerConfig.hexJitReuseTickMediaScan)
            pending.candidateSources(sources);
    }

    public static void endExtraction(CastingEnvironment env, long cost, boolean simulate, long remaining) {
        Pending pending = currentPending();
        if (pending != null && pending.env() == env) {
            if (simulate) {
                if (pending.extractionCost() == cost && remaining <= 0 && pending.candidateSources() != null) {
                    pending.simulatedSources(pending.candidateSources());
                    pending.simulatedSourceCost(cost);
                } else {
                    pending.simulatedSources(null);
                }
            } else {
                pending.simulatedSources(null);
            }
            pending.finishExtraction();
            pending.forced(null);
            pending.coverageChecked = false;
            if (pending.directWithdrawalInProgress) pending.directWithdrawalInProgress = false;
            else pending.invalidateCachedPersonalMediaAvailability();
        }
    }

    public static void endExtractionFailure(CastingEnvironment env) {
        Pending pending = currentPending();
        if (pending != null && pending.env() == env) {
            pending.simulatedSources(null);
            pending.finishExtraction();
            pending.forced(null);
            pending.coverageChecked = false;
            pending.directWithdrawalInProgress = false;
            pending.invalidateCachedPersonalMediaAvailability();
        }
    }

    /** Invalidate the simulated personal-pool balance before another action can alter media. */
    public static void invalidateCachedMediaAvailability(ExecutionScope scope) {
        if (scope == null) return;
        Pending pending = currentPending();
        if (pending != null && pending.scope == scope)
            pending.invalidateCachedPersonalMediaAvailability();
    }

    public static void end(CastingEnvironment env) {
        Pending pending = currentPending();
        if (pending != null && pending.env() == env) {
            pending.forced(null);
            if (pending.scope == null) removeCurrentPending();
            else pending.active = false;
        }
    }

    /** Clear a cast-scoped reusable extraction context when its execution scope closes. */
    public static void endCast(ExecutionScope scope) {
        Pending pending = currentPending();
        if (pending != null && pending.scope == scope) {
            lastDirectExtractions = pending.collectMetrics ? pending.directExtractions : 0;
            removeCurrentPending();
        }
    }

    private static Pending currentPending() {
        return HexJitRuntime.onServerThread() ? serverPending : OTHER_THREADS.get();
    }

    private static void setCurrentPending(Pending pending) {
        if (HexJitRuntime.onServerThread()) serverPending = pending;
        else OTHER_THREADS.set(pending);
    }

    private static void removeCurrentPending() {
        if (HexJitRuntime.onServerThread()) serverPending = null;
        else OTHER_THREADS.remove();
    }

    private static Access access() {
        if (accessResolved) return access;
        synchronized (FastHexOPMediaPool.class) {
            if (accessResolved) return access;
            accessResolved = true;
            if (!ModList.get().isLoaded("hexoverpowered")) return null;
            try {
                Class<?> type = Class.forName("io.yukkuric.hexop.personal_mana.PersonalManaHolder");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                MethodHandle enabled = lookup.findStatic(type, "enablesManaForPlayer",
                        MethodType.methodType(boolean.class, Player.class));
                MethodHandle get = lookup.findStatic(type, "get",
                        MethodType.methodType(type, Player.class))
                        .asType(MethodType.methodType(Object.class, Player.class));
                access = new Access(enabled, get);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                access = null;
            }
            return access;
        }
    }

    private record Access(MethodHandle enabled, MethodHandle get) {}

    private static final class Pending {
        private CastingEnvironment env;
        private ServerPlayer player;
        private ADMediaHolder holder;
        private List<ADMediaHolder> sources;
        private final ExecutionScope scope;
        private ADMediaHolder forced;
        private boolean active = true;
        private final boolean collectMetrics;
        private boolean coverageChecked;
        private long checkedCost;
        private long extractionCost;
        private List<ADMediaHolder> candidateSources;
        private List<ADMediaHolder> simulatedSources;
        private long simulatedSourceCost;
        private boolean recordingSimulation;
        private boolean usingSimulatedSources;
        private long directExtractions;
        private boolean cachePersonalMediaAvailability;
        private boolean hasCachedPersonalMediaAvailability;
        private long cachedPersonalMediaAvailability;
        private boolean directWithdrawalInProgress;
        private boolean personalManaHolder;
        private boolean checkedMediaWriteDeferral;
        private boolean canDeferMediaWrites;

        private Pending(CastingEnvironment env, ServerPlayer player, ADMediaHolder holder, ExecutionScope scope) {
            this.scope = scope;
            collectMetrics = scope != null && scope.collectMetricsEnabled();
            prepare(env, player, holder);
        }

        private void prepare(CastingEnvironment env, ServerPlayer player, ADMediaHolder holder) {
            boolean sameSource = this.env == env && this.player == player && this.holder == holder;
            if (this.holder != holder) sources = List.of(holder);
            if (!sameSource) invalidateCachedPersonalMediaAvailability();
            if (this.holder != holder) {
                personalManaHolder = PERSONAL_MEDIA_HOLDER_CLASS.equals(holder.getClass().getName());
                cachePersonalMediaAvailability = scope != null && scope.cacheTickMediaAvailabilityEnabled()
                        && personalManaHolder;
            }
            this.env = env;
            this.player = player;
            this.holder = holder;
            this.forced = null;
            this.active = true;
            this.coverageChecked = false;
            this.checkedCost = 0;
            finishExtraction();
            simulatedSources = null;
            simulatedSourceCost = 0;
        }

        private boolean mayDeferPersonalMediaWrites() {
            if (!checkedMediaWriteDeferral) {
                checkedMediaWriteDeferral = true;
                canDeferMediaWrites = scope != null && personalManaHolder
                        && ServerConfig.hexJitBatchTickPersonalMediaWrites
                        && JitCompatibility.personalMediaBatchTargetReady()
                        && personalManaExtractEventsEmpty();
            }
            if (!canDeferMediaWrites || scope == null) return false;
            if (scope.beginDeferredPersonalMediaWrites(holder)) return true;
            canDeferMediaWrites = false;
            return false;
        }

        private void prepareFallbackScan(long cost, boolean simulate) {
            extractionCost = cost;
            recordingSimulation = simulate && ServerConfig.hexJitReuseTickMediaScan;
            usingSimulatedSources = !simulate && ServerConfig.hexJitReuseTickMediaScan
                    && simulatedSources != null && simulatedSourceCost == cost;
            candidateSources = null;
        }

        private void finishExtraction() {
            extractionCost = 0;
            candidateSources = null;
            recordingSimulation = false;
            usingSimulatedSources = false;
        }

        private boolean canCachePersonalMediaAvailability(ExecutionScope currentScope) {
            return cachePersonalMediaAvailability && scope == currentScope;
        }

        private boolean canProvide() {
            return personalManaHolder || holder.canProvide();
        }

        private void cachePersonalMediaAvailability(long available) {
            cachedPersonalMediaAvailability = available;
            hasCachedPersonalMediaAvailability = true;
        }

        private void recordDirectPersonalMediaWithdrawal(long amount) {
            if (hasCachedPersonalMediaAvailability)
                cachedPersonalMediaAvailability = Math.max(0, cachedPersonalMediaAvailability - amount);
        }

        private void invalidateCachedPersonalMediaAvailability() {
            hasCachedPersonalMediaAvailability = false;
            cachedPersonalMediaAvailability = 0;
        }

        private CastingEnvironment env() { return env; }
        private ServerPlayer player() { return player; }
        private ADMediaHolder holder() { return holder; }
        private List<ADMediaHolder> sources() { return sources; }
        private ADMediaHolder forced() { return forced; }
        private void forced(ADMediaHolder forced) { this.forced = forced; }
        private long extractionCost() { return extractionCost; }
        private List<ADMediaHolder> candidateSources() { return candidateSources; }
        private void candidateSources(List<ADMediaHolder> sources) { candidateSources = sources; }
        private List<ADMediaHolder> simulatedSources() { return simulatedSources; }
        private void simulatedSources(List<ADMediaHolder> sources) { simulatedSources = sources; }
        private long simulatedSourceCost() { return simulatedSourceCost; }
        private void simulatedSourceCost(long cost) { simulatedSourceCost = cost; }
        private boolean recordingSimulation() { return recordingSimulation; }
        private boolean usingSimulatedSources() { return usingSimulatedSources; }
    }
}
