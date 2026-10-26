package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Mixin;

/** Reuse the verified world0 pipeline for this mod's dimension when a pack has no explicit dimension mapping. */
@Mixin(value=ShaderPack.class,remap=false)
public abstract class RiftDimensionProgramsMixin {
    @WrapMethod(method="getProgramSet")
    private ProgramSet cmi$allayPrograms(NamespacedId dimension,Operation<ProgramSet> original) {
        var pack=(ShaderPack)(Object)this;
        if(dimension.getNamespace().equals("createmanaindustry")&&dimension.getName().equals("allay_dimension")
                &&ShaderPackSession.current().rift().enabled()&&!pack.getDimensionMap().containsKey(dimension))
            return original.call(new NamespacedId("minecraft","overworld"));
        return original.call(dimension);
    }
}
