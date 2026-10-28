package com.iridium126.createmanaindustry.mixin.iris;

import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Preserve the enclosing Iris draw's deferred blend state across external model programs. */
@Mixin(value=BlendModeStorage.class,remap=false)
public interface ModelBlendStorageAccessor {
    @Accessor("blendLocked") static boolean cmi$locked(){throw new AssertionError();}
    @Accessor("blendLocked") static void cmi$locked(boolean value){throw new AssertionError();}
    @Accessor("originalBlendEnable") static boolean cmi$enabled(){throw new AssertionError();}
    @Accessor("originalBlendEnable") static void cmi$enabled(boolean value){throw new AssertionError();}
    @Accessor("originalBlend") static BlendMode cmi$mode(){throw new AssertionError();}
    @Accessor("originalBlend") static void cmi$mode(BlendMode value){throw new AssertionError();}
}
