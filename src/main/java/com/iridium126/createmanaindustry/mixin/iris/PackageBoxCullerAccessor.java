package com.iridium126.createmanaindustry.mixin.iris;
import com.iridium126.createmanaindustry.accessor.CMIPackageShadowCulling;
import net.irisshaders.iris.shadows.frustum.BoxCuller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=BoxCuller.class,remap=false)
public interface PackageBoxCullerAccessor extends CMIPackageShadowCulling.Distance {
    @Accessor("maxDistance") double cmi$packageMaxDistance();
}
