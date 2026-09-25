package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.PatternShapeMatch;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.OperationResult;
import at.petrak.hexcasting.api.casting.eval.ParenthesizedOperationResult;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ActionSites;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.CompiledCall;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Guarded call-site specialization; all lookup, checks, and result handling stay in Hexcasting. */
@Mixin(value = PatternIota.class, remap = false)
public abstract class PatternIotaMixin {
    @WrapOperation(method = "lookupAndOperate", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/castables/Action;operate(" +
                    "Lat/petrak/hexcasting/api/casting/eval/CastingEnvironment;" +
                    "Lat/petrak/hexcasting/api/casting/eval/vm/CastingImage;" +
                    "Lat/petrak/hexcasting/api/casting/eval/vm/SpellContinuation;)" +
                    "Lat/petrak/hexcasting/api/casting/eval/OperationResult;"))
    private OperationResult cmi$operate(Action action, CastingEnvironment env, CastingImage image,
                                        SpellContinuation continuation, Operation<OperationResult> original,
                                        @Local PatternShapeMatch match) throws Throwable {
        CompiledCall code = match instanceof PatternShapeMatch.Normal ? ActionSites.acquire(action, false) : null;
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
                                                              Operation<ParenthesizedOperationResult> original,
                                                              @Local PatternShapeMatch match) throws Throwable {
        CompiledCall code = match instanceof PatternShapeMatch.Normal ? ActionSites.acquire(action, true) : null;
        if (code == null) return original.call(action, env, image, continuation, iota);
        ExecutionScope.markCompiled();
        return (ParenthesizedOperationResult) code.call(action, env, image, continuation, iota);
    }
}
