#ifndef CMI_NATIVE_RIFT
#define CMI_NATIVE_RIFT
uniform vec4 CmiRift;
uniform vec4 CmiSkyContext;
uniform int CmiSkyDimension;
uniform float CmiRiftEnabled;
uniform float CmiRiftReactive;
// Only a main-view caller can clip clouds. Shared reflection and environment callers keep false.
bool cmiMainView = false;
bool cmiRayActive(vec3 direction) {
    return CmiRiftEnabled > 0.5 && (CmiSkyDimension != 0 || cmiRiftDistance(normalize(direction), CmiRift) > 0.0);
}
bool cmiCloudVisible(vec3 cameraRelativePoint) {
    if (!cmiMainView || CmiRiftEnabled < 0.5) return true;
    if (CmiSkyDimension != 0) return false;
    float distanceToSample = length(cameraRelativePoint);
    if (distanceToSample < 0.00001) return true;
    float limit = cmiRiftDistance(cameraRelativePoint / distanceToSample, CmiRift);
    return limit <= 0.0 || distanceToSample < limit;
}
float cmiCloudLimit(vec3 direction, float maximum) {
    if (!cmiMainView || CmiRiftEnabled < 0.5) return maximum;
    if (CmiSkyDimension != 0) return 0.0;
    float limit = cmiRiftDistance(normalize(direction), CmiRift);
    return limit > 0.0 ? min(maximum, limit) : maximum;
}
vec3 cmiNativeSky(vec3 original, vec3 direction, float gamma, float radianceScale) {
    if (!cmiRayActive(direction)) return original;
    return pow(max(cmiAllaySky(normalize(direction), CmiSkyContext) * radianceScale, vec3(0.0)), vec3(1.0 / gamma));
}
#endif
