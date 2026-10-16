package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.casting.ExecutorChainAccess;

import dev.enjarai.trickster.spell.execution.WrappedSpellExecutionManager;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import java.util.Optional;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WrappedSpellExecutionManager.class, remap = false)
public abstract class WrappedExecutionManagerChainMixin {
    @Shadow private Optional<DefaultSpellExecutor> executor;
    @Inject(method = {"killOuter", "killAll", "setExecutor"}, at = @At("HEAD"))
    private void cmi$release(CallbackInfo ci) {
        executor.ifPresent(e -> CastChains.release(((ExecutorChainAccess)(Object)e).cmi$executorChain(), e));
    }
    @Inject(method = "setSpell(Ldev/enjarai/trickster/spell/SpellPart;Ljava/util/List;)Z", at = @At("HEAD"))
    private void cmi$replace(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        executor.ifPresent(e -> CastChains.release(((ExecutorChainAccess)(Object)e).cmi$executorChain(), e));
    }
}
