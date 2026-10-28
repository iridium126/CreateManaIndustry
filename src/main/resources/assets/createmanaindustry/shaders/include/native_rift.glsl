#ifndef CMI_NATIVE_RIFT
#define CMI_NATIVE_RIFT
uniform sampler2D CmiTargetSky;
uniform vec4 CmiRift;
uniform vec4 CmiSkyContext;
uniform int CmiSkyDimension;
uniform int CmiTargetPreview;
uniform float CmiRiftEnabled;
uniform vec4 CmiStormCenter;
// cone slope, plane Y-64, strength, camera Y-64
uniform vec4 CmiStormCloud;
// Only a main-view caller can clip clouds. Shared reflection and environment callers keep false.
bool cmiMainView = false;
// Some native 2D/block clouds expose encoded opacity, not linear medium density.
float cmiCloudOpacity(float opacity,float multiplier) {
    if(multiplier<=0.0)return 0.0;
    if(multiplier==1.0)return opacity;
    return 1.0-pow(clamp(1.0-opacity,0.0,1.0),multiplier);
}
bool cmiStormCloudActive() {
    return cmiMainView && CmiSkyDimension==0 && CmiStormCloud.z>0.0 && CmiStormCloud.x>0.0;
}
float cmiCloudSmooth(float t) {
    t=clamp(t,0.0,1.0);
    return t*t*t*(t*(t*6.0-15.0)+10.0);
}
// Inputs are actual, unwarped camera-relative world positions, never noise coordinates.
float cmiStormCloudDensity(vec3 relativePoint) {
    // Preview geometry belongs to the target only beyond the portal. The real target
    // dimension has CmiTargetPreview=0 and retains its complete native cloud definition.
    if(CmiTargetPreview!=0 && cmiMainView && CmiRiftEnabled>0.5){
        float d2=dot(relativePoint,relativePoint);
        // Homogeneous direction projection is scale invariant; no normalization is needed.
        float hit=d2>0.0000000001?cmiRiftDistance(relativePoint,CmiRift):-1.0;
        return hit>0.0&&d2>hit*hit?1.0:0.0;
    }
    if(!cmiStormCloudActive())return 1.0;
    float h=relativePoint.y+CmiStormCloud.w;
    float below=CmiStormCloud.y-h;
    if(below<=0.0)return 1.0;
    float r0=max(16.0,CmiStormCloud.x*abs(h));
    float r1=r0+max(16.0,0.15*r0);
    float r2=r0+max(48.0,0.5*r0);
    float r3=r2+max(32.0,0.25*r0);
    vec2 delta=relativePoint.xz-CmiStormCenter.xz;
    float d2=dot(delta,delta);
    if(d2>=r3*r3)return 1.0;
    float ring=2.0*cmiCloudSmooth((d2-r0*r0)/(r1*r1-r0*r0))
        -cmiCloudSmooth((d2-r2*r2)/(r3*r3-r2*r2));
    return mix(1.0,ring,CmiStormCloud.z*cmiCloudSmooth(below/16.0));
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
