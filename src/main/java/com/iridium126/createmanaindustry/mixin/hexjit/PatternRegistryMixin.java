package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.common.casting.PatternRegistryManifest;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invalidates call-site guards whenever Hexcasting rebuilds its signature-to-action lookup. */
@Mixin(value = PatternRegistryManifest.class, remap = false)
public abstract class PatternRegistryMixin {
    @Inject(method = "processRegistry(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("HEAD"))
    private static void cmi$invalidate(ServerLevel level, CallbackInfo callback) {
        HexJitRuntime.invalidate("pattern registry rebuilt");
    }
}
