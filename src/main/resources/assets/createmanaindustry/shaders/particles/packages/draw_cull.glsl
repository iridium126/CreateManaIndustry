uniform vec3 uCamPos;
uniform float uMainRangeSquared; // negative keeps the native shadow distance policy
#ifdef CMI_SPLIT_DRAW
uniform vec4 uFrustum[13];
uniform uint uFrustumCount;
uniform vec2 uCullBounds; // distance AND (safe box OR planes); negative disables a box
#else
uniform vec4 uFrustum[6];
#endif
#pragma cmi_include chunks/frustum_culling.glsl

float packageDrawRadius(float boxRadius,float rigRadius,float sway,float parentScale) {
    return (max(boxRadius,rigRadius)+abs(sway)+2.0)*parentScale;
}
bool packageOutsideMainRange(vec3 rel,uint flags) {
    return (flags&1u)==0u && cmiOutsideRange(rel,uMainRangeSquared);
}
bool packageGeometryVisible(vec3 a,vec3 b,float r,uint flags) {
    if(packageOutsideMainRange(a,flags))return false;
#ifdef CMI_SPLIT_DRAW
    vec3 lo=min(a,b)-vec3(r),hi=max(a,b)+vec3(r);
    if(uCullBounds.x>=0.0 && (any(greaterThan(lo,vec3(uCullBounds.x))) || any(lessThan(hi,vec3(-uCullBounds.x)))))return false;
    if(uCullBounds.y>=0.0 && !any(greaterThan(lo,vec3(uCullBounds.y))) && !any(lessThan(hi,vec3(-uCullBounds.y))))return true;
    for(uint j=0u;j<uFrustumCount;j++)if(cmiOutsideSweep(uFrustum[j],a,b,r))return false;
#else
    for(int j=0;j<6;j++)if(cmiOutsideSweep(uFrustum[j],a,b,r))return false;
#endif
    return true;
}
