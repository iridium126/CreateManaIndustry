package com.iridium126.createmanaindustry.mixin.hexjit;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.TreeListBaseAccess;
import at.petrak.hexcasting.api.utils.TreeList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = TreeList.class, remap = false)
public interface TreeListAccessor extends TreeListBaseAccess {
    @Override @Accessor("prefix1") Object[] cmi$getPrefix1();
}
