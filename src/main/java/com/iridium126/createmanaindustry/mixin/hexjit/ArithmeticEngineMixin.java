package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.arithmetic.engine.ArithmeticEngine;
import at.petrak.hexcasting.api.casting.arithmetic.engine.HashCons;
import at.petrak.hexcasting.api.casting.arithmetic.operator.Operator;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.OperationResult;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.*;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.HashMap;
import java.util.Map;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ArithmeticEngine.class, remap = false)
public abstract class ArithmeticEngineMixin {
    @Shadow @Final private Map<HexPattern, ?> operators;
    @Shadow @Final private Map<HashCons, Operator> cache;
    @Unique private Map<HexPattern, ArithmeticSite> cmi$sites;
    @Unique private long cmi$epoch = -1;

    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void cmi$run(HexPattern pattern, CastingEnvironment env, CastingImage image,
                         SpellContinuation continuation, CallbackInfoReturnable<OperationResult> result) throws Throwable {
        CompiledCall code = HexJitRuntime.arithmeticCode();
        if (code == null || ServerConfig.hexJitMode != ServerConfig.HexJitMode.AUTO || !HexJitRuntime.enabled()) return;
        Object raw = operators.get(pattern);
        // Unknown ABI, missing pattern and underflow are left to the original method, before reading any Iota types.
        if (!(raw instanceof ArithmeticCandidates candidates) || candidates.cmi$arity() < 0
                || image.getStack().size() < candidates.cmi$arity()) return;
        long epoch = HexJitRuntime.generation();
        if (cmi$sites == null || epoch != cmi$epoch) {
            cmi$sites = new HashMap<>(); cmi$epoch = epoch;
        }
        ArithmeticSite site = cmi$sites.get(pattern);
        if (site == null || !site.valid(candidates, epoch)) {
            if (cmi$sites.size() >= ServerConfig.hexJitMaxUnits) cmi$sites.clear();
            site = new ArithmeticSite(candidates, epoch);
            cmi$sites.put(pattern, site);
        }
        result.setReturnValue(site.execute(pattern, cache, env, image, continuation, code));
    }

    /** Observe resolved arithmetic calls after the interpreter has selected its Operator. */
    @WrapOperation(method = "run", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/arithmetic/operator/Operator;operate(" +
                    "Lat/petrak/hexcasting/api/casting/eval/CastingEnvironment;" +
                    "Lat/petrak/hexcasting/api/casting/eval/vm/CastingImage;" +
                    "Lat/petrak/hexcasting/api/casting/eval/vm/SpellContinuation;)" +
                    "Lat/petrak/hexcasting/api/casting/eval/OperationResult;"))
    private OperationResult cmi$compileCall(Operator operator, CastingEnvironment env, CastingImage image,
                                             SpellContinuation continuation,
                                             Operation<OperationResult> original) throws Throwable {
        if (!HexJitRuntime.enabled()) return original.call(operator, env, image, continuation);
        CompiledCall code = HexJitRuntime.acquire(ArithmeticSite.CALL_SITE, ArithmeticSite.CALL);
        if (code == null) return original.call(operator, env, image, continuation);
        HexJitRuntime.publishArithmetic(code);
        ExecutionScope.markCompiled();
        return (OperationResult) code.call(operator, env, image, continuation, null);
    }
}
