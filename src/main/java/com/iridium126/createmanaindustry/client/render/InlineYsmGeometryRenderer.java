package com.iridium126.createmanaindustry.client.render;

import com.iridium126.createmanaindustry.compat.hexcasting.ysm.InlineYsmGeometryData;
import com.iridium126.createmanaindustry.compat.ysm.render.YsmGeometryThumbnail;
import com.samsthenerd.inline.api.client.GlowHandling;
import com.samsthenerd.inline.api.client.InlineRenderer;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Renders complete static YSM geometry in a cell matching the active font's line height. */
public final class InlineYsmGeometryRenderer implements InlineRenderer<InlineYsmGeometryData> {
    public static final InlineYsmGeometryRenderer INSTANCE = new InlineYsmGeometryRenderer();
    private static final int MAGNIFIED_SIZE = YsmGeometryThumbnail.SIZE;
    private static final int MAGNIFIED_GAP = 12;
    private static final int SCREEN_PADDING = 4;

    private InlineYsmGeometryRenderer() {}
    @Override public ResourceLocation getId() { return InlineYsmGeometryData.RENDERER_ID; }
    @Override public GlowHandling getGlowPreference(InlineYsmGeometryData data) { return new GlowHandling.None(); }
    @Override public int charWidth(InlineYsmGeometryData data, Style style, int codepoint) { return lineHeight(); }

    @Override public int render(InlineYsmGeometryData data, GuiGraphics graphics, int index, Style style,
            int codepoint, TextRenderingContext context) {
        if (context.isGlowy()) return charWidth(data, style, codepoint);
        YsmClientPreviews.Entry entry = YsmClientPreviews.get(data);
        var pose = graphics.pose();
        int fontLineHeight = lineHeight();
        int cell = fontLineHeight;
        pose.pushPose();
        pose.translate(0.0f, (fontLineHeight - cell) / 2.0f, 0.0f);
        if (entry.texture != null) {
            graphics.blit(entry.texture, 0, 0, cell, cell, 0.0F, 0.0F,
                    YsmGeometryThumbnail.SIZE, YsmGeometryThumbnail.SIZE,
                    YsmGeometryThumbnail.SIZE, YsmGeometryThumbnail.SIZE);
            HoverPoint hover = hoveredPoint(pose, context, cell);
            if (hover != null) drawMagnified(entry.texture, graphics, pose, hover);
        } else if (entry.error != null) {
            graphics.fill(1, 1, cell - 1, cell - 1, 0x993f1720);
            graphics.drawString(Minecraft.getInstance().font, "!", cell / 2 - 2, cell / 2 - 4, 0xffff7777, false);
        } else {
            graphics.fill(1, 1, cell - 1, cell - 1, 0x66555d66);
            graphics.fill(2, cell / 2, cell - 2, cell / 2 + 1, 0xffa9d68b);
            graphics.fill(cell / 2, 2, cell / 2 + 1, cell - 2, 0xffa9d68b);
        }
        pose.popPose();
        return charWidth(data, style, codepoint);
    }

    private static int lineHeight() {
        return Minecraft.getInstance().font.lineHeight;
    }

    private static HoverPoint hoveredPoint(com.mojang.blaze3d.vertex.PoseStack pose,
            TextRenderingContext context, int cell) {
        if (!InlineRenderer.isFlat(pose, context.layerType())) return null;

        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        int screenWidth = window.getScreenWidth();
        int screenHeight = window.getScreenHeight();
        if (screenWidth <= 0 || screenHeight <= 0) return null;
        double mouseX = minecraft.mouseHandler.xpos() * window.getGuiScaledWidth() / screenWidth;
        double mouseY = minecraft.mouseHandler.ypos() * window.getGuiScaledHeight() / screenHeight;

        Matrix4f transform = pose.last().pose();
        Matrix4f inverse = new Matrix4f(transform).invert();
        Vector3f origin = transform.transformPosition(0.0f, 0.0f, 0.0f, new Vector3f());
        Vector3f localMouse = inverse.transformPosition((float) mouseX, (float) mouseY, origin.z(), new Vector3f());
        if (!Float.isFinite(localMouse.x) || !Float.isFinite(localMouse.y)
                || localMouse.x < 0.0f || localMouse.x >= cell
                || localMouse.y < 0.0f || localMouse.y >= cell) return null;

        float scaleX = (float) Math.hypot(transform.m00(), transform.m10());
        float scaleY = (float) Math.hypot(transform.m01(), transform.m11());
        if (!(scaleX > 0.0f) || !(scaleY > 0.0f)) return null;
        return new HoverPoint(mouseX, mouseY, origin.z(), scaleX, scaleY);
    }

    private static void drawMagnified(ResourceLocation texture, GuiGraphics graphics,
            com.mojang.blaze3d.vertex.PoseStack pose, HoverPoint hover) {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        int guiWidth = window.getGuiScaledWidth();
        int guiHeight = window.getGuiScaledHeight();
        int size = Math.min(MAGNIFIED_SIZE, Math.min(guiWidth, guiHeight) - SCREEN_PADDING * 2);
        if (size <= 0) return;

        int x = (int) hover.mouseX() + MAGNIFIED_GAP;
        int y = (int) hover.mouseY() + MAGNIFIED_GAP;
        if (x + size > guiWidth - SCREEN_PADDING) x = (int) hover.mouseX() - size - MAGNIFIED_GAP;
        if (y + size > guiHeight - SCREEN_PADDING) y = (int) hover.mouseY() - size - MAGNIFIED_GAP;
        x = Math.max(SCREEN_PADDING, Math.min(x, guiWidth - size - SCREEN_PADDING));
        y = Math.max(SCREEN_PADDING, Math.min(y, guiHeight - size - SCREEN_PADDING));

        Matrix4f inverse = new Matrix4f(pose.last().pose()).invert();
        Vector3f local = inverse.transformPosition(x, y, hover.originZ(), new Vector3f());
        int localWidth = Math.max(1, (int) Math.ceil(size / hover.scaleX()));
        int localHeight = Math.max(1, (int) Math.ceil(size / hover.scaleY()));
        pose.pushPose();
        pose.translate(local.x(), local.y(), local.z() + 400.0f);
        graphics.fill(-2, -2, localWidth + 2, localHeight + 2, 0xff17191f);
        graphics.fill(-1, -1, localWidth + 1, localHeight + 1, 0xffe2d7bd);
        graphics.blit(texture, 0, 0, localWidth, localHeight, 0.0F, 0.0F,
                YsmGeometryThumbnail.SIZE, YsmGeometryThumbnail.SIZE,
                YsmGeometryThumbnail.SIZE, YsmGeometryThumbnail.SIZE);
        pose.popPose();
    }

    private record HoverPoint(double mouseX, double mouseY, float originZ, float scaleX, float scaleY) {}
}
