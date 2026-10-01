package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageRenderOwnership;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppress only the Create box mesh; preserve EntityRenderer's nameplate and other behavior. */
@Mixin(value=PackageRenderer.class,remap=false)
public abstract class PackageRendererOwnershipMixin {
    @Inject(method="renderBox",
            at=@At("HEAD"),cancellable=true,remap=false)
    private static void cmi$gpuOwned(Entity entity,float yaw,PoseStack pose,MultiBufferSource buffers,
                                    int light,PartialModel model,CallbackInfo ci) {
        if(entity instanceof PackageEntity box && PackageRenderOwnership.renderedByGpu(box))ci.cancel();
    }
}
