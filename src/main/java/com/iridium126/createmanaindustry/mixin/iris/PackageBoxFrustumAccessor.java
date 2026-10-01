package com.iridium126.createmanaindustry.mixin.iris;
import com.iridium126.createmanaindustry.accessor.CMIPackageShadowCulling;
import net.irisshaders.iris.shadows.frustum.BoxCuller;
import net.irisshaders.iris.shadows.frustum.fallback.BoxCullingFrustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=BoxCullingFrustum.class,remap=false)
public interface PackageBoxFrustumAccessor extends CMIPackageShadowCulling.Box {
    @Accessor("boxCuller") BoxCuller cmi$packageBox();
}
