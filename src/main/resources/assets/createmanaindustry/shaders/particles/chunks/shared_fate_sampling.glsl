// Twenty-four vec4s per link. 10..18 hold the 33-point arc table;
// 20..23 retain the previous pose for interpolated fragment release.
const uint CMI_FATE_VECTORS=24u;
const int CMI_FATE_ARC_STEPS=32;
uint cmiFateMix(uint x){x^=x>>16u;x*=0x7feb352du;x^=x>>15u;x*=0x846ca68bu;return x^(x>>16u);}
float cmiFateRandom(uint key){return float(cmiFateMix(key)>>8u)*(1.0/16777216.0);}
float cmiFateSmooth(float previous,float target,float dt){
    float value=mix(previous,target,1.0-exp(-max(0.0,dt)/.14));
    return abs(value-target)<.002?target:value;
}
float cmiFateSmoothLevel(float previous,float target,float dt){
    float boundary=round(previous);
    if(abs(previous-boundary)<.002&&abs(target-boundary)<.08)return boundary;
    return cmiFateSmooth(previous,target,dt);
}
float cmiFateLevel(float density,float maximum){return clamp(log2(max(.0625,density)),-4.0,maximum);}
float cmiFateDensity(float level){
    float k=ceil(level-.00001),blend=clamp(level-k+1.0,0.0,1.0);
    return exp2(k-1.0)*(1.0+blend);
}
uint cmiFateCount(float level,float low,float high){
    float density=exp2(ceil(level-.00001));
    return uint(clamp(ceil(high*density)-floor(low*density)+1.0,1.0,65536.0));
}
// Nested power-of-two lattices are anchored at distance zero, independent of camera and layer offsets.
// Existing even samples remain fixed; new odd samples fade in as the density level increases.
struct CmiFateSample{float distance;float weight;float spacing;uint identity;};
CmiFateSample cmiFateSample(uint ordinal,float level,float low,float high){
    float k=ceil(level-.00001),density=exp2(k),first=floor(low*density),last=ceil(high*density);
    float evenFirst=ceil(first*.5)*2.0;
    uint evenCount=uint(max(0.0,floor((last-evenFirst)*.5)+1.0));
    bool even=ordinal<evenCount;
    float grid=even?evenFirst+2.0*float(ordinal):floor(first*.5)*2.0+1.0+2.0*float(ordinal-evenCount);
    CmiFateSample result;
    result.distance=grid/density;
    result.weight=even?1.0:clamp(level-k+1.0,0.0,1.0);
    result.spacing=1.0/cmiFateDensity(level);
    result.identity=floatBitsToUint(result.distance);return result;
}
float cmiFateArcToT(float distance,float lengths[33]){
    if(lengths[32]<.00001)return 0.0;
    float s=clamp(distance,0.0,lengths[32]);int low=0,high=32;
    for(int step=0;step<5;step++){
        int mid=(low+high)/2;if(lengths[mid]<s)low=mid;else high=mid;
    }
    float blend=clamp((s-lengths[low])/max(.000001,lengths[high]-lengths[low]),0.0,1.0);
    return (float(low)+blend*float(high-low))/32.0;
}
bool cmiFateClipPlane(float a,float b,inout float enter,inout float leave){
    if(a<0.0&&b<0.0)return false;
    if(a<0.0)enter=max(enter,a/(a-b));else if(b<0.0)leave=min(leave,a/(a-b));
    return enter<=leave;
}
bool cmiFateClipExpanded(vec4 a,vec4 b,vec2 padding,out float enter,out float leave){
    enter=0.0;leave=1.0;
    // Clip homogeneous segments before division, including segments crossing the near plane.
    return cmiFateClipPlane(a.w-.0001,b.w-.0001,enter,leave)
        &&cmiFateClipPlane(a.z+a.w,b.z+b.w,enter,leave)
        &&cmiFateClipPlane(a.w-a.z,b.w-b.z,enter,leave)
        &&cmiFateClipPlane(a.x+1.04*a.w+padding.x,b.x+1.04*b.w+padding.x,enter,leave)
        &&cmiFateClipPlane(1.04*a.w-a.x+padding.x,1.04*b.w-b.x+padding.x,enter,leave)
        &&cmiFateClipPlane(a.y+1.04*a.w+padding.y,b.y+1.04*b.w+padding.y,enter,leave)
        &&cmiFateClipPlane(1.04*a.w-a.y+padding.y,1.04*b.w-b.y+padding.y,enter,leave);
}
bool cmiFateClip(vec4 a,vec4 b,out float enter,out float leave){
    return cmiFateClipExpanded(a,b,vec2(0),enter,leave);
}
float cmiFatePulse(float distance,float time){
    float x=mod(distance-2.0*time+3.0,6.0)-3.0;
    return exp(-x*x/(x>0.0?.035:.48));
}
