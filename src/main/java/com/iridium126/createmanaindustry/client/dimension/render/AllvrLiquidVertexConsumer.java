package com.iridium126.createmanaindustry.client.dimension.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;

/** Transforms LiquidBlockRenderer's section-local vertices into camera space. */
public record AllvrLiquidVertexConsumer(VertexConsumer delegate, PoseStack pose,
                                        BlockPos blockPos) implements VertexConsumer {

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        int dx = this.blockPos.getX() & 15;
        int dy = this.blockPos.getY() & 15;
        int dz = this.blockPos.getZ() & 15;
        return this.delegate.addVertex(this.pose.last().pose(), x - dx, y - dy, z - dz);
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        return this.delegate.setColor(red, green, blue, alpha);
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        return this.delegate.setUv(u, v);
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this.delegate.setUv1(u, v);
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return this.delegate.setUv2(u, v);
    }

    @Override
    public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
        return this.delegate.setNormal(this.pose.last(), normalX, normalY, normalZ);
    }
}
