package com.iridium126.createmanaindustry.content.recipes;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

/**
 * Specifies mist produced as a recipe byproduct.
 *
 * @param fluidId the fluid whose color tints the mist
 * @param radius  field radius in blocks
 */
public record MistOutput(ResourceLocation fluidId, int radius) {
    public static final Codec<MistOutput> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("fluid").forGetter(MistOutput::fluidId),
            Codec.INT.optionalFieldOf("radius", 16).forGetter(MistOutput::radius)
    ).apply(instance, MistOutput::new));
}
