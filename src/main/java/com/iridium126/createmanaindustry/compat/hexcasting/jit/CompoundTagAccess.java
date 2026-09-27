package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.util.Map;
import net.minecraft.nbt.Tag;

/** Runtime duck interface implemented by the guarded CompoundTag accessor mixin. */
public interface CompoundTagAccess {
    Map<String, Tag> cmi$getTags();
}
