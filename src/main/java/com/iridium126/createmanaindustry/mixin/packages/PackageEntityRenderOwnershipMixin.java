package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageRenderOwnership;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native entity bookkeeping must not draw a second shadow or a stale authority hitbox. */
@Mixin(EntityRenderDispatcher.class)
public abstract class PackageEntityRenderOwnershipMixin {
    @Inject(method="renderShadow",at=@At("HEAD"),cancellable=true)
    private static void cmi$gpuShadow(PoseStack pose,MultiBufferSource buffers,Entity entity,float weight,
                                      float partialTick,LevelReader level,float size,CallbackInfo ci) {
        if(entity instanceof PackageEntity box && PackageRenderOwnership.renderedByGpu(box))ci.cancel();
    }
    @Inject(method="renderHitbox",at=@At("HEAD"),cancellable=true)
    private static void cmi$retainedHitbox(PoseStack pose,VertexConsumer buffer,Entity entity,
                                          float partialTick,float red,float green,float blue,CallbackInfo ci) {
        if(entity instanceof PackageEntity box && PackageRenderOwnership.authorityOwned(box))ci.cancel();
    }
    @Inject(method="renderServerSideHitbox",at=@At("HEAD"),cancellable=true)
    private static void cmi$retainedServerHitbox(PoseStack pose,Entity entity,MultiBufferSource buffers,CallbackInfo ci) {
        if(entity instanceof PackageEntity box && PackageRenderOwnership.authorityOwned(box))ci.cancel();
    }
}
