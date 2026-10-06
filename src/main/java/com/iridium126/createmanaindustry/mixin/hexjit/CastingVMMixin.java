package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ExecutionClientView;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.ContinuationFrame;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect.AttemptSpell;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.AddMotionNormalizationCache;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastTickAction;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastLoopTickDispatch;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastHexOPMediaPool;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.IotaStackValidation;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.JitCompatibility;
import com.iridium126.createmanaindustry.compat.hexcasting.OpTick;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CastingVM.class, remap = false)
public abstract class CastingVMMixin {
    private static final String ADD_MOTION_SPELL =
            "at.petrak.hexcasting.common.casting.actions.spells.OpAddMotion$Spell";
    @org.spongepowered.asm.mixin.Unique private boolean cmi$fastStackValidation;
    @org.spongepowered.asm.mixin.Unique private boolean cmi$cacheStackMetrics;
    @org.spongepowered.asm.mixin.Unique private boolean cmi$cacheStackValidationResults;
    @org.spongepowered.asm.mixin.Unique private IotaStackValidation.MetricCache cmi$stackMetricCache;

    @WrapOperation(method = {"queueExecuteAndWrapIotas", "executeInner"}, at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/iota/IotaType;isTooLargeToSerialize(Ljava/lang/Iterable;)Z"))
    private boolean cmi$fastStackValidation(Iterable<Iota> stack, Operation<Boolean> original) {
        if (cmi$fastStackValidation) {
            ExecutionScope scope = ExecutionScope.current();
            if (scope != null && scope.consumeTickSubstackValidationSkip(stack)) return false;
            if ((cmi$cacheStackMetrics || cmi$cacheStackValidationResults)
                    && cmi$stackMetricCache == null && stack instanceof TreeList<?>) {
                cmi$stackMetricCache = new IotaStackValidation.MetricCache(
                        cmi$cacheStackMetrics, cmi$cacheStackValidationResults);
            }
            if (cmi$stackMetricCache == null) return IotaStackValidation.isTooLarge(stack);
            return IotaStackValidation.isTooLarge(stack, cmi$stackMetricCache);
        }
        return original.call(stack);
    }

    @WrapMethod(method = "queueExecuteAndWrapIotas")
    private ExecutionClientView cmi$scope(List<Iota> iotas, ServerLevel level,
                                          Operation<ExecutionClientView> original) {
        ExecutionScope enclosingScope = ExecutionScope.current();
        if (enclosingScope != null) {
            enclosingScope.flushTickCounterWrites();
            enclosingScope.flushDeferredPersonalMediaWrites();
        }
        boolean jitEnabled = HexJitRuntime.enabled();
        boolean fastStack = jitEnabled && ServerConfig.hexJitFastStackValidation
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && JitCompatibility.fastStackValidationReady();
        boolean cacheStackMetrics = fastStack && ServerConfig.hexJitCacheStackMetrics;
        boolean cacheStackValidationResults = fastStack && ServerConfig.hexJitCacheStackValidationResults;
        boolean batchMotion = ServerConfig.hexJitBatchAddMotion && JitCompatibility.motionBatchingReady();
        boolean memoAddMotionNormalization = jitEnabled && ServerConfig.hexJitFastAddMotionArguments
                && ServerConfig.hexJitMemoAddMotionNormalization
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && JitCompatibility.fastAddMotionReady();
        boolean coalesceDecorations = ServerConfig.hexJitCoalesceDecorations
                && JitCompatibility.particleCoalescingReady();
        boolean cacheActionTagMembership = ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && ServerConfig.hexJitCacheActionTagMembership && JitCompatibility.actionTagMembershipReady();
        boolean cacheActionPrechecks = jitEnabled && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && ServerConfig.hexJitCacheActionPrechecks && JitCompatibility.actionPrechecksReady();
        boolean reuseTickUserData = ServerConfig.hexJitReuseTickUserData && FastTickAction.enabled();
        boolean cacheTickChunk = ServerConfig.hexJitCacheTickChunk && FastTickAction.enabled();
        boolean cacheTickBlockEligibility = ServerConfig.hexJitCacheTickBlockEligibility
                && FastTickAction.enabled();
        boolean cacheMaxOpCount = jitEnabled && ServerConfig.hexJitCacheMaxOpCount
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO;
        boolean loopSpecialization = jitEnabled && ServerConfig.hexJitLoopSpecialization
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && JitCompatibility.frameTailCacheReady();
        boolean coalesceEvalSounds = jitEnabled && ServerConfig.hexJitCoalesceEvalSounds
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO;
        boolean skipEmptyPostExecution = jitEnabled && ServerConfig.hexJitSkipEmptyPostExecution
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO;
        boolean needsScope = ServerConfig.hexJitSkipObservers || coalesceDecorations || batchMotion
                || reuseTickUserData || cacheTickChunk || cacheTickBlockEligibility || cacheMaxOpCount
                || cacheActionTagMembership || cacheActionPrechecks || loopSpecialization || coalesceEvalSounds
                || skipEmptyPostExecution;
        if (!jitEnabled || (!fastStack && !needsScope && !memoAddMotionNormalization))
            return original.call(iotas, level);
        boolean previous = cmi$fastStackValidation;
        boolean previousCacheStackMetrics = cmi$cacheStackMetrics;
        boolean previousCacheStackValidationResults = cmi$cacheStackValidationResults;
        IotaStackValidation.MetricCache previousMetricCache = cmi$stackMetricCache;
        cmi$fastStackValidation = fastStack;
        cmi$cacheStackMetrics = cacheStackMetrics;
        cmi$cacheStackValidationResults = cacheStackValidationResults;
        cmi$stackMetricCache = null;
        if (memoAddMotionNormalization) AddMotionNormalizationCache.beginCast();
        try {
            if (needsScope) {
                try (ExecutionScope ignored = ExecutionScope.enter(batchMotion)) {
                    return original.call(iotas, level);
                }
            }
            return original.call(iotas, level);
        } finally {
            if (memoAddMotionNormalization) AddMotionNormalizationCache.endCast();
            cmi$fastStackValidation = previous;
            cmi$cacheStackMetrics = previousCacheStackMetrics;
            cmi$cacheStackValidationResults = previousCacheStackValidationResults;
            cmi$stackMetricCache = previousMetricCache;
        }
    }

    @WrapOperation(method = "queueExecuteAndWrapIotas", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/eval/vm/ContinuationFrame;evaluate(" +
                    "Lat/petrak/hexcasting/api/casting/eval/vm/SpellContinuation;" +
                    "Lnet/minecraft/server/level/ServerLevel;" +
                    "Lat/petrak/hexcasting/api/casting/eval/vm/CastingVM;)" +
                    "Lat/petrak/hexcasting/api/casting/eval/CastResult;"))
    private CastResult cmi$step(ContinuationFrame frame, SpellContinuation continuation,
                                ServerLevel level, CastingVM vm, Operation<CastResult> original) {
        boolean skipObservers = ServerConfig.hexJitSkipObservers;
        boolean batchTickCounterWrites = ServerConfig.hexJitBatchTickCounterWrites;
        ExecutionScope scope = skipObservers || batchTickCounterWrites ? ExecutionScope.current() : null;
        if (batchTickCounterWrites && scope != null && scope.batchTickCounterWritesEnabled()
                && !FastLoopTickDispatch.willDispatchFastTick(frame, vm, scope))
            scope.flushTickCounterWrites();
        if (!skipObservers) scope = null;
        if (scope != null) scope.startStep();
        return original.call(frame, continuation, level, vm);
    }

    @WrapOperation(method = "queueExecuteAndWrapIotas", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/eval/CastingEnvironment;postExecution(" +
                    "Lat/petrak/hexcasting/api/casting/eval/CastResult;)V"))
    private void cmi$notify(CastingEnvironment env, CastResult result, Operation<Void> original) {
        boolean skipEmpty = ServerConfig.hexJitSkipEmptyPostExecution
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO;
        ExecutionScope scope = ServerConfig.hexJitSkipObservers || skipEmpty ? ExecutionScope.current() : null;
        if (skipEmpty && scope != null && scope.canSkipEmptyCallbacks(env)) {
            scope.recordEmptyPostExecutionSkipped();
            return;
        }
        if (scope == null) {
            original.call(env, result);
            return;
        }
        if (!ServerConfig.hexJitSkipObservers) {
            original.call(env, result);
            return;
        }
        scope.notifying(true);
        try {
            original.call(env, result);
        } finally {
            scope.notifying(false);
        }
    }

    @WrapOperation(method = "performSideEffects", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/eval/sideeffects/OperatorSideEffect;performEffect(" +
                    "Lat/petrak/hexcasting/api/casting/eval/vm/CastingVM;)V"))
    private void cmi$performSideEffect(OperatorSideEffect effect, CastingVM vm, Operation<Void> original) {
        // The two opt-ins affect disjoint effect types. Check their cheap gates before touching
        // the per-cast ThreadLocal so ordinary casts pay only this single wrapper.
        if (ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && ServerConfig.hexJitFastTickAction
                && effect instanceof AttemptSpell tickAttempt
                && OpTick.isTickSpell(tickAttempt.getSpell())) {
            original.call(effect, vm);
            return;
        }
        if (effect instanceof OperatorSideEffect.ConsumeMedia) {
            ExecutionScope rangeScope = ExecutionScope.current();
            if (rangeScope != null) rangeScope.invalidateTickRangeCheck();
            if (ServerConfig.hexJitBatchTickCounterWrites) {
                ExecutionScope scope = ExecutionScope.current();
                if (scope != null) scope.flushTickCounterWrites();
            }
            try {
                original.call(effect, vm);
            } finally {
                FastHexOPMediaPool.end(vm.getEnv());
            }
            return;
        }
        ExecutionScope scope = ExecutionScope.current();
        if (scope != null && !(effect instanceof AttemptSpell tickAttempt
                && OpTick.isTickSpell(tickAttempt.getSpell()))) {
            if (!(effect instanceof OperatorSideEffect.Particles)) scope.invalidateTickRangeCheck();
            scope.flushTickCounterWrites();
        }
        if (scope != null && effect instanceof AttemptSpell attempt && !OpTick.isTickSpell(attempt.getSpell())) {
            scope.flushBuddingAmethystRandomTicks();
            scope.invalidateBuddingAmethystState();
        } else if (scope != null && effect instanceof OperatorSideEffect.DoMishap) {
            scope.flushBuddingAmethystRandomTicks();
            scope.invalidateBuddingAmethystState();
        }
        if (ServerConfig.hexJitCoalesceDecorations && JitCompatibility.particleCoalescingReady()
                && effect instanceof OperatorSideEffect.Particles particles) {
            if (scope != null) {
                FrozenPigment pigment = scope.getPigment(vm.getEnv());
                if (!scope.emitParticle(particles.getSpray(), pigment)) return;
                vm.getEnv().produceParticles(particles.getSpray(), pigment);
                return;
            }
        }
        if (ServerConfig.hexJitBatchAddMotion && JitCompatibility.motionBatchingReady()
                && effect instanceof AttemptSpell attempt
                && ADD_MOTION_SPELL.equals(attempt.getSpell().getClass().getName())) {
            if (scope != null) {
                boolean previous = scope.inAddMotionEffect();
                scope.addMotionEffect(true);
                try {
                    original.call(effect, vm);
                } finally {
                    scope.addMotionEffect(previous);
                }
                return;
            }
        }
        original.call(effect, vm);
    }

    @WrapOperation(method = "queueExecuteAndWrapIotas", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/eval/CastingEnvironment;postCast(" +
                    "Lat/petrak/hexcasting/api/casting/eval/vm/CastingImage;)V"))
    private void cmi$flushBeforePostCast(CastingEnvironment env, CastingImage image, Operation<Void> original) {
        ExecutionScope scope = ExecutionScope.current();
        if (scope != null) {
            scope.flushTickCounterWrites();
            scope.flushBuddingAmethystRandomTicks();
            scope.flushAllMotion();
        }
        original.call(env, image);
    }
}
