package com.iridium126.createmanaindustry.mixin.hexjit;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cast-local virtual balance for HexOP's optional PersonalManaHolder. */
@Mixin(targets = "io.yukkuric.hexop.personal_mana.PersonalManaHolder", remap = false)
public abstract class PersonalManaHolderMixin {
    @Inject(method = "getMedia()J", at = @At("HEAD"), cancellable = true)
    private void cmi$readDeferredMedia(CallbackInfoReturnable<Long> cir) {
        ExecutionScope scope = ExecutionScope.current();
        if (scope != null && scope.hasDeferredPersonalMediaValue(this))
            cir.setReturnValue(scope.deferredPersonalMediaValue());
    }

    @Inject(method = "getMedia()J", at = @At("RETURN"))
    private void cmi$seedDeferredMedia(CallbackInfoReturnable<Long> cir) {
        ExecutionScope scope = ExecutionScope.current();
        if (scope != null) scope.observePersonalMediaRead(this, cir.getReturnValue());
    }

    @Inject(method = "setMedia(J)V", at = @At("HEAD"), cancellable = true)
    private void cmi$deferMediaWrite(long value, CallbackInfo ci) {
        ExecutionScope scope = ExecutionScope.current();
        if (scope != null && scope.deferPersonalMediaWrite(this, value)) ci.cancel();
    }
}
