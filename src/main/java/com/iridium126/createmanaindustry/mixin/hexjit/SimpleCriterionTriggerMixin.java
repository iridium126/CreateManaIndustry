package com.iridium126.createmanaindustry.mixin.hexjit;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastHexOPMediaPool;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Commit deferred media before any criterion reward can observe player attributes or start a nested cast. */
@Mixin(SimpleCriterionTrigger.class)
public abstract class SimpleCriterionTriggerMixin {
    @Inject(method = "addPlayerListener(Lnet/minecraft/server/PlayerAdvancements;Lnet/minecraft/advancements/CriterionTrigger$Listener;)V",
            at = @At("HEAD"))
    private void cmi$invalidateSpendMediaCacheOnAdd(PlayerAdvancements advancements,
                                                     CriterionTrigger.Listener<?> listener,
                                                     CallbackInfo ci) {
        cmi$invalidateSpendMediaCache();
    }

    @Inject(method = "removePlayerListener(Lnet/minecraft/server/PlayerAdvancements;Lnet/minecraft/advancements/CriterionTrigger$Listener;)V",
            at = @At("HEAD"))
    private void cmi$invalidateSpendMediaCacheOnRemove(PlayerAdvancements advancements,
                                                        CriterionTrigger.Listener<?> listener,
                                                        CallbackInfo ci) {
        cmi$invalidateSpendMediaCache();
    }

    @Inject(method = "removePlayerListeners(Lnet/minecraft/server/PlayerAdvancements;)V",
            at = @At("HEAD"))
    private void cmi$invalidateSpendMediaCacheOnClear(PlayerAdvancements advancements, CallbackInfo ci) {
        cmi$invalidateSpendMediaCache();
    }

    @WrapOperation(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Predicate;)V",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/advancements/CriterionTrigger$Listener;run(Lnet/minecraft/server/PlayerAdvancements;)V"))
    private void cmi$flushDeferredMediaBeforeMatchingAward(CriterionTrigger.Listener<?> listener,
                                                           PlayerAdvancements advancements,
                                                           Operation<Void> original) {
        ExecutionScope scope = ExecutionScope.current();
        if (scope != null) scope.invalidateSpendMediaTriggerCache();
        FastHexOPMediaPool.flushDeferredPersonalMediaWrites(advancements);
        original.call(listener, advancements);
    }

    private static void cmi$invalidateSpendMediaCache() {
        ExecutionScope scope = ExecutionScope.current();
        if (scope != null) scope.invalidateSpendMediaTriggerCache();
    }
}
