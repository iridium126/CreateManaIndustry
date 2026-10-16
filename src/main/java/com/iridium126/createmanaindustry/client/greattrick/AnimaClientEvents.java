package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.anima.BatteryAnima;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

@EventBusSubscriber(modid=CreateManaIndustry.MODID,value=Dist.CLIENT)
public final class AnimaClientEvents {
    private static final String MEDIA_TOOLTIP = "hexcasting.tooltip.media_amount.advanced";
    private static final int ANIMA_COLOR = 0x44BCF9;
    private static final int ANIMA_PERCENT_COLOR = 0x7AD6FB;
    private static final ResourceLocation SPACING_FONT = CreateManaIndustry.modLoc("tooltip_spacing");
    private static final DecimalFormat AMOUNT = new DecimalFormat("###,###.##");
    private static final DecimalFormat PERCENTAGE = new DecimalFormat("####");
    static { PERCENTAGE.setRoundingMode(RoundingMode.DOWN); }

    @SubscribeEvent
    public static void registerItemDecorations(RegisterItemDecorationsEvent event) {
        if (!CreateManaIndustry.HEX_ACTIVE) return;
        BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("hexcasting", "battery"))
                .ifPresent(battery -> event.register(battery, (graphics, font, stack, x, y) -> {
                    long current = BatteryAnima.get(stack);
                    if (current <= 0) return false;
                    long capacity = BatteryAnima.capacity(stack);
                    if (capacity <= 0) return false;
                    int width = Math.max(1, (int)Math.round(13.0 * Math.min(current, capacity) / capacity));
                    // Leave a one-pixel gap below the native media bar at y + 13.
                    int left = x + 2, top = y + 14;
                    graphics.fill(RenderType.guiOverlay(), left, top, left + 13, top + 2, 0xFF000000);
                    graphics.fill(RenderType.guiOverlay(), left, top, left + width, top + 1,
                            0xFF000000 | ANIMA_COLOR);
                    return false;
                }));
    }

    @SubscribeEvent public static void tooltip(ItemTooltipEvent event) {
        if(!CreateManaIndustry.HEX_ACTIVE||!BatteryAnima.isBattery(event.getItemStack()))return;
        long current = BatteryAnima.get(event.getItemStack());
        long capacity = BatteryAnima.capacity(event.getItemStack());
        float fullness = capacity == 0 ? 0 : (float)current / (float)capacity;
        var amount = Component.literal(AMOUNT.format(BigDecimal.valueOf(current, 3)))
                .withColor(ANIMA_COLOR);
        var maximum = Component.translatable("createmanaindustry.anima.amount",
                        AMOUNT.format(BigDecimal.valueOf(capacity, 3)))
                .withColor(ANIMA_COLOR);
        var percent = Component.literal(PERCENTAGE.format(100f * fullness) + "%")
                .withColor(ANIMA_PERCENT_COLOR);
        var anima = Component.translatable("createmanaindustry.anima.tooltip", amount, maximum, percent);
        var tooltip = event.getToolTip();
        for (int i = 0; i < tooltip.size(); i++) {
            var line = tooltip.get(i);
            if (line.getContents() instanceof TranslatableContents text && text.getKey().equals(MEDIA_TOOLTIP)) {
                tooltip.add(i + 1, Component.empty().append(indent(line)).append(anima));
                return;
            }
        }
        // A battery without valid media capacity has no native media row to continue.
        tooltip.add(Component.translatable(MEDIA_TOOLTIP, amount, maximum, percent));
    }

    private static Component indent(Component mediaLine) {
        String marker = "\u0001";
        String template = Component.translatable(MEDIA_TOOLTIP, marker, "", "").getString();
        int start = template.indexOf(marker);
        if (start < 0) return Component.empty();
        var prefix = Component.literal(template.substring(0, start)).withStyle(mediaLine.getStyle());
        int width = Minecraft.getInstance().font.width(prefix);
        // A one-pixel space provider aligns both CJK and proportional Latin prefixes exactly.
        return Component.literal(" ".repeat(width)).withStyle(style -> style.withFont(SPACING_FONT));
    }
}
