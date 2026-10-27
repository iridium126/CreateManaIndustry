#ifndef CMI_NATIVE_RIFT
#define CMI_NATIVE_RIFT
uniform sampler2D CmiTargetSky;
uniform vec4 CmiRift;
uniform vec4 CmiSkyContext;
uniform int CmiSkyDimension;
uniform float CmiRiftEnabled;
uniform float CmiRiftReactive;
uniform vec4 CmiStormCenter;
uniform vec2 CmiStormCloud;
// Only a main-view caller can clip clouds. Shared reflection and environment callers keep false.
bool cmiMainView = false;
vec3 cmiCloudCamera=vec3(0.0);
float cmiCloudUnitScale=1.0;
float cmiCloudDensityFactor=1.0;
bool cmiStormCloudActive() {
    return cmiMainView && CmiSkyDimension==0 && CmiStormCloud.y>0.0 && CmiStormCenter.w>0.0;
}
float cmiStormCloudRadius() { return clamp(8.0*CmiStormCenter.w,96.0,384.0); }
vec2 cmiStormCloudPosition(vec2 relativePoint) {
    if(!cmiStormCloudActive())return relativePoint;
    vec2 delta=relativePoint-CmiStormCenter.xz;
    float radial=length(delta)/cmiStormCloudRadius();
    float influence=(1.0-smoothstep(.65,1.0,radial))*CmiStormCloud.y;
    // Use the unwrapped growth-law integral; radial shear must not inherit a modulo discontinuity.
    float angle=(CmiStormCloud.x+.7*log(1.0+8.0*radial))*influence;
    float s=sin(angle),c=cos(angle);
    return CmiStormCenter.xz+mat2(c,-s,s,c)*delta;
}
vec3 cmiStormCloudPosition(vec3 relativePoint) {
    vec2 xz=cmiStormCloudPosition(relativePoint.xz);
    return vec3(xz.x,relativePoint.y,xz.y);
}
float cmiStormCloudDensity(vec2 relativePoint) {
    if(!cmiStormCloudActive())return 1.0;
    float radial=length(relativePoint-CmiStormCenter.xz)/cmiStormCloudRadius();
    float factor=mix(.3,1.6,smoothstep(.25,.45,radial));
    return mix(1.0,factor,(1.0-smoothstep(.7,1.0,radial))*CmiStormCloud.y);
}
bool cmiRayActive(vec3 direction) {
    return CmiRiftEnabled > 0.5 && CmiSkyDimension == 0 && cmiRiftDistance(normalize(direction), CmiRift) > 0.0;
}
bool cmiCloudVisible(vec3 cameraRelativePoint) {
    if (!cmiMainView || CmiRiftEnabled < 0.5) return true;
    if (CmiSkyDimension != 0) return true;
    float distanceToSample = length(cameraRelativePoint);
    if (distanceToSample < 0.00001) return true;
    float limit = cmiRiftDistance(cameraRelativePoint / distanceToSample, CmiRift);
    return limit <= 0.0 || distanceToSample < limit;
}
float cmiCloudLimit(vec3 direction, float maximum) {
    if (!cmiMainView || CmiRiftEnabled < 0.5) return maximum;
    if (CmiSkyDimension != 0) return maximum;
    float limit = cmiRiftDistance(normalize(direction), CmiRift);
    return limit > 0.0 ? min(maximum, limit) : maximum;
}
vec3 cmiNativeSky(vec3 original, vec3 direction, float gamma, float radianceScale) {
    if (!cmiRayActive(direction)) return original;
    vec4 clip=CmiSkyViewProjection*vec4(direction,0.0);
    vec3 nativeSky=texture(CmiTargetSky,clip.xy/clip.w*.5+.5).rgb;
    return pow(max(nativeSky,vec3(0.0)),vec3(1.0/gamma))*radianceScale;
}
#endif
