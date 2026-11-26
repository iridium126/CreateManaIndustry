package com.iridium126.createmanaindustry.client.render;

import com.iridium126.createmanaindustry.compat.hexcasting.ysm.InlineYsmGeometryData;
import com.iridium126.createmanaindustry.compat.ysm.render.YsmGeometryThumbnail;
import com.samsthenerd.inline.api.client.GlowHandling;
import com.samsthenerd.inline.api.client.InlineRenderer;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Renders complete static YSM geometry in a cell matching the active font's line height. */
public final class InlineYsmGeometryRenderer implements InlineRenderer<InlineYsmGeometryData> {
    public static final InlineYsmGeometryRenderer INSTANCE = new InlineYsmGeometryRenderer();
    private static final int MAGNIFIED_SIZE = YsmGeometryThumbnail.SIZE;
    private static final int MAGNIFIED_GAP = 12;
    private static final int SCREEN_PADDING = 4;

    /** Preview pending: a dimmed picture-frame glyph with a soft breathing glow. */
    private static final int LOADING_FRAME_RGB = 0x8FB3D9;
    private static final int LOADING_ICON_RGB = 0xC7D7E8;
    private static final int LOADING_SUN_RGB = 0xF2C879;
    private static final int LOADING_BACKDROP = 0xD9161B22;
    /** Preview failed: the same frame in red with a crossed-out glyph. */
    private static final int FAILURE_FRAME_RGB = 0xC2565F;
    private static final int FAILURE_ICON_RGB = 0xE8898F;
    private static final int FAILURE_BACKDROP = 0xE01E1113;
    private static final long PULSE_PERIOD_MS = 1600L;
    private static final Component LOADING_HINT = Component.translatable("createmanaindustry.ysm.preview.loading");
    private static final Component FAILURE_HINT = Component.translatable("createmanaindustry.ysm.preview.failed");

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
            drawFailureCell(graphics, cell);
            drawHint(graphics, pose, context, cell, FAILURE_HINT, argb(1.0f, FAILURE_FRAME_RGB));
        } else {
            drawLoadingCell(graphics, cell);
            drawHint(graphics, pose, context, cell, LOADING_HINT, argb(1.0f, LOADING_FRAME_RGB));
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

    /** Rounded one-pixel frame with an inset backdrop, kept one pixel inside the cell. */
    private static void drawFrame(GuiGraphics graphics, int cell, int frameColor, int backdropColor) {
        int x0 = 1;
        int y0 = 1;
        int x1 = cell - 1;
        int y1 = cell - 1;
        if (x1 - x0 < 5 || y1 - y0 < 5) {
            graphics.fill(x0, y0, Math.max(x0 + 1, x1), Math.max(y0 + 1, y1), frameColor);
            return;
        }
        graphics.fill(x0 + 1, y0, x1 - 1, y1, frameColor);
        graphics.fill(x0, y0 + 1, x1, y1 - 1, frameColor);
        graphics.fill(x0 + 2, y0 + 1, x1 - 2, y1 - 1, backdropColor);
        graphics.fill(x0 + 1, y0 + 2, x1 - 1, y1 - 2, backdropColor);
    }

    /** Pending state: a picture-frame glyph that breathes, so it reads as "still loading". */
    private static void drawLoadingCell(GuiGraphics graphics, int cell) {
        long phase = Math.floorMod(Util.getMillis(), PULSE_PERIOD_MS);
        float pulse = 0.45f + 0.55f * (0.5f - 0.5f * Mth.cos(phase / (float) PULSE_PERIOD_MS * Mth.TWO_PI));
        drawFrame(graphics, cell, argb(pulse, LOADING_FRAME_RGB), LOADING_BACKDROP);
        int left = 2;
        int top = 2;
        int right = cell - 2;
        int bottom = cell - 2;
        int inner = Math.min(right - left, bottom - top);
        if (inner < 4) return;
        int icon = argb(0.6f + 0.4f * pulse, LOADING_ICON_RGB);
        // Horizon line with a single mountain peak, plus a sun in the upper-right corner.
        graphics.fill(left, bottom - 2, right, bottom - 1, icon);
        graphics.fill(left + 1, bottom - 3, Math.min(left + 3, right), bottom - 2, icon);
        if (inner >= 5)
            graphics.fill(right - 2, top + 1, right - 1, top + 2, argb(0.65f + 0.35f * pulse, LOADING_SUN_RGB));
    }

    /** Failure state: the same frame in red with a crossed-out glyph. */
    private static void drawFailureCell(GuiGraphics graphics, int cell) {
        drawFrame(graphics, cell, argb(1.0f, FAILURE_FRAME_RGB), FAILURE_BACKDROP);
        int left = 2;
        int top = 2;
        int right = cell - 2;
        int bottom = cell - 2;
        int inner = Math.min(right - left, bottom - top);
        if (inner < 4) return;
        int icon = argb(0.9f, FAILURE_ICON_RGB);
        for (int i = 0; i < inner; i++) {
            graphics.fill(left + i, bottom - 1 - i, left + i + 1, bottom - i, icon);
            graphics.fill(left + i, top + i, left + i + 1, top + i + 1, icon);
        }
    }

    /** Explains the placeholder while the cursor rests on it, matching the magnifier behaviour. */
    private static void drawHint(GuiGraphics graphics, com.mojang.blaze3d.vertex.PoseStack pose,
            TextRenderingContext context, int cell, Component text, int accent) {
        HoverPoint hover = hoveredPoint(pose, context, cell);
        if (hover == null) return;
        Font font = Minecraft.getInstance().font;
        Window window = Minecraft.getInstance().getWindow();
        int padding = 3;
        int boxWidth = font.width(text) + padding * 2;
        int boxHeight = font.lineHeight + padding * 2 - 1;
        int guiWidth = window.getGuiScaledWidth();
        int guiHeight = window.getGuiScaledHeight();
        int x = (int) hover.mouseX() + MAGNIFIED_GAP;
        int y = (int) hover.mouseY() + MAGNIFIED_GAP;
        if (x + boxWidth > guiWidth - SCREEN_PADDING) x = (int) hover.mouseX() - boxWidth - MAGNIFIED_GAP;
        if (y + boxHeight > guiHeight - SCREEN_PADDING) y = (int) hover.mouseY() - boxHeight - MAGNIFIED_GAP;
        x = Mth.clamp(x, SCREEN_PADDING, Math.max(SCREEN_PADDING, guiWidth - boxWidth - SCREEN_PADDING));
        y = Mth.clamp(y, SCREEN_PADDING, Math.max(SCREEN_PADDING, guiHeight - boxHeight - SCREEN_PADDING));

        Vector3f local = new Matrix4f(pose.last().pose()).invert()
                .transformPosition(x, y, hover.originZ(), new Vector3f());
        pose.pushPose();
        pose.translate(local.x(), local.y(), local.z() + 400.0f);
        // Keep the hint at screen pixel size even when the inline cell is scaled (e.g. item tooltips).
        pose.scale(1.0f / hover.scaleX(), 1.0f / hover.scaleY(), 1.0f);
        graphics.fill(-1, -1, boxWidth + 1, boxHeight + 1, 0xff101318);
        graphics.fill(0, 0, boxWidth, boxHeight, 0xf0202530);
        graphics.fill(0, 0, 1, boxHeight, accent);
        graphics.drawString(font, text, padding, padding - 1, 0xffd8e3f0, false);
        pose.popPose();
    }

    private static int argb(float alpha, int rgb) {
        return ((int) (Mth.clamp(alpha, 0.0f, 1.0f) * 255.0f) << 24) | (rgb & 0xFFFFFF);
    }

    private record HoverPoint(double mouseX, double mouseY, float originZ, float scaleX, float scaleY) {}
}
