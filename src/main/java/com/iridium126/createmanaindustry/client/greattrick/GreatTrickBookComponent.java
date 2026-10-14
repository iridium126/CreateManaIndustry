package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.AnimaMath;
import com.iridium126.createmanaindustry.compat.trickster.GreatTrick;
import com.iridium126.createmanaindustry.compat.trickster.GreatTrickGlyph;
import com.iridium126.createmanaindustry.compat.trickster.GreatTricks;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.VerticalAlignment;
import io.wispforest.owo.ui.parsing.UIModelParsingException;
import io.wispforest.owo.ui.parsing.UIParsing;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.w3c.dom.Element;

/** The same title/glyph/signature layout as Trickster's trick overview, with an Anima cost. */
public final class GreatTrickBookComponent extends FlowLayout {
    private GreatTrickBookComponent(GreatTrick trick, ResourceLocation bookTexture) {
        super(Sizing.fill(100), Sizing.content(), Algorithm.VERTICAL);
        alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER);
        var glyph = new GreatTrickGlyph(trick.pattern());
        child(Components.label(glyph.asText())
                .color(Color.ofFormatting(ChatFormatting.DARK_GRAY))
                .horizontalTextAlignment(HorizontalAlignment.CENTER)
                .margins(Insets.of(2, 2, 0, 0))
                .sizing(Sizing.fill(100), Sizing.content()));
        child(new GreatBookGlyphComponent(glyph));
        var signature = Component.empty();
        for (var input : trick.inputTypes()) {
            if (!signature.getSiblings().isEmpty()) signature.append(", ");
            signature.append(input.asText());
        }
        signature.append(" → ").append(trick.outputType().asText());
        child(Components.label(signature
                        .withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY).withFont(Minecraft.UNIFORM_FONT)))
                .horizontalSizing(Sizing.fill(100))
                .margins(Insets.of(0, 5, 3, 3)));
        child(Components.label(Component.translatable("createmanaindustry.great_trick.book_cost",
                        AnimaMath.format(trick.animaCost())))
                .color(Color.ofFormatting(ChatFormatting.DARK_GRAY))
                .horizontalTextAlignment(HorizontalAlignment.CENTER)
                .horizontalSizing(Sizing.fill(100))
                .margins(Insets.of(0, 5, 0, 0)));
        child(Components.texture(bookTexture, 54, 183, 109, 3, 512, 256).blend(true));
    }

    public static GreatTrickBookComponent parse(Element element) {
        UIParsing.expectAttributes(element, "trick-id", "texture");
        var id = UIParsing.parseIdentifier(element.getAttributeNode("trick-id"));
        var trick = GreatTricks.REGISTRY.get(id);
        if (trick == null) throw new UIModelParsingException("Not a valid great trick: " + id);
        return new GreatTrickBookComponent(trick, UIParsing.parseIdentifier(element.getAttributeNode("texture")));
    }
}
