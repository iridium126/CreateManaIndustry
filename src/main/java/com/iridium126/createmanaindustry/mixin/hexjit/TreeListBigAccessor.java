package com.iridium126.createmanaindustry.mixin.hexjit;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.TreeListBigAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "at.petrak.hexcasting.api.utils.TreeList$BigTreeList", remap = false)
public interface TreeListBigAccessor extends TreeListBigAccess {
    @Override @Accessor("suffix1") Object[] cmi$getSuffix1();
}
