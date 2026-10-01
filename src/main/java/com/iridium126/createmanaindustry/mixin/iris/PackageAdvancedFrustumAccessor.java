package com.iridium126.createmanaindustry.mixin.iris;
import com.iridium126.createmanaindustry.accessor.CMIPackageShadowCulling;
import net.irisshaders.iris.shadows.frustum.BoxCuller;
import net.irisshaders.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=AdvancedShadowCullingFrustum.class,remap=false)
public interface PackageAdvancedFrustumAccessor extends CMIPackageShadowCulling.Advanced {
    @Accessor("planes") float[][] cmi$packagePlanes();
    @Accessor("planeCount") int cmi$packagePlaneCount();
    @Accessor("boxCuller") BoxCuller cmi$packageBox();
}
