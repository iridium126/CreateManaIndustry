package com.iridium126.createmanaindustry.mixin.hexjit;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.CompoundTagAccess;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Narrow access to the verified 1.21.1 CompoundTag storage used by the Add Motion copy path. */
@Mixin(CompoundTag.class)
public interface CompoundTagAccessor extends CompoundTagAccess {
    @Override
    @Accessor("tags")
    Map<String, Tag> cmi$getTags();

    @Invoker("<init>")
    static CompoundTag cmi$newWithTags(Map<String, Tag> tags) {
        throw new AssertionError();
    }
}
