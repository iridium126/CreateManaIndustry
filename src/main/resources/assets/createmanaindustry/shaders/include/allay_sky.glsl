#ifndef CMI_ALLAY_SKY
#define CMI_ALLAY_SKY
// The complete quadratic Julia projection is rasterized once. No orbit loop runs in cloud samples.
uniform sampler2D CmiJuliaHit;
uniform mat4 CmiSkyViewProjection;
float cmiJuliaDistance=-2.0;
float cmiRiftDistance(vec3 worldDirection,vec4 cameraRelativeRift) {
    if(cameraRelativeRift.w<0.0)return -1.0;
    if(cmiJuliaDistance>=-1.0)return cmiJuliaDistance;
    vec4 clip=CmiSkyViewProjection*vec4(worldDirection,0.0);
    if(clip.w<=0.0)return -1.0;
    vec2 uv=clip.xy/clip.w*.5+.5;
    if(any(lessThan(uv,vec2(0.0)))||any(greaterThan(uv,vec2(1.0))))return -1.0;
    cmiJuliaDistance=texture(CmiJuliaHit,uv).r;
    return cmiJuliaDistance;
}
#endif
