package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.trickster.*;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(modid=CreateManaIndustry.MODID,value=Dist.CLIENT)
public final class AnimaClientEvents {
    @SubscribeEvent public static void tooltip(ItemTooltipEvent event) {
        if(!CreateManaIndustry.HEX_ACTIVE||!BatteryAnima.isBattery(event.getItemStack()))return;
        event.getToolTip().add(Component.translatable("createmanaindustry.anima.tooltip",
                AnimaMath.format(BatteryAnima.get(event.getItemStack())),AnimaMath.format(BatteryAnima.capacity(event.getItemStack())))
                .withStyle(ChatFormatting.AQUA));
    }
}
