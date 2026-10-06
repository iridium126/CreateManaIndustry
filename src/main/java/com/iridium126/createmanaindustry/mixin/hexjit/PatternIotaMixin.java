package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.HexAPI;
import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.PatternShapeMatch;
import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.IOperationResult;
import at.petrak.hexcasting.api.casting.eval.OperationResult;
import at.petrak.hexcasting.api.casting.eval.ParenthesizedOperationResult;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.FrameEvaluate;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.common.casting.actions.math.SpecialHandlerNumberLiteral;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidPattern;
import at.petrak.hexcasting.api.casting.mishaps.MishapUnenlightened;
import at.petrak.hexcasting.api.mod.HexTags;
import at.petrak.hexcasting.common.casting.PatternRegistryManifest;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.iridium126.createmanaindustry.compat.hexcasting.OpTick;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ActionSites;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.CompiledCall;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastTickAction;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastHexOPMediaPool;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastAddMotionArguments;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastNumberLiteral;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.JitCompatibility;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.PatternIotaLoopDispatchAccess;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Guarded call-site specialization; all lookup, checks, and result handling stay in Hexcasting. */
@Mixin(value = PatternIota.class, remap = false)
public abstract class PatternIotaMixin implements PatternIotaLoopDispatchAccess {
    @org.spongepowered.asm.mixin.Unique private long cmi$normalPatternEpoch = Long.MIN_VALUE;
    @org.spongepowered.asm.mixin.Unique private PatternShapeMatch.Normal cmi$normalPatternMatch;
    @org.spongepowered.asm.mixin.Unique private long cmi$normalActionEpoch = Long.MIN_VALUE;
    @org.spongepowered.asm.mixin.Unique private ResourceKey<ActionRegistryEntry> cmi$normalActionKey;
    @org.spongepowered.asm.mixin.Unique private Action cmi$normalAction;
    @org.spongepowered.asm.mixin.Unique private boolean cmi$normalActionRequiresEnlightenment;
    @org.spongepowered.asm.mixin.Unique private long cmi$perWorldPatternEpoch = Long.MIN_VALUE;
    @org.spongepowered.asm.mixin.Unique private PatternShapeMatch.PerWorld cmi$perWorldPatternMatch;
    @org.spongepowered.asm.mixin.Unique private long cmi$perWorldActionEpoch = Long.MIN_VALUE;
    @org.spongepowered.asm.mixin.Unique private Action cmi$perWorldAction;
    @org.spongepowered.asm.mixin.Unique private boolean cmi$perWorldActionRequiresEnlightenment;

    @Override
    public PatternShapeMatch.PerWorld cmi$getCachedLoopTickMatch(long registryGeneration) {
        if (cmi$perWorldPatternEpoch != registryGeneration || cmi$perWorldActionEpoch != registryGeneration
                || cmi$perWorldAction != OpTick.INSTANCE)
            return null;
        return cmi$perWorldPatternMatch;
    }

    @Override
    public boolean cmi$cachedLoopTickRequiresEnlightenment() {
        return cmi$perWorldActionRequiresEnlightenment;
    }

