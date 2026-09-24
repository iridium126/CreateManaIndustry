package com.iridium126.createmanaindustry.client.render;

import com.iridium126.createmanaindustry.compat.hexcasting.ysm.InlineYsmGeometryData;
import com.iridium126.createmanaindustry.compat.ysm.render.YsmGeometryThumbnail;
import com.samsthenerd.inline.api.client.GlowHandling;
import com.samsthenerd.inline.api.client.InlineRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/** Renders complete static YSM geometry in a fixed-size Inline text cell. */
public final class InlineYsmGeometryRenderer implements InlineRenderer<InlineYsmGeometryData> {
    public static final InlineYsmGeometryRenderer INSTANCE = new InlineYsmGeometryRenderer();
    private static final int CELL = 24;

    private InlineYsmGeometryRenderer() {}
    @Override public ResourceLocation getId() { return InlineYsmGeometryData.RENDERER_ID; }
    @Override public GlowHandling getGlowPreference(InlineYsmGeometryData data) { return new GlowHandling.None(); }
    @Override public int charWidth(InlineYsmGeometryData data, Style style, int codepoint) { return CELL; }

    @Override public int render(InlineYsmGeometryData data, GuiGraphics graphics, int index, Style style,
            int codepoint, TextRenderingContext context) {
        if (context.isGlowy()) return charWidth(data, style, codepoint);
        YsmClientPreviews.Entry entry = YsmClientPreviews.get(data);
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0f, -4.0f, 0.0f);
        if (entry.texture != null) {
            graphics.blit(entry.texture, 0, 0, 0.0F, 0.0F, CELL, CELL,
                    YsmGeometryThumbnail.SIZE, YsmGeometryThumbnail.SIZE);
        } else if (entry.error != null) {
            graphics.fill(1, 1, CELL - 1, CELL - 1, 0x993f1720);
            graphics.drawString(Minecraft.getInstance().font, "!", CELL / 2 - 2, CELL / 2 - 4, 0xffff7777, false);
        } else {
            graphics.fill(1, 1, CELL - 1, CELL - 1, 0x66555d66);
            graphics.fill(7, CELL / 2, CELL - 7, CELL / 2 + 1, 0xffa9d68b);
            graphics.fill(CELL / 2, 7, CELL / 2 + 1, CELL - 7, 0xffa9d68b);
        }
        pose.popPose();
        return charWidth(data, style, codepoint);
    }
}
