package com.iridium126.createmanaindustry.content.recipes;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

/**
 * Specifies that a recipe requires mist of a certain fluid type to be
 * present at the basin before it can match.
 *
 * @param fluidId          the required mist fluid
 * @param minConcentration minimum concentration (0.0–1.0) at the basin position
 */
public record MistRequirement(ResourceLocation fluidId, double minConcentration) {
    public static final Codec<MistRequirement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("fluid").forGetter(MistRequirement::fluidId),
            Codec.DOUBLE.optionalFieldOf("min_concentration", 0.1).forGetter(MistRequirement::minConcentration)
    ).apply(instance, MistRequirement::new));
}
