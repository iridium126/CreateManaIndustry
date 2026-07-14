package com.iridium126.createmanaindustry.content.kinetics.kineticatomizer;

import static net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;

import com.iridium126.createmanaindustry.CMIPartialModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

public class KineticAtomizerRenderer extends KineticBlockEntityRenderer<KineticAtomizerBlockEntity> {

    public KineticAtomizerRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected SuperByteBuffer getRotatedModel(KineticAtomizerBlockEntity be, BlockState state) {
        Direction facing = state.getValue(FACING);
        return CachedBuffers.partialFacingVertical(CMIPartialModels.KINETIC_ATOMIZER_COG, state, facing);
    }

    @Override
    protected void renderSafe(KineticAtomizerBlockEntity be, float partialTicks,
            PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

        if (!be.isMistActive())
            return;

        FluidStack fluidStack = be.getTankFluid();
        if (fluidStack.isEmpty())
            return;

        renderFluidSurface(be, fluidStack, ms, buffer, light);
    }

    private void renderFluidSurface(KineticAtomizerBlockEntity be, FluidStack fluidStack,
            PoseStack ms, MultiBufferSource buffer, int light) {

        Fluid fluid = fluidStack.getFluid();
        IClientFluidTypeExtensions clientFluid = IClientFluidTypeExtensions.of(fluid);
        FluidType fluidType = fluid.getFluidType();

        ResourceLocation stillTex = clientFluid.getStillTexture(fluidStack);
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(stillTex);

        int tintColor = clientFluid.getTintColor(fluidStack);
        int blockLightIn = (light >> 4) & 0xF;
        int luminosity = Math.max(blockLightIn, fluidType.getLightLevel(fluidStack));
        int packedLight = (light & 0xF00000) | luminosity << 4;

        int a = tintColor >> 24 & 0xff;
        int r = tintColor >> 16 & 0xff;
        int g = tintColor >> 8 & 0xff;
        int b = tintColor & 0xff;

        Direction facing = be.getBlockState().getValue(BlockStateProperties.FACING);
        VertexConsumer vc = buffer.getBuffer(RenderType.translucent());

        float halfSize = 5f / 16f;  // 10px → 10/16 blocks, half = 5/16
        float offset = 4.1f / 16f;    // 4px offset from center toward facing

        ms.pushPose();

        // Move to block center, rotate to face direction, offset forward
        ms.translate(0.5, 0.5, 0.5);
        switch (facing) {
            case NORTH:
                ms.mulPose(Axis.YP.rotationDegrees(180));
                break;
            case SOUTH:
                break;
            case EAST:
                ms.mulPose(Axis.YP.rotationDegrees(90));
                break;
            case WEST:
                ms.mulPose(Axis.YP.rotationDegrees(-90));
                break;
            case UP:
                ms.mulPose(Axis.XP.rotationDegrees(-90));
                break;
            case DOWN:
                ms.mulPose(Axis.XP.rotationDegrees(90));
                break;
        }
        ms.translate(0, 0, offset);

        // Render quad in XY plane (facing Z+), cropped to fluid texture center
        PoseStack.Pose pose = ms.last();
        float cropFactor = 3f / 16f;
        float u0 = sprite.getU0();
        float v0 = sprite.getV0();
        float u1 = sprite.getU1();
        float v1 = sprite.getV1();
        float cu0 = u0 + (u1 - u0) * cropFactor;
        float cv0 = v0 + (v1 - v0) * cropFactor;
        float cu1 = u1 - (u1 - u0) * cropFactor;
        float cv1 = v1 - (v1 - v0) * cropFactor;

        vc.addVertex(pose, -halfSize, -halfSize, 0).setUv(cu0, cv1).setLight(packedLight).setColor(r, g, b, a).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose,  halfSize, -halfSize, 0).setUv(cu1, cv1).setLight(packedLight).setColor(r, g, b, a).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose,  halfSize,  halfSize, 0).setUv(cu1, cv0).setLight(packedLight).setColor(r, g, b, a).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, -halfSize,  halfSize, 0).setUv(cu0, cv0).setLight(packedLight).setColor(r, g, b, a).setNormal(pose, 0, 0, 1);

        ms.popPose();
    }
}
