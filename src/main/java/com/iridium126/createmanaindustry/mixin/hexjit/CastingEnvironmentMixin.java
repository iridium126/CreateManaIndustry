package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.CastingEnvironmentObserverAccess;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.SkippablePostExecutionObserver;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CastingEnvironment.class, remap = false)
public abstract class CastingEnvironmentMixin implements CastingEnvironmentObserverAccess {
    @Override
    @Accessor("postExecutions")
    public abstract java.util.List<CastingEnvironmentComponent.PostExecution> cmi$getPostExecutions();

    @Override
    @Accessor("preMediaExtract")
    public abstract java.util.List<?> cmi$getPreMediaExtract();

    @Override
    @Accessor("postMediaExtract")
    public abstract java.util.List<?> cmi$getPostMediaExtract();

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
