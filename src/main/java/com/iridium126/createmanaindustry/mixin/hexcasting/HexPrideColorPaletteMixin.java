package com.iridium126.createmanaindustry.mixin.hexcasting;

import com.iridium126.createmanaindustry.client.hex.particles.HexColorPalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets="at.petrak.hexcasting.common.items.pigment.ItemPridePigment$Type")
public interface HexPrideColorPaletteMixin extends HexColorPalette {
    @Override @Accessor("colors") int[] cmi$palette();
}
