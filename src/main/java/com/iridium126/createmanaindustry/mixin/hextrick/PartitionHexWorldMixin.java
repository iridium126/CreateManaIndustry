package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.eval.ExecutionClientView;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.Iota;
import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import java.util.List;

/** Includes playerless circles and nested calls; JIT guards remain dimension-local. */
@Mixin(value=CastingVM.class,remap=false,priority=2000)
public abstract class PartitionHexWorldMixin {
    @WrapMethod(method="queueExecuteAndWrapIotas")
    private ExecutionClientView cmi$world(List<Iota> iotas,ServerLevel level,Operation<ExecutionClientView> original) {
        try(var ignored=PartitionManager.enterWorld(level)){return original.call(iotas,level);}
    }
}
