package com.iridium126.createmanaindustry.mixin.sodium;

import com.iridium126.createmanaindustry.client.particles.shaderpack.FeatherLeafMesh;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSection.class, remap = false)
abstract class FeatherLeafSectionMixin {
    @Inject(method = "delete", at = @At("HEAD"))
    private void cmi$forgetLeafMesh(CallbackInfo ci) {
        var section = (RenderSection) (Object) this;
        FeatherLeafMesh.remove(section.getPosition().asLong(), section);
    }
}
