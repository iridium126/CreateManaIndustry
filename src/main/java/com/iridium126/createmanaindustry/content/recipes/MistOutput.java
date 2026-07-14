package com.iridium126.createmanaindustry.content.recipes;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

/**
 * Specifies mist produced as a recipe byproduct.
 *
 * @param fluidId  the fluid whose color tints the mist
 * @param radius   field radius in blocks
 * @param amount   mB of condensable fluid added to mist capacity per recipe completion
 * @param duration ticks the mist persists after the last recipe completes
 */
public record MistOutput(ResourceLocation fluidId, int radius, int amount, int duration) {
    public static final Codec<MistOutput> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("fluid").forGetter(MistOutput::fluidId),
            Codec.INT.optionalFieldOf("radius", 8).forGetter(MistOutput::radius),
            Codec.INT.fieldOf("amount").forGetter(MistOutput::amount),
            Codec.INT.optionalFieldOf("duration", 100).forGetter(MistOutput::duration)
    ).apply(instance, MistOutput::new));
}
