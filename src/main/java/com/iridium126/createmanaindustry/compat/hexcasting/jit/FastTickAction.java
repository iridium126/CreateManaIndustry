package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.HexAPI;
import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.sideeffects.EvalSound;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedCastEnv;
import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedSpiralPatternCastEnv;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import com.iridium126.createmanaindustry.compat.hexcasting.OpTick;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** Allocation-lean equivalent of SpellAction.operate for the project's read-only Tick action. */
public final class FastTickAction {
    private static final ClassValue<Boolean> HAS_READ_ONLY_POST_EXECUTION = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                Class<?> declaringClass = type.getMethod("postExecution", CastResult.class).getDeclaringClass();
                // PlayerBasedCastEnv only examines side effects and refreshes cached player
                // attributes; it neither mutates nor retains the CastResult image. A subclass
                // override is deliberately rejected by checking the actual declaring class.
                return declaringClass == CastingEnvironment.class
                        || declaringClass == PlayerBasedCastEnv.class
                        || declaringClass == StaffCastEnv.class;
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
    };
    private static final ClassValue<Boolean> HAS_STABLE_PLAYER_RANGE_CHECK = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (!StaffCastEnv.class.isAssignableFrom(type)) return false;
            try {
                Class<?> rangeOwner = methodOwner(type, "isVecInRangeEnvironment", Vec3.class);
                return type.getMethod("isVecInRange", Vec3.class).getDeclaringClass()
                                == CastingEnvironment.class
                        && type.getMethod("precheckAction", at.petrak.hexcasting.api.casting.PatternShapeMatch.class)
                                .getDeclaringClass() == CastingEnvironment.class
                        && (rangeOwner == PlayerBasedCastEnv.class
                                || rangeOwner == PlayerBasedSpiralPatternCastEnv.class);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return false;
            }
        }
    };
    private FastTickAction() {}
    private static final ClassValue<Boolean> HAS_STANDARD_CALLBACK_BRIDGE = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("cmi$getTickCaster").getDeclaringClass() == PlayerBasedCastEnv.class
                        && type.getMethod("cmi$hasPureRangeAttributes").getDeclaringClass() == PlayerBasedCastEnv.class
                        && type.getMethod("cmi$canCollapseQuotedCallbacks", EvalSound.class).getDeclaringClass() == StaffCastEnv.class
                        && type.getMethod("cmi$recordCollapsedQuotedCallbacks", EvalSound.class, int.class).getDeclaringClass() == StaffCastEnv.class
                        && type.getMethod("cmi$refreshTickRangeAttributes").getDeclaringClass() == PlayerBasedCastEnv.class
                        && type.getMethod("cmi$recordTickPattern", at.petrak.hexcasting.api.casting.math.HexPattern.class)
                                .getDeclaringClass() == PlayerBasedSpiralPatternCastEnv.class
                        && type.getMethod("cmi$postSuccessfulTick", at.petrak.hexcasting.api.casting.math.HexPattern.class,
                                at.petrak.hexcasting.api.casting.eval.sideeffects.EvalSound.class)
                                .getDeclaringClass() == StaffCastEnv.class;
            } catch (ReflectiveOperationException ignored) { return false; }
        }
    };
    public static boolean hasStandardCallbackBridge(CastingEnvironment env) {
        return HAS_STANDARD_CALLBACK_BRIDGE.get(env.getClass());
    }

    private static final ClassValue<Boolean> HAS_BASE_OP_LIMIT = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("maxOpCount").getDeclaringClass() == CastingEnvironment.class;
            } catch (ReflectiveOperationException ignored) { return false; }
        }
    };
    public static boolean hasBaseOpLimit(CastingEnvironment env) { return HAS_BASE_OP_LIMIT.get(env.getClass()); }

    /** Read-only player callbacks still report mishaps, refresh range, and play staff sounds. */
    private static final ClassValue<Boolean> HAS_EMPTY_POST_EXECUTION = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("postExecution", CastResult.class).getDeclaringClass()
                        == CastingEnvironment.class;
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
    };

    public static boolean maySkipPostExecution(CastingEnvironment env) {
        return env instanceof CastingEnvironmentObserverAccess access
                && access.cmi$getPostExecutions().isEmpty()
                && HAS_EMPTY_POST_EXECUTION.get(env.getClass());
    }

    public static boolean enabled() {
        return ServerConfig.hexJitFastTickAction
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && HexJitRuntime.enabled()
                && JitCompatibility.fastAddMotionReady();
    }

    public static boolean supports(Action action) {
        return action == OpTick.INSTANCE && enabled();
    }

    public static boolean mayMutateExecutionState(CastingEnvironment env) {
        return (env.getClass() == StaffCastEnv.class || env instanceof TickStateReuseEnvironment)
                && env instanceof CastingEnvironmentObserverAccess access
                && access.cmi$getPostExecutions().isEmpty()
                && HAS_READ_ONLY_POST_EXECUTION.get(env.getClass());
    }

    /**
     * A Tick range result is stable only for the standard player range rule, with no registered
     * components able to observe or alter range/media checks during this synchronous cast.
     */
    public static boolean mayCacheBuddingAmethystRangeCheck(CastingEnvironment env) {
        if (!(env instanceof CastingEnvironmentObserverAccess access)
                || !HAS_STABLE_PLAYER_RANGE_CHECK.get(env.getClass())
                || !access.cmi$getPostExecutions().isEmpty()
                || !access.cmi$getIsVecInRanges().isEmpty()
                || !access.cmi$getPreMediaExtract().isEmpty()
                || !access.cmi$getPostMediaExtract().isEmpty()) return false;
        return true;
    }

    private static Class<?> methodOwner(Class<?> type, String name, Class<?>... parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                current.getDeclaredMethod(name, parameterTypes);
                return current;
            } catch (NoSuchMethodException ignored) {
                // Continue through the concrete environment's superclass chain.
            }
        }
        return null;
    }

    public static CastResult operate(CastingEnvironment env, CastingImage image,
                                     SpellContinuation continuation, PatternIota cast,
                                     ExecutionScope currentScope) {
        return operate(env, image, continuation, cast, currentScope, false);
    }

    /** Fast-loop variant: retain the result fields in the cast scope and defer the wrapper. */
    public static CastResult operateFolded(CastingEnvironment env, CastingImage image,
                                           SpellContinuation continuation, PatternIota cast,
                                           ExecutionScope currentScope) {
        if (FastLoopTickDispatch.prepareFoldedBuddingAmethystTick(
                env, image, continuation, cast, currentScope)) return null;
        return operate(env, image, continuation, cast, currentScope, true);
    }

    /** Preserve Tick's exact simulated preflight and clean up the prepared pool on failure. */
    static void preflightTickMedia(CastingEnvironment env, CastingImage image, long cost,
                                   ExecutionScope scope) {
        if (!image.getSimulateNext() && FastHexOPMediaPool.prepareRepeatedTickPreflight(env, cost, scope)) return;
        boolean useHexOPMediaPool = cost > 0 && !image.getSimulateNext()
                && FastHexOPMediaPool.begin(env, scope);
        try {
            boolean directPreflight = useHexOPMediaPool
                    && FastHexOPMediaPool.preflightCoveredByPersonalPool(env, cost, scope);
            if (!directPreflight) {
                if (scope != null) scope.flushTickCounterWrites();
                if (env.extractMedia(cost, true) > 0) throw new MishapNotEnoughMedia(cost);
            }
        } catch (RuntimeException | Error failure) {
            if (useHexOPMediaPool) FastHexOPMediaPool.end(env);
            throw failure;
        }
    }

    private static CastResult operate(CastingEnvironment env, CastingImage image,
                                      SpellContinuation continuation, PatternIota cast,
                                      ExecutionScope currentScope, boolean deferCastResult) {
        OpTick action = OpTick.INSTANCE;
        TreeList<Iota> stack = image.getStack();
        int argc = action.getArgc();
        if (argc > stack.size()) throw new MishapNotEnoughArgs(argc, stack.size());

        Iota target = stack.getLast();
        BlockPos pos;
        if (currentScope != null && currentScope.cacheTickChunkEnabled()) {
            pos = currentScope.cachedTickTarget(target);
            if (pos == null) throw MishapInvalidIota.Companion.ofType(target, 0, "vector");
        } else {
            if (!(target instanceof Vec3Iota vector))
                throw MishapInvalidIota.Companion.ofType(target, 0, "vector");
            pos = BlockPos.containing(vector.getVec3());
        }
        TreeList<Iota> cachedStackWithoutArgs = currentScope == null || !currentScope.cacheTickStackPopEnabled()
                ? null : ExecutionScope.takeTickStackPopSourceOnServerThread(stack);
        TreeList<Iota> stackWithoutArgs = cachedStackWithoutArgs == null ? stack.init() : cachedStackWithoutArgs;
        boolean mutateUserDataInPlace = currentScope == null
                ? mayMutateExecutionState(env) : currentScope.canMutateTickUserDataInPlace(env);
        ExecutionScope scope = mutateUserDataInPlace ? currentScope : null;
        OpTick.FastTickAssets tickAssets = OpTick.fastAssets(pos, mutateUserDataInPlace, scope);
        CompoundTag userData;
        if (mutateUserDataInPlace && scope != null && scope.reuseTickUserDataEnabled()) {
            // Isolate the caller's input image once, then reuse this private tag after each
            // read-only postExecution callback instead of copying it for every Tick action.
            userData = scope.reuseTickUserData(
                    image.getUserData(), OpTick.TAG_TIMES_TICKED, HexAPI.RAVENMIND_USERDATA);
        } else if (mutateUserDataInPlace) {
            userData = FastCompoundTagCopy.copyForTick(
                    image.getUserData(), OpTick.TAG_TIMES_TICKED, HexAPI.RAVENMIND_USERDATA);
        } else {
            userData = FastCompoundTagCopy.copy(image.getUserData());
        }
        long cost = action.executeForFastPath(pos, env, userData, mutateUserDataInPlace,
                currentScope, tickAssets);

        preflightTickMedia(env, image, cost, currentScope);

        boolean combineTickSideEffects = mutateUserDataInPlace && scope != null
                && scope.combineTickSideEffectsEnabled() && !image.getSimulateNext();
        boolean includeConsumeMedia = cost > 0 && !combineTickSideEffects;
        if (combineTickSideEffects && cost > 0)
            scope.prepareTickMediaExtraction(env, cost);

        List<ParticleSpray> particles = tickAssets.particles();
        OperatorSideEffect.AttemptSpell attempt = tickAssets.attempt();
        boolean canCoalesce = scope != null && mutateUserDataInPlace && scope.coalesceDecorationsEnabled();
        List<OperatorSideEffect> sideEffects;
        if (particles.size() == 1 && canCoalesce) {
            ParticleSpray spray = particles.get(0);
            if (scope.isDuplicateLoopTickParticle(spray)
                    || scope.isDuplicateParticle(spray, scope.getPigment(env))) {
                sideEffects = includeConsumeMedia
                        ? List.of(new OperatorSideEffect.ConsumeMedia(cost), attempt)
                        : tickAssets.attemptSideEffects();
            } else {
                sideEffects = includeConsumeMedia
                        ? List.of(new OperatorSideEffect.ConsumeMedia(cost), attempt,
                                new OperatorSideEffect.Particles(spray))
                        : tickAssets.attemptAndParticleSideEffects();
            }
        } else {
            if (!includeConsumeMedia && particles.size() == 1) {
                sideEffects = tickAssets.attemptAndParticleSideEffects();
            } else {
                List<OperatorSideEffect> mutableSideEffects = new ArrayList<>(particles.size() + 2);
                if (includeConsumeMedia)
                    mutableSideEffects.add(new OperatorSideEffect.ConsumeMedia(cost));
                mutableSideEffects.add(attempt);
                for (int index = 0; index < particles.size(); index++) {
                    ParticleSpray spray = particles.get(index);
                    if (canCoalesce && scope.isDuplicateParticle(spray, scope.getPigment(env))) continue;
                    mutableSideEffects.add(new OperatorSideEffect.Particles(spray));
                }
                sideEffects = mutableSideEffects;
            }
        }

        if (mutateUserDataInPlace && scope != null && !image.getSimulateNext())
            scope.rememberTickSubstack(stack, stackWithoutArgs);
        // FrameEvaluate replaces every successful sound emitted by a metacast with Hermes.
        // Return that final sound here so the loop frame does not allocate a second CastResult
        // just to overwrite Tick's intermediate SPELL/MUTE sound on every iteration.
        boolean loopSpecializedMetacast = currentScope != null
                && currentScope.inMetacastingFrame()
                && ServerConfig.hexJitLoopSpecialization;
        var sound = loopSpecializedMetacast ? currentScope.loopTickHermesSound()
                : action.hasCastingSound(env) ? HexEvalSounds.SPELL.get() : HexEvalSounds.MUTE.get();
        if (deferCastResult) {
            currentScope.preparePendingLoopTickResult(cast, continuation, image,
                    stackWithoutArgs, userData, sideEffects, sound);
            return null;
        }
        CastingImage image2 = image.copy(stackWithoutArgs, image.getParenCount(), image.getParenthesized(),
                image.getEscapeNext(), image.getSimulateNext(), image.getOpsConsumed() + 1, userData);
        return new CastResult(cast, continuation, image2, sideEffects, ResolvedPatternType.EVALUATED, sound);
    }
}
