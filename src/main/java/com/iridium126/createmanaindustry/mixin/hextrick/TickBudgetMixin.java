package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.hextrick.execution.BridgeExecutionContext;
import dev.enjarai.trickster.spell.execution.TickData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TickData.class, remap = false)
public abstract class TickBudgetMixin {
    @Shadow private int executionLimit;

    @Inject(method = "incrementExecutions", at = @At("RETURN"))
    private void cmi$count(CallbackInfo callback) {
        var context = BridgeExecutionContext.current();
        if (context != null) context.trickInstruction();
    }

    @Inject(method = "isExecutionLimitReached", at = @At("RETURN"), cancellable = true)
    private void cmi$limit(CallbackInfoReturnable<Boolean> callback) {
        var context = BridgeExecutionContext.current();
        if (context != null && context.trickReached(executionLimit)) callback.setReturnValue(true);
    }
}
