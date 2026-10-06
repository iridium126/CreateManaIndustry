package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.HexAPI;
import at.petrak.hexcasting.api.casting.PatternShapeMatch;
import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect.AttemptSpell;
import at.petrak.hexcasting.api.casting.eval.vm.ContinuationFrame;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.FrameEvaluate;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.mishaps.MishapEvalTooMuch;
import at.petrak.hexcasting.api.casting.mishaps.MishapStackSize;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import com.iridium126.createmanaindustry.compat.hexcasting.OpTick;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

/** Executes a cached Tick action from a loop frame without repeating pattern registry dispatch. */
public final class FastLoopTickDispatch {
    // The specialized Tick increments opsConsumed on every successful action and
    // commitIntermediate enforces the environment's op limit on every fold. Match the
    // default single-cast budget so long Tick-only loop frames don't bounce back through
    // FrameEvaluate every 1,024 iterations.
    private static final int MAX_TICKS_PER_FRAME_BATCH = 100_000;

    private FastLoopTickDispatch() {}

    /** Match FrameEvaluate's direct-dispatch guards for a repeated Tick continuation frame. */
    public static boolean willDispatchFastTick(ContinuationFrame frame, CastingVM vm,
                                               ExecutionScope scope) {
        if (scope == null || !ServerConfig.hexJitLoopTickDispatch
                || !scope.loopSpecializationEnabled() || !scope.fastTickActionEnabled()
                || !(frame instanceof FrameEvaluate evaluate)) return false;
        var image = vm.getImage();
        if (image.getEscapeNext() || image.getSimulateNext() || image.getParenCount() != 0) return false;
        TreeList<Iota> list = evaluate.getList();
        if (list.isEmpty() || !(list.head() instanceof PatternIota pattern)
                || !(pattern instanceof PatternIotaLoopDispatchAccess lookup)) return false;
        var match = lookup.cmi$getCachedLoopTickMatch(scope.registryGeneration());
        return match != null && (!lookup.cmi$cachedLoopTickRequiresEnlightenment()
                || vm.getEnv().isEnlightened());
    }

    /**
     * Fold a run of direct Tick-only loop frames into one outer CastingVM iteration. Intermediate
     * frames still run their Tick action, precheck, world effects, side effects, postExecution,
     * and op-limit check in order. The initial stack was already validated by CastingVM, and each
     * Tick only removes one argument, so repeating the serialization-size scan is unnecessary.
     */
    public static CastResult executeRun(CastingVM vm, SpellContinuation continuation,
                                        PatternIota pattern, PatternShapeMatch.PerWorld match,
                                        ExecutionScope scope) {
        CastResult result = execute(vm, continuation, pattern, match, scope);
        if (!scope.loopTickBatchEnabled() || !scope.inMetacastingFrame()
                || !scope.canMutateTickUserDataInPlace(vm.getEnv())) return result;

        boolean pendingDirectResult = false;
        SpellContinuation completedContinuation = continuation;
        for (int folded = 0; folded < MAX_TICKS_PER_FRAME_BATCH; folded++) {
            if (pendingDirectResult) {
                if (!scope.hasPendingLoopTickResult())
                    throw new IllegalStateException("Missing folded Tick result");
            } else {
                if (result.getResolutionType() != ResolvedPatternType.EVALUATED || result.getNewData() == null)
                    return result;
                completedContinuation = result.getContinuation();
            }
            if (!(completedContinuation instanceof SpellContinuation.NotDone next)
                    || !(next.getFrame() instanceof FrameEvaluate nextFrame)
                    || !nextFrame.isMetacasting())
                return pendingDirectResult ? scope.finishPendingLoopTickResult() : result;

            TreeList<Iota> nextList = nextFrame.getList();
            if (nextList.isEmpty())
                return pendingDirectResult ? scope.finishPendingLoopTickResult() : result;
            Iota nextHead = nextList.head();
            PatternIota nextPattern;
            PatternShapeMatch.PerWorld nextMatch;
            if (nextHead == pattern) {
                PatternIotaLoopDispatchAccess samePattern = (PatternIotaLoopDispatchAccess) pattern;
                if (samePattern.cmi$cachedLoopTickRequiresEnlightenment()
                        && !vm.getEnv().isEnlightened())
                    return pendingDirectResult ? scope.finishPendingLoopTickResult() : result;
                // The current run entered through FrameEvaluate's full guards and every Tick
                // image preserves escape/simulation/paren state. Reuse its immutable match for
                // the identical pattern object without repeating registry and frame checks.
                nextPattern = pattern;
                nextMatch = match;
            } else {
                if (!willDispatchFastTick(nextFrame, vm, scope)
                        || !(nextHead instanceof PatternIota candidate)
                        || !(candidate instanceof PatternIotaLoopDispatchAccess lookup))
                    return pendingDirectResult ? scope.finishPendingLoopTickResult() : result;
                nextPattern = candidate;
                nextMatch = lookup.cmi$getCachedLoopTickMatch(scope.registryGeneration());
                if (nextMatch == null)
                    return pendingDirectResult ? scope.finishPendingLoopTickResult() : result;
            }

            CastResult committed = pendingDirectResult
                    ? commitPendingIntermediate(vm, scope) : commitIntermediate(vm, result, scope);
            if (committed != null) return committed;

            SpellContinuation afterNext = scope.cachedFrameLoopContinuation(nextList, next.getNext());
            scope.startStep();
            result = executeFolded(vm, afterNext, nextPattern, nextMatch, scope);
            pendingDirectResult = result == null && scope.hasPendingLoopTickResult();
            completedContinuation = afterNext;
        }
        return pendingDirectResult ? scope.finishPendingLoopTickResult() : result;
    }

