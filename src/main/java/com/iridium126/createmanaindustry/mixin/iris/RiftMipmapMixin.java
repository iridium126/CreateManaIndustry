package com.iridium126.createmanaindustry.mixin.iris;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.iridium126.createmanaindustry.client.allaystorm.rift.*;
@Mixin(value=net.irisshaders.iris.gl.IrisRenderSystem.class,remap=false)
public abstract class RiftMipmapMixin {
    @WrapMethod(method="generateMipmaps(II)V")
    private static void cmi$conditionalMips(int texture,int target,Operation<Void> original){
        if(SkyRenderScope.active()&&RiftVisibility.conditional())RiftTargetTransfer.mipmaps(texture);else original.call(texture,target);
    }
}
