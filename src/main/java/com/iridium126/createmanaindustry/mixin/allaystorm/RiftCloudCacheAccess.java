package com.iridium126.createmanaindustry.mixin.allaystorm;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla geometry/cache ABI; replay also uses this geometry when Sodium is installed. */
@Mixin(LevelRenderer.class)
public interface RiftCloudCacheAccess {
    @Accessor("generateClouds") boolean cmi$generateClouds();
    @Accessor("generateClouds") void cmi$generateClouds(boolean value);
    @Accessor("cloudBuffer") com.mojang.blaze3d.vertex.VertexBuffer cmi$cloudBuffer();
    @Accessor("cloudBuffer") void cmi$cloudBuffer(com.mojang.blaze3d.vertex.VertexBuffer value);
    @Accessor("prevCloudX") int cmi$prevCloudX();
    @Accessor("prevCloudX") void cmi$prevCloudX(int value);
    @Accessor("prevCloudY") int cmi$prevCloudY();
    @Accessor("prevCloudY") void cmi$prevCloudY(int value);
    @Accessor("prevCloudZ") int cmi$prevCloudZ();
    @Accessor("prevCloudZ") void cmi$prevCloudZ(int value);
    @Accessor("prevCloudColor") net.minecraft.world.phys.Vec3 cmi$prevCloudColor();
    @Accessor("prevCloudColor") void cmi$prevCloudColor(net.minecraft.world.phys.Vec3 value);
    @Accessor("prevCloudsType") net.minecraft.client.CloudStatus cmi$prevCloudsType();
    @Accessor("prevCloudsType") void cmi$prevCloudsType(net.minecraft.client.CloudStatus value);
    @Accessor("cloudsTarget") com.mojang.blaze3d.pipeline.RenderTarget cmi$cloudsTarget();
    @Accessor("cloudsTarget") void cmi$cloudsTarget(com.mojang.blaze3d.pipeline.RenderTarget value);
    @Invoker("buildClouds") com.mojang.blaze3d.vertex.MeshData cmi$buildClouds(com.mojang.blaze3d.vertex.Tesselator tesselator,double x,double y,double z,net.minecraft.world.phys.Vec3 color);
    @Accessor("ticks") int cmi$cloudTicks();
}
