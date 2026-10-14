package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateMishaps;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;

@Mixin(targets="at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect$DoMishap",remap=false)
public abstract class SharedFateMishapMixin {
    @Shadow @Final private Mishap mishap;
    @Shadow @Final private Mishap.Context errorCtx;
    @WrapMethod(method="performEffect")
    private void cmi$shared(CastingVM vm,Operation<Void> original){
        SharedFateMishaps.perform(vm,mishap,errorCtx,()->original.call(vm));
    }
}
