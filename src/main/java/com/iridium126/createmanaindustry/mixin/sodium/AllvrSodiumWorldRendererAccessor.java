package com.iridium126.createmanaindustry.mixin.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;

/** The only access to SodiumWorldRenderer's private section manager. */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface AllvrSodiumWorldRendererAccessor {

    @Accessor("renderSectionManager")
    RenderSectionManager cmi$getRenderSectionManager();
}
