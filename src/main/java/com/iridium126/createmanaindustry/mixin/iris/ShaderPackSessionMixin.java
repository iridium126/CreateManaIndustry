package com.iridium126.createmanaindustry.mixin.iris;

import com.google.common.collect.ImmutableList;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.ShaderPack;
import java.nio.file.Path;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ShaderPack.class,remap=false)
abstract class ShaderPackSessionMixin {
    // Iris also has a delegating three-argument constructor. Select the implementation
    // constructor once; HEAD executes before object initialization and must be static.
    @Inject(method="<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V",at=@At("HEAD"))
    private static void cmi$begin(Path root,Map<String,String> changed,ImmutableList<StringPair> environment,boolean zip,CallbackInfo ci){ShaderPackSession.begin(root);}
    @Inject(method="<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V",at=@At("TAIL"))
    private void cmi$options(Path root,Map<String,String> changed,ImmutableList<StringPair> environment,boolean zip,CallbackInfo ci){ShaderPackSession.options(((ShaderPack)(Object)this).getShaderPackOptions());}
}