    /**
     * Mirrors Hexcasting pre-53 lookup semantics while resolving the display name only on mishap.
     * The upstream method allocates a capturing Supplier for every successful normal pattern.
     */
    @Overwrite
    private @NotNull CastResult lookupAndOperate(CastingVM vm, SpellContinuation continuation, boolean inParens) {
        PatternIota self = (PatternIota) (Object) this;
        ResourceKey<ActionRegistryEntry> castedKey = null;
        at.petrak.hexcasting.api.casting.castables.SpecialHandler castedHandler = null;
        boolean reqsEnlightenment = false;
        try {
            boolean onHexJitServerThread = ServerConfig.hexJitMode != ServerConfig.HexJitMode.OFF
                    && HexJitRuntime.onServerThread();
            ExecutionScope lookupScope = onHexJitServerThread
                    ? ExecutionScope.currentOnServerThread() : null;
            long lookupEpoch = lookupScope == null
                    ? HexJitRuntime.generation() : lookupScope.registryGeneration();
            boolean canCachePatternLookup = lookupScope == null
                    ? ServerConfig.hexJitMode != ServerConfig.HexJitMode.OFF
                    && onHexJitServerThread && JitCompatibility.specialHandlerLookupReady()
                    : lookupScope.patternLookupEnabled();
            boolean cacheNormalPattern = lookupScope == null
                    ? ServerConfig.hexJitCacheNormalPatternLookup
                    && canCachePatternLookup && JitCompatibility.actionPrechecksReady()
                    : lookupScope.cacheNormalPatternLookupEnabled();
            boolean cachePerWorldPattern = lookupScope == null
                    ? ServerConfig.hexJitCachePerWorldPatternLookup && canCachePatternLookup
                    : lookupScope.cachePerWorldPatternLookupEnabled();
            PatternShapeMatch.Normal cachedNormalMatch = cacheNormalPattern
                    && cmi$normalPatternEpoch == lookupEpoch ? cmi$normalPatternMatch : null;
            PatternShapeMatch.PerWorld cachedPerWorldMatch = cachePerWorldPattern
                    && cmi$perWorldPatternEpoch == lookupEpoch ? cmi$perWorldPatternMatch : null;
            at.petrak.hexcasting.api.casting.PatternShapeMatch lookup;
            if (cachedNormalMatch != null) {
                // Normal matches are immutable, and precheckAction only reads their registry key.
                // Reuse the match object while retaining the action/environment checks below.
                lookup = cachedNormalMatch;
            } else if (cachedPerWorldMatch != null) {
                lookup = cachedPerWorldMatch;
            } else {
                lookup = PatternRegistryManifest.matchPattern(self.getPattern(), vm.getEnv());
                if (cacheNormalPattern && lookup instanceof PatternShapeMatch.Normal normal) {
                    cmi$normalPatternMatch = normal;
                    cmi$normalPatternEpoch = lookupEpoch;
                } else if (cachePerWorldPattern && lookup instanceof PatternShapeMatch.PerWorld perWorld) {
                    cmi$perWorldPatternMatch = perWorld;
                    cmi$perWorldPatternEpoch = lookupEpoch;
                }
            }
            vm.getEnv().precheckAction(lookup);

            Action action;
            if (lookup instanceof PatternShapeMatch.Normal || lookup instanceof PatternShapeMatch.PerWorld) {
                ResourceKey<ActionRegistryEntry> key;
                if (lookup instanceof PatternShapeMatch.Normal normal) {
                    key = normal.key;
                } else {
                    PatternShapeMatch.PerWorld perWorld = (PatternShapeMatch.PerWorld) lookup;
                    key = perWorld.key;
                }

                castedKey = key;
                boolean cacheResolvedNormalAction = lookup instanceof PatternShapeMatch.Normal
                        && cacheNormalPattern;
                if (cacheResolvedNormalAction && cmi$normalActionEpoch == lookupEpoch
                        && cmi$normalActionKey == key && cmi$normalAction != null) {
                    action = cmi$normalAction;
                    reqsEnlightenment = cmi$normalActionRequiresEnlightenment;
                } else {
                    boolean cacheResolvedPerWorldAction = lookup instanceof PatternShapeMatch.PerWorld
                            && cachePerWorldPattern;
                    reqsEnlightenment = at.petrak.hexcasting.api.utils.HexUtils.isOfTag(
                            IXplatAbstractions.INSTANCE.getActionRegistry(), key, HexTags.Actions.REQUIRES_ENLIGHTENMENT);
                    if (cacheResolvedPerWorldAction && cmi$perWorldActionEpoch == lookupEpoch
                        && cmi$perWorldAction != null) {
                        action = cmi$perWorldAction;
                    } else {
                        action = ActionSites.registeredAction(key);
                        if (action == null) {
                            action = Objects.requireNonNull(IXplatAbstractions.INSTANCE.getActionRegistry().get(key)).action();
                        }
                        if (cacheResolvedPerWorldAction) {
                            cmi$perWorldAction = action;
                            cmi$perWorldActionEpoch = lookupEpoch;
                            cmi$perWorldActionRequiresEnlightenment = reqsEnlightenment;
                        }
                    }
                    if (cacheResolvedNormalAction) {
                        cmi$normalActionKey = key;
                        cmi$normalAction = action;
                        cmi$normalActionRequiresEnlightenment = reqsEnlightenment;
                        cmi$normalActionEpoch = lookupEpoch;
                    }
                }

                if (reqsEnlightenment && !vm.getEnv().isEnlightened()) throw new MishapUnenlightened();
            } else if (lookup instanceof PatternShapeMatch.Special special) {
                castedHandler = special.handler;
                if (!inParens && ServerConfig.hexJitFastNumberLiterals
                        && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                        && HexJitRuntime.onServerThread() && FastNumberLiteral.supports(special.handler)) {
                    ExecutionScope.markCompiled();
                    return FastNumberLiteral.execute(vm.getEnv(), vm.getImage(), continuation, self,
                            (SpecialHandlerNumberLiteral) special.handler);
                }
                action = special.handler.act();
            } else if (lookup instanceof PatternShapeMatch.Nothing) {
                if (inParens) {
                    return new CastResult(self, continuation, vm.getImage().withNewParenthesized(self, false),
                            List.of(), ResolvedPatternType.ESCAPED, HexEvalSounds.NORMAL_EXECUTE.get());
                }
                throw new MishapInvalidPattern(self.getPattern());
            } else {
                throw new IllegalStateException();
            }

            if (inParens || action != OpTick.INSTANCE) {
                ExecutionScope scope = lookupScope == null ? ExecutionScope.current() : lookupScope;
                if (scope != null) scope.invalidateCachedTickCounter();
                FastHexOPMediaPool.invalidateCachedMediaAvailability(scope);
            }

            if (!inParens && ServerConfig.hexJitFastAddMotionArguments
                    && FastAddMotionArguments.supports(action)
                    && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                    && HexJitRuntime.onServerThread()) {
                ExecutionScope.markCompiled();
                return FastAddMotionArguments.operate(vm.getEnv(), vm.getImage(), continuation, self);
            }

            if (!inParens && FastTickAction.supports(action)) {
                ExecutionScope.markCompiled();
                return FastTickAction.operate(vm.getEnv(), vm.getImage(), continuation, self, lookupScope);
            }

            IOperationResult result;
            ResolvedPatternType resolutionType;
            if (inParens) {
                result = action.operateInParens(vm.getEnv(), vm.getImage(), continuation, self);
                resolutionType = ((ParenthesizedOperationResult) result).getResolutionType();
            } else {
                result = action.operate(vm.getEnv(), vm.getImage(), continuation);
                resolutionType = ResolvedPatternType.EVALUATED;
            }

            return new CastResult(self, result.getNewContinuation(), result.getNewImage(), result.getSideEffects(),
                    resolutionType, result.getSound());
        } catch (Mishap mishap) {
            boolean wipeParens = continuation instanceof SpellContinuation.NotDone cnd
                    && cnd.getFrame() instanceof FrameEvaluate frameEval && frameEval.isMetacasting();
            var image = wipeParens ? vm.getImage().withResetEscape() : null;
            var castedName = castedKey != null
                    ? HexAPI.instance().getActionI18n(castedKey, reqsEnlightenment)
                    : castedHandler == null ? null : castedHandler.getName();
            return new CastResult(self, continuation, image,
                    List.of(new at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect.DoMishap(
                            mishap, new Mishap.Context(self.getPattern(), castedName))),
                    mishap.resolutionType(vm.getEnv()), HexEvalSounds.MISHAP.get());
        }
    }

