package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.accessor.CMIPackageShadowCulling;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDrawCulling;
import net.irisshaders.iris.shadows.frustum.*;
import net.irisshaders.iris.shadows.frustum.fallback.NonCullingFrustum;
import net.minecraft.client.renderer.culling.Frustum;

/** Copies O(13) immutable frame parameters, never iterates package identities on the CPU. */
public final class PackageShadowPolicy {
    private PackageShadowPolicy() {}
    public static boolean copy(Frustum frustum,PackageDrawCulling out) {
        out.clear();
        if(frustum instanceof NonCullingFrustum)return true;
        if(frustum instanceof CullEverythingFrustum){out.reject();return true;}
        if(frustum instanceof CMIPackageShadowCulling.Advanced advanced) {
            out.set(advanced.cmi$packagePlanes(),advanced.cmi$packagePlaneCount());
            if(frustum instanceof CMIPackageShadowCulling.Safe safe) {
                out.distance=distance(safe.cmi$packageDistanceBox());out.safe=distance(advanced.cmi$packageBox());
            }else out.distance=distance(advanced.cmi$packageBox());
            return true;
        }
        if(frustum instanceof CMIPackageShadowCulling.Box box){out.distance=distance(box.cmi$packageBox());return true;}
        return false; // Unknown future frustum must not silently become air or the main camera.
    }
    private static float distance(BoxCuller box) {
        if(box==null)return -1;
        double d=((CMIPackageShadowCulling.Distance)box).cmi$packageMaxDistance();
        if(!Double.isFinite(d) || d<0 || d>Float.MAX_VALUE)throw new IllegalArgumentException("Invalid Iris shadow distance");
        return (float)d;
    }
}
