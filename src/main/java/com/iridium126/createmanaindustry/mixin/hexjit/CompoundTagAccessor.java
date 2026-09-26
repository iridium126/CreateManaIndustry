package com.iridium126.createmanaindustry.mixin.hexjit;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.CompoundTagAccess;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Narrow access to the verified 1.21.1 CompoundTag storage used by the Add Motion copy path. */
@Mixin(CompoundTag.class)
public interface CompoundTagAccessor extends CompoundTagAccess {
    @Override
    @Accessor("tags")
    Map<String, Tag> cmi$getTags();

    @Override
    @Mutable
    @Accessor("tags")
    void cmi$setTags(Map<String, Tag> tags);
}
