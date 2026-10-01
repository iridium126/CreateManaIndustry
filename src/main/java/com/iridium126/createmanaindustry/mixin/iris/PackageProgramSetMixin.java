package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.accessor.CMIPackageProgramSet;
import java.util.function.Function;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ProgramSet.class,remap=false)
public class PackageProgramSetMixin implements CMIPackageProgramSet {
    @Unique private ShaderProperties cmi$packageProperties;
    @Inject(method="<init>",at=@At("TAIL"))
    private void cmi$capturePackageProperties(AbsolutePackPath directory,Function<AbsolutePackPath,String> sources,
            ShaderProperties properties,ShaderPack pack,CallbackInfo ci){cmi$packageProperties=properties;}
    @Override public ShaderProperties cmi$packageProperties(){return cmi$packageProperties;}
}
