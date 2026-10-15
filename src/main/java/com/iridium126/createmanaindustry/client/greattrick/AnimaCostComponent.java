package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.anima.AnimaMath;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatTrick;
import dev.enjarai.trickster.ClientUtils;
import dev.enjarai.trickster.screen.owo.ManaCostComponent;
import dev.enjarai.trickster.spell.mana.type.Manae;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** Native book cost bar, icon and hover regions; only the resource text differs. */
public final class AnimaCostComponent extends ManaCostComponent {
    public AnimaCostComponent(GreatTrick trick, ResourceLocation bookTexture) {
        // Reuse the native icon without registering Anima as a transferable Trickster mana type.
        super(calculation(trick), Manae.TRADITIONAL, bookTexture);
        var tooltip = Component.translatable("createmanaindustry.great_trick.costs_anima")
                .append("\n")
                .append(Component.literal(calculation(trick)).withStyle(ChatFormatting.GRAY));
        if (ClientUtils.hasAdvancement(ResourceLocation.fromNamespaceAndPath("trickster", "unusual_mana"))) {
            tooltip.append("\n\n").append(Component.translatable("trickster.mana_cost.type",
                    Component.literal("Anima").withColor(0x4da6ff)));
        }
        children().forEach(component -> component.tooltip(tooltip));
    }

    private static String calculation(GreatTrick trick) {
        var cost = trick.estimateAnimaCost(List.of());
        return cost.isPresent() ? AnimaMath.format(cost.getAsLong()) + " Anima" : trick.costDescription().getString();
    }
}