    @WrapOperation(method = "lookupAndOperate", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/castables/Action;operate(" +
                    "Lat/petrak/hexcasting/api/casting/eval/CastingEnvironment;" +
                    "Lat/petrak/hexcasting/api/casting/eval/vm/CastingImage;" +
                    "Lat/petrak/hexcasting/api/casting/eval/vm/SpellContinuation;)" +
                    "Lat/petrak/hexcasting/api/casting/eval/OperationResult;"))
    private OperationResult cmi$operate(Action action, CastingEnvironment env, CastingImage image,
                                        SpellContinuation continuation, Operation<OperationResult> original) throws Throwable {
        boolean compileActions = ServerConfig.hexJitCompileActions
                || ServerConfig.hexJitMode == ServerConfig.HexJitMode.PROFILE;
        CompiledCall code = compileActions ? ActionSites.acquire(action, false) : null;
        if (code == null) return original.call(action, env, image, continuation);
        ExecutionScope.markCompiled();
        return (OperationResult) code.call(action, env, image, continuation, null);
    }

    @WrapOperation(method = "lookupAndOperate", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/castables/Action;operateInParens(" +
                    "Lat/petrak/hexcasting/api/casting/eval/CastingEnvironment;" +
                    "Lat/petrak/hexcasting/api/casting/eval/vm/CastingImage;" +
                    "Lat/petrak/hexcasting/api/casting/eval/vm/SpellContinuation;" +
                    "Lat/petrak/hexcasting/api/casting/iota/Iota;)" +
                    "Lat/petrak/hexcasting/api/casting/eval/ParenthesizedOperationResult;"))
    private ParenthesizedOperationResult cmi$operateInParens(Action action, CastingEnvironment env,
                                                              CastingImage image, SpellContinuation continuation,
                                                              Iota iota,
                                                              Operation<ParenthesizedOperationResult> original) throws Throwable {
        boolean compileActions = ServerConfig.hexJitCompileActions
                || ServerConfig.hexJitMode == ServerConfig.HexJitMode.PROFILE;
        CompiledCall code = compileActions ? ActionSites.acquire(action, true) : null;
        if (code == null) return original.call(action, env, image, continuation, iota);
        ExecutionScope.markCompiled();
        return (ParenthesizedOperationResult) code.call(action, env, image, continuation, iota);
    }
}
