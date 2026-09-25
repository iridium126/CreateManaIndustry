package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.config.ServerConfig;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.SkippablePostExecutionObserver;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CastingEnvironment.class, remap = false)
public abstract class CastingEnvironmentMixin {
    @WrapOperation(method = "postExecution", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/eval/CastingEnvironmentComponent$PostExecution;" +
                    "onPostExecution(Lat/petrak/hexcasting/api/casting/eval/CastResult;)V"))
    private void cmi$observe(CastingEnvironmentComponent.PostExecution observer, CastResult result,
                             Operation<Void> original) {
        if (ServerConfig.hexJitSkipObservers && observer instanceof SkippablePostExecutionObserver
                && ExecutionScope.maySkip()) return;
        original.call(observer, result);
    }
}
