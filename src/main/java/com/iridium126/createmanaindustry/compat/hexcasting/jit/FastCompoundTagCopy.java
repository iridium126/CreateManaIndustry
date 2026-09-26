package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Allocation-lean equivalent of the verified vanilla CompoundTag.copy implementation.
 * Every child still goes through its own Tag.copy implementation, including modded Tag types.
 */
public final class FastCompoundTagCopy {
    private FastCompoundTagCopy() {}

    public static CompoundTag copy(CompoundTag source) {
        Map<String, Tag> tags = ((CompoundTagAccess) (Object) source).cmi$getTags();
        int size = tags.size();
        // Match HashMap(Map)'s JDK sizing so the copied map keeps vanilla's bucket layout and
        // iteration order while avoiding the Guava transformed-map wrappers used by copy().
        int capacity = size == 0 ? 0 : Math.min(1 << 30, (int) (size / 0.75F + 1.0F));
        Map<String, Tag> copied = size == 0 ? new HashMap<>() : new HashMap<>(capacity);
        for (Map.Entry<String, Tag> entry : tags.entrySet()) {
            copied.put(entry.getKey(), entry.getValue().copy());
        }
        CompoundTag clone = new CompoundTag();
        ((CompoundTagAccess) (Object) clone).cmi$setTags(copied);
        return clone;
    }
}