    /** Returns a replacement mishap result if the intermediate Tick crossed the op limit. */
    private static CastResult commitIntermediate(CastingVM vm, CastResult result, ExecutionScope scope) {
        CastingImage image = result.getNewData();
        if (!scope.consumeTickSubstackValidationSkip(image.getStack())
                && IotaType.isTooLargeToSerialize(image.getStack())) {
            return new CastResult(result.getCast(), result.getContinuation(), null,
                    List.of(new OperatorSideEffect.DoMishap(new MishapStackSize(),
                            new Mishap.Context(null, null))),
                    ResolvedPatternType.ERRORED, HexEvalSounds.MISHAP.get());
        }
        int maxOpCount = scope.hasCachedMaxOpCount()
                ? scope.cachedMaxOpCount() : vm.getEnv().maxOpCount();
        if (image.getOpsConsumed() > maxOpCount) {
            return new CastResult(result.getCast(), result.getContinuation(), null,
                    List.of(new OperatorSideEffect.DoMishap(new MishapEvalTooMuch(),
                            new Mishap.Context(null, null))),
                    ResolvedPatternType.ERRORED, HexEvalSounds.MISHAP.get());
        }

        scope.recordLoopTickBatchFold();
        vm.setImage(image);
        CastingEnvironment env = vm.getEnv();
        boolean skipEmpty = ServerConfig.hexJitSkipEmptyPostExecution
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && scope.canSkipEmptyCallbacks(env);
        if (skipEmpty) {
            scope.recordEmptyPostExecutionSkipped();
        } else {
            boolean skipObservers = ServerConfig.hexJitSkipObservers;
            if (skipObservers) scope.notifying(true);
            try {
                env.postExecution(result);
            } finally {
                if (skipObservers) scope.notifying(false);
            }
        }

        try {
            vm.performSideEffects(result.getSideEffects());
        } catch (Exception exception) {
            exception.printStackTrace();
            vm.performSideEffects(List.of(new OperatorSideEffect.DoMishap(
                    new at.petrak.hexcasting.api.casting.mishaps.MishapInternalException(exception),
                    new Mishap.Context(null, null))));
        }
        return null;
    }

