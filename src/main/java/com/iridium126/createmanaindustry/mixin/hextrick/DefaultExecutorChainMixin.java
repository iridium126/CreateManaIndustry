package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.execution.*;
import dev.enjarai.trickster.spell.execution.executor.*;
import dev.enjarai.trickster.spell.execution.source.SpellSource;
import java.util.Optional;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value = DefaultSpellExecutor.class, remap = false)
public abstract class DefaultExecutorChainMixin implements ExecutorChainAccess {
    @Shadow private ExecutionState state;
    public CastChain cmi$executorChain() { return ((ChainState)state).cmi$getChain(); }
    public void cmi$executorChain(CastChain chain) { ((ChainState)state).cmi$setChain(chain); }
    @WrapMethod(method = "run")
    private Optional<Fragment> cmi$run(SpellSource source, TickData data, Operation<Optional<Fragment>> original) {
        var player = source.getPlayer().orElse(null);
        if (player == null) {
            try (var ignored = CastChains.enter(null)) { return original.call(source, data); }
        }
        CastChain chain = ((ChainState)state).cmi$getChain();
        if (CastChains.belongsTo(player.getUUID())) {
            chain = CastChains.current();
            ((ChainState)state).cmi$setChain(chain);
            return original.call(source, data);
        }
        if (chain.player() != null && !chain.player().equals(player.getUUID())) chain = new CastChain();
        chain = CastChains.attach(chain, player, this);
        ((ChainState)state).cmi$setChain(chain);
        boolean finished = true;
        try (var ignored = CastChains.enter(chain)) {
            Optional<Fragment> result = original.call(source, data);
            finished = result.isPresent();
            return result;
        } finally { if (finished) CastChains.release(chain, this); }
    }
    @Inject(method = "type", at = @At("HEAD"), cancellable = true)
    private void cmi$persist(CallbackInfoReturnable<SpellExecutorType<?>> cir) {
        if (((ChainState)state).cmi$getChain().player() != null) cir.setReturnValue(GreatTricks.CHAIN_EXECUTOR);
    }
}
