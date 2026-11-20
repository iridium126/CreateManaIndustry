package com.iridium126.createmanaindustry.mixin.render;

import com.iridium126.createmanaindustry.client.xessfg.XessFgRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockEntityRenderDispatcher.class)
abstract class XessFgBlockEntityMotionMixin {
    @WrapOperation(method = "setupAndRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
    private static <T extends BlockEntity> void cmi$motion(BlockEntityRenderer<T> renderer, T entity, float partial,
                                                           PoseStack pose, MultiBufferSource buffer, int light,
                                                           int overlay, Operation<Void> render) {
        try (var scope = XessFgRuntime.entityMotion(entity)) {
            render.call(renderer, entity, partial, pose, buffer, light, overlay);
        }
    }
}