    /** Commit an internal folded Tick without ever allocating its CastResult wrapper. */
    private static CastResult commitPendingIntermediate(CastingVM vm, ExecutionScope scope) {
        CastingImage inputImage = scope.pendingLoopTickInputImage();
        TreeList<Iota> outputStack = scope.pendingLoopTickOutputStack();
        var userData = scope.pendingLoopTickUserData();
        long outputOpsConsumed = inputImage.getOpsConsumed() + 1;
        SpellContinuation continuation = scope.pendingLoopTickContinuation();
        PatternIota pattern = scope.pendingLoopTickPattern();
        List<OperatorSideEffect> sideEffects = scope.pendingLoopTickSideEffects();
        scope.clearPendingLoopTickResult();
        if (!scope.consumeTickSubstackValidationSkip(outputStack)
                && IotaType.isTooLargeToSerialize(outputStack)) {
            return new CastResult(pattern, continuation, null,
                    List.of(new OperatorSideEffect.DoMishap(new MishapStackSize(),
                            new Mishap.Context(null, null))),
                    ResolvedPatternType.ERRORED, HexEvalSounds.MISHAP.get());
        }
        int maxOpCount = scope.hasCachedMaxOpCount()
                ? scope.cachedMaxOpCount() : vm.getEnv().maxOpCount();
        if (outputOpsConsumed > maxOpCount) {
            return new CastResult(pattern, continuation, null,
                    List.of(new OperatorSideEffect.DoMishap(new MishapEvalTooMuch(),
                            new Mishap.Context(null, null))),
                    ResolvedPatternType.ERRORED, HexEvalSounds.MISHAP.get());
        }

        scope.recordLoopTickBatchFold();
        CastingImage image;
        if (inputImage == vm.getImage() && JitCompatibility.loopTickImageMutationReady()
                && (Object) inputImage instanceof CastingImageLoopAccess access) {
            // This image has no retained observer result in the gated loop path; advance it in
            // place so each folded Tick doesn't allocate another seven-field immutable wrapper.
            access.cmi$applyFastTickOutput(outputStack, outputOpsConsumed, userData);
            image = inputImage;
        } else {
            image = new CastingImage(outputStack, inputImage.getParenCount(), inputImage.getParenthesized(),
                    inputImage.getEscapeNext(), inputImage.getSimulateNext(), outputOpsConsumed, userData);
        }
        vm.setImage(image);
        // Direct results are held only when the empty post-execution callback fast path is safe.
        scope.recordEmptyPostExecutionSkipped();
        try {
            if (sideEffects.size() == 1 && sideEffects.get(0) instanceof AttemptSpell attempt
                    && OpTick.isTickSpell(attempt.getSpell())) {
                // CastingVMMixin's Tick branch forwards this exact effect unchanged. Avoid the
                // List iterator and wrapper dispatch for the common single-effect folded Tick.
                // The one-shot scope marker lets TickSpell select its loop-specialized cast body.
                scope.beginFoldedLoopTickEffect();
                try {
                    attempt.performEffect(vm);
                } finally {
                    scope.endFoldedLoopTickEffect();
                }
            } else {
                vm.performSideEffects(sideEffects);
            }
        } catch (Exception exception) {
            exception.printStackTrace();
            vm.performSideEffects(List.of(new OperatorSideEffect.DoMishap(
                    new at.petrak.hexcasting.api.casting.mishaps.MishapInternalException(exception),
                    new Mishap.Context(null, null))));
        }
        return null;
    }

    public static CastResult execute(CastingVM vm, SpellContinuation continuation, PatternIota pattern,
                                     PatternShapeMatch.PerWorld match, ExecutionScope scope) {
        return execute(vm, continuation, pattern, match, scope, false);
    }

    private static CastResult executeFolded(CastingVM vm, SpellContinuation continuation, PatternIota pattern,
                                            PatternShapeMatch.PerWorld match, ExecutionScope scope) {
        boolean deferResult = ServerConfig.hexJitSkipEmptyPostExecution
                && scope.canSkipEmptyCallbacks(vm.getEnv());
        return execute(vm, continuation, pattern, match, scope, deferResult);
    }

