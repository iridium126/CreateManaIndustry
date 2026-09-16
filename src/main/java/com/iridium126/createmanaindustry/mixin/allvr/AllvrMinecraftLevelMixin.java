package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.client.dimension.lod.voxy.AllvrVoxyClientIngest;
import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Binds terrain to the installed level before Sodium/Voxy create their renderers. */
@Mixin(Minecraft.class)
public abstract class AllvrMinecraftLevelMixin {

    @Inject(method = "updateLevelInEngines", at = @At("HEAD"))
    private void allvr$bindInstalledLevel(ClientLevel level, CallbackInfo ci) {
        // ClientLevel's constructor fires Load BEFORE setLevel unloads the old
        // world. Binding there lets that old Unload erase the new bridges.
        // Here Minecraft.level has been assigned and the old Unload is over,
        // but LevelRenderer.setLevel has not created the new renderers yet.
        AllvrClientCubeCache.clear();
        AllvrVoxyClientIngest.clear();
        if (level != null) {
            AllvrClientCubeCache.onLevelChanged(level);
            AllvrSodiumBridge.bindLevel(level);
            AllvrVoxyClientIngest.bindLevel(level);
        }
    }
}
