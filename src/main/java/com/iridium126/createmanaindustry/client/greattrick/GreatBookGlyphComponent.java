package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.GreatTrickGlyph;
import dev.enjarai.trickster.render.CircleRenderer;
import dev.enjarai.trickster.screen.owo.GlyphComponent;
import dev.enjarai.trickster.spell.Pattern;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import net.minecraft.world.phys.Vec3;

/** Uses the stock book glyph's content sizing and flow positioning with the great-trick renderer. */
public final class GreatBookGlyphComponent extends GlyphComponent {
    private final GreatTrickGlyph glyph;
    private final GreatGlyphRenderer glyphRenderer = new GreatGlyphRenderer();
    private final CircleRenderer renderer = new CircleRenderer(true, false, 0);

    public GreatBookGlyphComponent(GreatTrickGlyph glyph) {
        super(Pattern.EMPTY, 50);
        this.glyph = glyph;
        renderer.setColor(0, 0, 0);
        renderer.setMousePosition(-9999, -9999);
    }

    @Override
    public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
        float patternRadius = size / 2f;
        glyphRenderer.render(glyph, context.pose(), context.bufferSource(),
                x + patternRadius + 4, y + patternRadius + 4,
                patternRadius * CircleRenderer.PATTERN_TO_PART_RATIO, 0.9f, 0.5f,
                Vec3.ZERO, partialTicks, renderer);
    }
}