    /**
     * Prepare a repeated, same-target budding-amethyst Tick without entering the general action
     * implementation. The first Tick has already emitted its decoration and populated all
     * cast-local target/environment caches; this loop body retains per-call range, media, cost,
     * stack, counter, and op accounting.
     */
    public static boolean prepareFoldedBuddingAmethystTick(CastingEnvironment env, CastingImage image,
                                                            SpellContinuation continuation, PatternIota pattern,
                                                            ExecutionScope scope) {
        if (scope == null || !scope.loopSpecializationEnabled() || !scope.loopTickBatchEnabled()
                || !scope.inMetacastingFrame() || !scope.coalesceDecorationsEnabled()
                || !scope.combineTickSideEffectsEnabled() || !scope.reuseTickUserDataEnabled()
                || !ServerConfig.hexJitSkipEmptyPostExecution || !scope.canSkipEmptyCallbacks(env)
                || image.getEscapeNext() || image.getSimulateNext() || image.getParenCount() != 0
                || !scope.canMutateTickUserDataInPlace(env)) return false;

        TreeList<Iota> stack = image.getStack();
        if (stack.isEmpty()) return false;
        BlockPos pos = scope.cachedTickTarget(stack.getLast());
        if (pos == null) return false;
        OpTick.FastTickAssets assets = scope.cachedFastTickAssets(pos);
        if (assets == null || assets.particles().size() != 1 || assets.attemptSideEffects().size() != 1
                || !scope.isDuplicateLoopTickParticle(assets.particles().get(0))) return false;
        var blockState = scope.cachedBuddingAmethystState(env.getWorld(), pos);
        if (blockState == null || !blockState.is(Blocks.BUDDING_AMETHYST)) return false;

        TreeList<Iota> cachedStackWithoutArgs = scope.cacheTickStackPopEnabled()
                ? ExecutionScope.takeTickStackPopSourceOnServerThread(stack) : null;
        TreeList<Iota> stackWithoutArgs = cachedStackWithoutArgs == null ? stack.init() : cachedStackWithoutArgs;
        var userData = scope.reuseTickUserData(image.getUserData(), OpTick.TAG_TIMES_TICKED,
                HexAPI.RAVENMIND_USERDATA);
        long cost = OpTick.INSTANCE.executeForFastPath(pos, env, userData, true, scope, assets);
        FastTickAction.preflightTickMedia(env, image, cost, scope);
        if (cost > 0) scope.prepareTickMediaExtraction(env, cost);
        scope.rememberTickSubstack(stackWithoutArgs);
        scope.preparePendingLoopTickResult(pattern, continuation, image, stackWithoutArgs,
                userData, assets.attemptSideEffects(), scope.loopTickHermesSound());
        scope.recordFoldedBuddingAmethystAction();
        return true;
    }

    private static CastResult execute(CastingVM vm, SpellContinuation continuation, PatternIota pattern,
                                      PatternShapeMatch.PerWorld match, ExecutionScope scope,
                                      boolean deferResult) {
        CastingEnvironment env = vm.getEnv();
        scope.recordLoopTickDispatch();
        boolean precheckPassed = false;
        scope.loopTickDispatchActive(true);
        try {
            if (scope.canReuseLoopTickActionPrecheck(match.key)) {
                scope.recordLoopTickActionPrecheckHit();
                precheckPassed = true;
            } else {
                precheckPassed = env instanceof CastingEnvironmentObserverAccess access
                        && access.cmi$applyCachedLoopTickPrecheck(match, scope);
                if (!precheckPassed) env.precheckAction(match);
                precheckPassed = true;
            }
            ExecutionScope.markCompiled();
            CastResult result = deferResult
                    ? FastTickAction.operateFolded(env, vm.getImage(), continuation, pattern, scope)
                    : FastTickAction.operate(env, vm.getImage(), continuation, pattern, scope);
            scope.loopTickDispatchCompleted(true);
            return result;
        } catch (Mishap mishap) {
            boolean wipeParens = continuation instanceof SpellContinuation.NotDone cnd
                    && cnd.getFrame() instanceof FrameEvaluate frameEval && frameEval.isMetacasting();
            var image = wipeParens ? vm.getImage().withResetEscape() : null;
            Component actionName = precheckPassed ? HexAPI.instance().getActionI18n(match.key, false) : null;
            return new CastResult(pattern, continuation, image,
                    List.of(new OperatorSideEffect.DoMishap(mishap,
                            new Mishap.Context(pattern.getPattern(), actionName))),
                    mishap.resolutionType(env), HexEvalSounds.MISHAP.get());
        } finally {
            if (!precheckPassed) scope.loopTickDispatchCompleted(false);
            scope.loopTickDispatchActive(false);
        }
    }
}
