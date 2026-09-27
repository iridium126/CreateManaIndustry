package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import com.iridium126.createmanaindustry.mixin.hexjit.CompoundTagAccessor;
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
        if (source.getClass() != CompoundTag.class) return source.copy();
        Map<String, Tag> tags = ((CompoundTagAccess) (Object) source).cmi$getTags();
        int size = tags.size();
        // Avoid the transformed-map wrapper allocation used by vanilla copy() on empty data.
        if (size == 0) return new CompoundTag();
        // Match HashMap(Map)'s JDK sizing so the copied map keeps vanilla's bucket layout and
        // iteration order while avoiding the Guava transformed-map wrappers used by copy().
        int capacity = Math.min(1 << 30, (int) (size / 0.75F + 1.0F));
        Map<String, Tag> copied = new HashMap<>(capacity);
        for (Map.Entry<String, Tag> entry : tags.entrySet()) {
            Tag value = entry.getValue();
            copied.put(entry.getKey(), value.getClass() == CompoundTag.class
                    ? copy((CompoundTag) value) : value.copy());
        }
        // This is the same protected Map constructor used by vanilla copy(), without first
        // allocating and abandoning the public constructor's empty map.
        return CompoundTagAccessor.cmi$newWithTags(copied);
    }
}
