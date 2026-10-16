package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.eval.ExecutionClientView;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.Iota;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateMishaps;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import java.util.List;

@Mixin(value=CastingVM.class,remap=false,priority=1200)
public abstract class SharedFateVmMixin {
    @WrapMethod(method="queueExecuteAndWrapIotas")
    private ExecutionClientView cmi$trackVm(List<Iota> iotas,ServerLevel level,Operation<ExecutionClientView> original){
        try(var ignored=SharedFateMishaps.enterVm((CastingVM)(Object)this)){return original.call(iotas,level);}
    }
}
