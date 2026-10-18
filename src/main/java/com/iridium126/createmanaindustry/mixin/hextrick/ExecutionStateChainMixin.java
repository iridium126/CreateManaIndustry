package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.casting.ChainState;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.execution.ExecutionState;
import java.util.List;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value = ExecutionState.class, remap = false)
public abstract class ExecutionStateChainMixin implements ChainState {
    @Unique private CastChain cmi$chain;
    @Shadow private boolean hasUsedMana;
    public CastChain cmi$getChain() { if (cmi$chain == null) cmi$chain = CastChains.inherit(); return cmi$chain; }
    public void cmi$setChain(CastChain chain) { cmi$chain = chain; }
    public void cmi$markResourceUse() { hasUsedMana = true; }
    @Inject(method = "<init>", at = @At("RETURN"))
    private void cmi$inherit(CallbackInfo ci) { cmi$getChain(); }
    @Inject(method = "recurseOrThrow", at = @At("RETURN"))
    private void cmi$recurse(List<Fragment> args, CallbackInfoReturnable<ExecutionState> cir) {
        ((ChainState)cir.getReturnValue()).cmi$setChain(cmi$getChain());
    }
}
