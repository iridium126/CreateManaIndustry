package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.advancements.SpendMediaTrigger;
import at.petrak.hexcasting.api.advancements.SpendMediaTrigger.Instance;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.SpendMediaTriggerCache;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Avoids constructing an unused LootContext while preserving every Spend Media listener award. */
@Mixin(value = SpendMediaTrigger.class, remap = false)
public abstract class SpendMediaTriggerMixin {
    @Inject(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;JJ)V", at = @At("HEAD"), cancellable = true)
    private void cmi$skipUnusedLootContext(ServerPlayer player, long mediaSpent, long mediaWasted,
                                           CallbackInfo ci) {
        ExecutionScope scope = ExecutionScope.current();
        if (!ServerConfig.hexJitFastSpendMediaTrigger
                || ServerConfig.hexJitMode != ServerConfig.HexJitMode.AUTO
                || !HexJitRuntime.enabled() || !HexJitRuntime.onServerThread()
                || scope == null || !scope.loopTickBatchEnabled()) {
            if (scope != null) scope.flushDeferredMediaUsedStat();
            return;
        }

        SpendMediaTrigger trigger = (SpendMediaTrigger) (Object) this;
        ExecutionScope.SpendMediaListenerRule[] rules =
                scope.cachedSpendMediaListenerRules(trigger, player);
        if (rules == null) {
            PlayerAdvancements advancements = player.getAdvancements();
            Map<PlayerAdvancements, Set<CriterionTrigger.Listener<?>>> listenersByPlayer =
                    ((SimpleCriterionTriggerAccessor) this).cmi$getPlayers();
            Set<CriterionTrigger.Listener<?>> listeners = listenersByPlayer.get(advancements);
            if (listeners == null || listeners.isEmpty()) {
                rules = new ExecutionScope.SpendMediaListenerRule[0];
            } else {
                List<ExecutionScope.SpendMediaListenerRule> supportedListeners = new ArrayList<>(listeners.size());
                for (CriterionTrigger.Listener<?> listener : listeners) {
                    if (!(listener.trigger() instanceof Instance instance)) {
                        scope.flushDeferredMediaUsedStat();
                        scope.invalidateSpendMediaTriggerCache();
                        return;
                    }
                    ContextAwarePredicate playerPredicate = instance.player().orElse(null);
                    // The Hexcasting Instance implementation always returns empty. Fall back to upstream
                    // behavior if a compatible addon changes that contract.
                    if (playerPredicate != null) {
                        scope.flushDeferredMediaUsedStat();
                        scope.invalidateSpendMediaTriggerCache();
                        return;
                    }
                    supportedListeners.add(ExecutionScope.SpendMediaListenerRule.from(listener, instance));
                }
                rules = supportedListeners.toArray(new ExecutionScope.SpendMediaListenerRule[0]);
            }
            scope.rememberSpendMediaListenerRules(trigger, player, rules);
        }
        if (SpendMediaTriggerCache.dispatchKnownRules(player, mediaSpent, mediaWasted, rules)) ci.cancel();
    }
}
