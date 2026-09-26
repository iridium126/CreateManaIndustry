package com.iridium126.createmanaindustry.mixin.hexjit;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.TreeList2Access;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "at.petrak.hexcasting.api.utils.TreeList$TreeList2", remap = false)
public interface TreeList2Accessor extends TreeList2Access {
    @Override @Accessor("len1") int cmi$getLen1();
    @Override @Accessor("data2") Object[][] cmi$getData2();
}
