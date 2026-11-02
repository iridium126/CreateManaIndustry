layout(binding=4)uniform sampler2D uShadowDepthHierarchy;
layout(binding=5)uniform sampler2D uScreenDepthHierarchy;
uniform bool uShadowDepthEnabled,uScreenDepthEnabled,uShadowDepthReverse;
uniform mat4 uShadowDepthMatrix,uScreenDepthMatrix;
// x=radial convention (0/1/2), y=radial amount, z=depth scale, w=depth margin.
uniform vec4 uShadowDepthParams;
uniform float uShadowDepthFilter;
uniform vec3 uScreenOriginDelta;
uniform float uScreenDepthMargin;
uniform ivec2 uShadowDepthSourceSize,uScreenDepthSourceSize;
bool cmiDepthFinite(vec4 value){return !any(isnan(value))&&!any(isinf(value));}
vec2 cmiDepthDivide(vec2 numerator,vec2 denominator){
    vec4 q=vec4(numerator.x/denominator.x,numerator.x/denominator.y,numerator.y/denominator.x,numerator.y/denominator.y);
    return vec2(min(min(q.x,q.y),min(q.z,q.w)),max(max(q.x,q.y),max(q.z,q.w)));
}
bool cmiDepthBounds(mat4 matrix,vec3 center,vec3 extent,out vec3 lo,out vec3 hi){
    vec4 c=matrix*vec4(center,1.);
    vec4 e=abs(matrix[0])*extent.x+abs(matrix[1])*extent.y+abs(matrix[2])*extent.z;
    if(!cmiDepthFinite(c)||!cmiDepthFinite(e)||c.w-e.w<=1e-5)return false;
    vec2 w=vec2(c.w-e.w,c.w+e.w);
    vec2 x=cmiDepthDivide(vec2(c.x-e.x,c.x+e.x),w),y=cmiDepthDivide(vec2(c.y-e.y,c.y+e.y),w),z=cmiDepthDivide(vec2(c.z-e.z,c.z+e.z),w);
    lo=vec3(x.x,y.x,z.x);hi=vec3(x.y,y.y,z.y);return cmiDepthFinite(vec4(lo,1.))&&cmiDepthFinite(vec4(hi,1.));
}
bool cmiDepthHidden(sampler2D hierarchy,ivec2 sourceSize,vec2 lo,vec2 hi,float nearest,bool reversed,float margin){
    if(!cmiDepthFinite(vec4(lo,hi))||isnan(nearest)||isinf(nearest)||any(lessThan(sourceSize,ivec2(1))))return false;
    // Do not clamp a partial native range into evidence for the entire caster.
    if(any(lessThan(lo,vec2(0.)))||any(greaterThan(hi,vec2(1.)))||any(greaterThan(lo,hi))||nearest<0.||nearest>1.)return false;
    int levels=textureQueryLevels(hierarchy),level=0;
    ivec2 a=clamp(ivec2(floor(lo*vec2(sourceSize))),ivec2(0),sourceSize-1),b=clamp(ivec2(floor(hi*vec2(sourceSize))),ivec2(0),sourceSize-1);
    for(;;){ivec2 size=textureSize(hierarchy,level);
        // Inverse of the builder's integer partition, including every NPOT boundary.
        a=((a+1)*size-1)/sourceSize;b=((b+1)*size-1)/sourceSize;
        if(all(lessThanEqual(b-a,ivec2(1)))||level+1>=levels)break;sourceSize=size;level++;}
    float occluder=reversed?1.:0.;
    for(int y=a.y;y<=b.y;y++)for(int x=a.x;x<=b.x;x++){
        float depth=texelFetch(hierarchy,ivec2(x,y),level).r;
        if(isnan(depth)||isinf(depth))return false;
        occluder=reversed?min(occluder,depth):max(occluder,depth);
    }
    return reversed?nearest<occluder-margin:nearest>occluder+margin;
}
bool cmiShadowDepthReject(vec3 center,vec3 extent){
    vec3 lo,hi;if(!cmiDepthBounds(uShadowDepthMatrix,center,extent,lo,hi))return false;
    int mode=int(uShadowDepthParams.x);
    if(mode==1||mode==2){
        // Interval radial bound includes interior extrema; eight distorted corners are insufficient.
        vec2 nearXY=max(max(lo.xy,-hi.xy),vec2(0.)),farXY=max(abs(lo.xy),abs(hi.xy));
        float r0=mode==1?pow(dot(nearXY*nearXY,nearXY*nearXY),.25):length(nearXY);
        float r1=mode==1?pow(dot(farXY*farXY,farXY*farXY),.25):length(farXY);
        float amount=uShadowDepthParams.y;if(amount<0.||amount>=1.)return false;
        vec2 denominator=vec2(r0,r1)*amount+(1.-amount);
        if(denominator.x<=0.)return false;
        vec2 x=cmiDepthDivide(vec2(lo.x,hi.x),denominator),y=cmiDepthDivide(vec2(lo.y,hi.y),denominator);
        lo.xy=vec2(x.x,y.x);hi.xy=vec2(x.y,y.y);
    }else if(mode!=0)return false;
    float scale=uShadowDepthParams.z;if(scale<=0.)return false;
    vec2 uvLo=lo.xy*.5+.5-vec2(uShadowDepthFilter),uvHi=hi.xy*.5+.5+vec2(uShadowDepthFilter);
    float nearest=(uShadowDepthReverse?hi.z:lo.z)*scale*.5+.5;
    return cmiDepthHidden(uShadowDepthHierarchy,uShadowDepthSourceSize,uvLo,uvHi,nearest,uShadowDepthReverse,uShadowDepthParams.w);
}
bool cmiScreenDepthReject(vec3 center,vec3 extent){
    float begin=0.,end=1e20;
    for(int j=0;j<6;j++){
        vec4 plane=uShadowViewPlanes[j];float support=dot(plane.xyz,center)+plane.w+dot(abs(plane.xyz),extent),direction=dot(plane.xyz,uShadowRay);
        if(abs(direction)<1e-7){if(support<0.)return false;}
        else if(direction>0.)begin=max(begin,-support/direction);else end=min(end,-support/direction);
    }
    if(begin>end||end>=1e19||isnan(end)||isinf(end))return false;
    vec3 delta=uShadowRay*(end-begin),sweptCenter=center+uShadowRay*begin+delta*.5+uScreenOriginDelta,sweptExtent=extent+abs(delta)*.5;
    vec3 lo,hi;if(!cmiDepthBounds(uScreenDepthMatrix,sweptCenter,sweptExtent,lo,hi))return false;
    return cmiDepthHidden(uScreenDepthHierarchy,uScreenDepthSourceSize,lo.xy*.5+.5,hi.xy*.5+.5,lo.z*.5+.5,false,uScreenDepthMargin);
}
