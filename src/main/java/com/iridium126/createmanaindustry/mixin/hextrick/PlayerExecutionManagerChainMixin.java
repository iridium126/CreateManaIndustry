package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.*;
import dev.enjarai.trickster.spell.SpellExecutor;
import dev.enjarai.trickster.spell.execution.PlayerSpellExecutionManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Optional;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value = PlayerSpellExecutionManager.class, remap = false)
public abstract class PlayerExecutionManagerChainMixin {
    @Shadow @Final private Int2ObjectMap<SpellExecutor> spells;
    @Inject(method = "queue", at = @At("RETURN"))
    private void cmi$retain(SpellExecutor executor, CallbackInfoReturnable<Optional<Integer>> cir) {
        if (cir.getReturnValue().isPresent() && executor instanceof ExecutorChainAccess access)
            access.cmi$executorChain().retain(executor);
    }
    @Inject(method = "kill", at = @At("HEAD"))
    private void cmi$kill(int slot, CallbackInfoReturnable<Boolean> cir) { cmi$release(spells.get(slot)); }
    @Inject(method = "killAll", at = @At("HEAD"))
    private void cmi$killAll(CallbackInfo ci) { spells.values().forEach(PlayerExecutionManagerChainMixin::cmi$release); }
    @Unique private static void cmi$release(SpellExecutor executor) {
        if (executor instanceof ExecutorChainAccess access) CastChains.release(access.cmi$executorChain(), executor);
    }
}
