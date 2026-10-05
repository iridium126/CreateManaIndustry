package com.iridium126.createmanaindustry.bootstrap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.InputConstants;
import dev.enjarai.trickster.util.InputContext;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyMappingLookup;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.List;

/** Bytecode template copied into Trickster's mixin; this class is never loaded. */
final class TricksterKeyBindingPatch {
    // Keep Trickster's optional-injection policy: Connector checks against the
    // clean vanilla class, where NeoForge's getAll calls do not exist. These
    // targets are present in the actual NeoForge KeyMapping loaded by Mixin.
    @WrapOperation(method = "click", at = @At(value = "INVOKE", target =
            "Lnet/neoforged/neoforge/client/settings/KeyMappingLookup;getAll(Lcom/mojang/blaze3d/platform/InputConstants$Key;)Ljava/util/Collection;",
            remap = false), remap = false, require = 0)
    private static Collection<KeyMapping> applyKeybindContext(KeyMappingLookup lookup, InputConstants.Key key,
                                                              Operation<Collection<KeyMapping>> original) {
        KeyMapping binding = getContextKeyBinding(key);
        return binding == null ? original.call(lookup, key) : List.of(binding);
    }

    @WrapOperation(method = "set", at = @At(value = "INVOKE", target =
            "Lnet/neoforged/neoforge/client/settings/KeyMappingLookup;getAll(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)Ljava/util/Collection;",
            remap = false), remap = false, require = 0)
    private static Collection<KeyMapping> applyKeybindContext2(KeyMappingLookup lookup, InputConstants.Key key,
                                                               boolean released, Operation<Collection<KeyMapping>> original) {
        KeyMapping binding = getContextKeyBinding(key);
        Collection<KeyMapping> bindings = original.call(lookup, key, released);
        if (binding == null) {
            return bindings;
        }
        for (KeyMapping originalBinding : bindings) {
            originalBinding.setDown(false);
        }
        return List.of(binding);
    }

    @WrapOperation(method = "resetMapping", at = @At(value = "INVOKE", target =
            "Lnet/neoforged/neoforge/client/settings/KeyMappingLookup;put(Lcom/mojang/blaze3d/platform/InputConstants$Key;Lnet/minecraft/client/KeyMapping;)V",
            remap = false), remap = false, require = 0)
    private static void skipAddingContextualKeys(KeyMappingLookup lookup, InputConstants.Key key,
                                                KeyMapping binding, Operation<Void> original) {
        if (!InputContext.contextsContain(binding)) {
            original.call(lookup, key, binding);
        }
    }

    // Calls are redirected to the original mixin's existing helper.
    private static KeyMapping getContextKeyBinding(InputConstants.Key key) {
        throw new UnsupportedOperationException("Bytecode template only");
    }
}
