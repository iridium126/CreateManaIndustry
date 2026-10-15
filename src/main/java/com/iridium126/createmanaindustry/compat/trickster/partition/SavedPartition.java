package com.iridium126.createmanaindustry.compat.trickster.partition;

import net.minecraft.resources.ResourceLocation;

/** Saved independently of cmi_chain and old executor layouts. */
public record SavedPartition(ResourceLocation dimension, PartitionBounds bounds) {
    public String encode() { return dimension+"|"+bounds.encode(); }
    public static SavedPartition decode(String value) {
        int slash=value.indexOf('|');
        if(slash<0)throw new IllegalArgumentException("Invalid saved partition");
        return new SavedPartition(ResourceLocation.parse(value.substring(0,slash)),PartitionBounds.decode(value.substring(slash+1)));
    }
}
