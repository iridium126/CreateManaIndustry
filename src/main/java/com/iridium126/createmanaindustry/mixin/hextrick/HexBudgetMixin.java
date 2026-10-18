package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ExecutionClientView;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.ContinuationFrame;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapEvalTooMuch;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import com.iridium126.createmanaindustry.compat.hextrick.execution.BridgeExecutionContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CastingVM.class, remap = false, priority = 1200)
public abstract class HexBudgetMixin {
    @WrapMethod(method = "queueExecuteAndWrapIotas")
    private ExecutionClientView cmi$root(List<Iota> code, ServerLevel world, Operation<ExecutionClientView> original) {
        CastingVM vm = (CastingVM) (Object) this;
        var enclosing = BridgeExecutionContext.current();
        var context = enclosing == null ? new BridgeExecutionContext(world.getServer(), null) : enclosing;
        context.hexLimit(vm.getEnv().maxOpCount(), enclosing == null ? vm.getImage().getOpsConsumed() : 0);
        try (var scope = context.enter()) { return original.call(code, world); }
    }

    @WrapOperation(method = "queueExecuteAndWrapIotas", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/eval/vm/ContinuationFrame;evaluate(Lat/petrak/hexcasting/api/casting/eval/vm/SpellContinuation;Lnet/minecraft/server/level/ServerLevel;Lat/petrak/hexcasting/api/casting/eval/vm/CastingVM;)Lat/petrak/hexcasting/api/casting/eval/CastResult;"))
    private CastResult cmi$step(ContinuationFrame frame, SpellContinuation continuation, ServerLevel world,
            CastingVM vm, Operation<CastResult> original) {
        long before = vm.getImage().getOpsConsumed();
        var context = BridgeExecutionContext.current();
        if (context == null) return original.call(frame, continuation, world, vm);
        try (var accounting = context.hexFrame()) {
            CastResult result = original.call(frame, continuation, world, vm);
            if (result.getNewData() != null
                    && !accounting.account(Math.max(0, result.getNewData().getOpsConsumed() - before))) {
                return new CastResult(result.getCast(), SpellContinuation.Done.INSTANCE, null,
                        List.of(new OperatorSideEffect.DoMishap(new MishapEvalTooMuch(), new Mishap.Context(null, null))),
                        ResolvedPatternType.ERRORED, HexEvalSounds.MISHAP.get());
            }
            return result;
        }
    }
}
