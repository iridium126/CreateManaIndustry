package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ExecutionClientView;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.ContinuationFrame;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitConfig;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CastingVM.class, remap = false)
public abstract class CastingVMMixin {
    @WrapMethod(method = "queueExecuteAndWrapIotas")
    private ExecutionClientView cmi$scope(List<Iota> iotas, ServerLevel level,
                                          Operation<ExecutionClientView> original) {
        if (!HexJitConfig.skipObservers || !HexJitRuntime.enabled()) return original.call(iotas, level);
        try (ExecutionScope ignored = ExecutionScope.enter()) {
            return original.call(iotas, level);
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
        ExecutionScope scope = HexJitConfig.skipObservers ? ExecutionScope.current() : null;
        if (scope != null) scope.startStep();
        return original.call(frame, continuation, level, vm);
    }

    @WrapOperation(method = "queueExecuteAndWrapIotas", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/eval/CastingEnvironment;postExecution(" +
                    "Lat/petrak/hexcasting/api/casting/eval/CastResult;)V"))
    private void cmi$notify(CastingEnvironment env, CastResult result, Operation<Void> original) {
        ExecutionScope scope = HexJitConfig.skipObservers ? ExecutionScope.current() : null;
        if (scope == null) {
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
}
