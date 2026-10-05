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
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ActionSites;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.CompiledCall;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastTickAction;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastAddMotionArguments;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastNumberLiteral;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.JitCompatibility;
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
public abstract class PatternIotaMixin {
    @org.spongepowered.asm.mixin.Unique private long cmi$normalPatternEpoch = Long.MIN_VALUE;
    @org.spongepowered.asm.mixin.Unique private ResourceKey<ActionRegistryEntry> cmi$normalPatternKey;

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
            long lookupEpoch = HexJitRuntime.generation();
            boolean cacheNormalPattern = ServerConfig.hexJitCacheNormalPatternLookup
                    && ServerConfig.hexJitMode != ServerConfig.HexJitMode.OFF
                    && HexJitRuntime.onServerThread() && JitCompatibility.specialHandlerLookupReady();
            ResourceKey<ActionRegistryEntry> cachedNormalKey = cacheNormalPattern
                    && cmi$normalPatternEpoch == lookupEpoch ? cmi$normalPatternKey : null;
            at.petrak.hexcasting.api.casting.PatternShapeMatch lookup;
            if (cachedNormalKey != null) {
                // Normal registry matches have precedence over per-world patterns and dynamic
                // handlers. Recreate the same fresh match value; keep environment checks below.
                lookup = new PatternShapeMatch.Normal(cachedNormalKey);
            } else {
                lookup = PatternRegistryManifest.matchPattern(self.getPattern(), vm.getEnv());
                if (cacheNormalPattern && lookup instanceof PatternShapeMatch.Normal normal) {
                    cmi$normalPatternKey = normal.key;
                    cmi$normalPatternEpoch = lookupEpoch;
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

                reqsEnlightenment = at.petrak.hexcasting.api.utils.HexUtils.isOfTag(
                        IXplatAbstractions.INSTANCE.getActionRegistry(), key, HexTags.Actions.REQUIRES_ENLIGHTENMENT);
                castedKey = key;
                action = ActionSites.registeredAction(key);
                if (action == null) {
                    action = Objects.requireNonNull(IXplatAbstractions.INSTANCE.getActionRegistry().get(key)).action();
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

            if (!inParens && ServerConfig.hexJitFastAddMotionArguments
                    && FastAddMotionArguments.supports(action)
                    && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                    && HexJitRuntime.onServerThread()) {
                ExecutionScope.markCompiled();
                return FastAddMotionArguments.operate(vm.getEnv(), vm.getImage(), continuation, self);
            }

            if (!inParens && FastTickAction.supports(action)) {
                ExecutionScope.markCompiled();
                return FastTickAction.operate(vm.getEnv(), vm.getImage(), continuation, self);
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
