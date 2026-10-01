package com.iridium126.createmanaindustry.accessor;

import net.irisshaders.iris.shadows.frustum.BoxCuller;

public interface CMIPackageShadowCulling {
    interface Advanced {
        float[][] cmi$packagePlanes();
        int cmi$packagePlaneCount();
        BoxCuller cmi$packageBox();
    }
    interface Box {BoxCuller cmi$packageBox();}
    interface Safe {BoxCuller cmi$packageDistanceBox();}
    interface Distance {double cmi$packageMaxDistance();}
}
