package com.iridium126.createmanaindustry.mixin.iris;
import com.iridium126.createmanaindustry.accessor.CMIPackageShadowCulling;
import net.irisshaders.iris.shadows.frustum.BoxCuller;
import net.irisshaders.iris.shadows.frustum.advanced.SafeZoneCullingFrustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=SafeZoneCullingFrustum.class,remap=false)
public interface PackageSafeFrustumAccessor extends CMIPackageShadowCulling.Safe {
    @Accessor("distanceCuller") BoxCuller cmi$packageDistanceBox();
}
