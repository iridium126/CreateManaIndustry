package com.iridium126.createmanaindustry.mixin.particles;

import com.iridium126.createmanaindustry.client.particles.packages.PackageCollisionRuntime;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class PackageLightChunkMixin {
    @Shadow @Final private ClientLevel level;
    @Inject(method="onLightUpdate",at=@At("TAIL"))
    private void cmi$lightChanged(LightLayer layer,SectionPos section,CallbackInfo ci){PackageCollisionRuntime.lightChanged(level,section);}
}
