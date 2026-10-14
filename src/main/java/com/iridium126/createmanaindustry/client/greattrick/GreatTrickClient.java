package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatMode;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatTricks;

import dev.enjarai.trickster.render.fragment.FragmentRenderer;
import dev.enjarai.trickster.screen.ScrollAndQuillScreen;
import net.minecraft.client.Minecraft;

public final class GreatTrickClient {
    private GreatTrickClient() {}
    public static boolean greatMode() {
        return Minecraft.getInstance().screen instanceof ScrollAndQuillScreen screen
                && ((GreatMode)(Object)screen.getScreenHandler().initialData).cmi$greatMode();
    }
    public static void register() {
        FragmentRenderer.register(GreatTricks.GLYPH, new GreatGlyphRenderer());
        io.wispforest.owo.ui.parsing.UIParsing.registerFactory(
                com.iridium126.createmanaindustry.CreateManaIndustry.modLoc("great-trick-overview"), GreatTrickBookComponent::parse);
    }
    public static void save(dev.enjarai.trickster.spell.SpellPart root) {
        if (Minecraft.getInstance().screen instanceof ScrollAndQuillScreen screen)
            screen.getScreenHandler().updateSpell(root);
    }
}
