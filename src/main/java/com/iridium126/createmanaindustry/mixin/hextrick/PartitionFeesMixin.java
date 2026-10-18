package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionFees;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.exception.blunder.WrappedBlunder;
import dev.enjarai.trickster.spell.trick.Trick;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value=Trick.class,remap=false)
public abstract class PartitionFeesMixin {
    @WrapMethod(method="activate")
    private EvaluationResult cmi$preflight(SpellContext ctx,List<Fragment> args,Operation<EvaluationResult> original) {
        try{PartitionFees.check(this,ctx,args);}catch(PartitionFees.InfiniteDistanceBlunder failure){throw new WrappedBlunder(new TrickContext((Trick<?>)(Object)this,args),failure).attachTrace(ctx.state().getStacktrace());}
        return original.call(ctx,args);
    }
}
