#pragma once
#include <cuda_runtime.h>

// The shadow volume of one convex opaque box from a point is convex. Proving
// all eight corners inside that same volume proves the entire model bound.
// The queried point alone never authorizes culling. Bounds include all parts.
__device__ bool wholeBoundOccluded(float3 eye,float3 center,float radius,const SpatialParticle& box){
    float3 oc=make_float3(box.position.x,box.position.y,box.position.z),ext=make_float3(box.velocity.x,box.velocity.y,box.velocity.z);
    float magnitude=fmaxf(fmaxf(fabsf(eye.x),fabsf(eye.y)),fabsf(eye.z));
    magnitude=fmaxf(magnitude,fmaxf(fmaxf(fabsf(center.x),fabsf(center.y)),fabsf(center.z)));
    float margin=fmaxf(.001f,magnitude*0x1p-20f);radius+=margin;
    ext.x-=margin;ext.y-=margin;ext.z-=margin;
    if(radius<=0||ext.x<=0||ext.y<=0||ext.z<=0)return false;
    if(fabsf(eye.x-oc.x)<=ext.x+margin&&fabsf(eye.y-oc.y)<=ext.y+margin&&fabsf(eye.z-oc.z)<=ext.z+margin)return false;
    if(fabsf(center.x-oc.x)<=ext.x+radius&&fabsf(center.y-oc.y)<=ext.y+radius&&fabsf(center.z-oc.z)<=ext.z+radius)return false;
    for(unsigned corner=0;corner<8;corner++){
        float target[]={center.x+((corner&1)?radius:-radius),center.y+((corner&2)?radius:-radius),center.z+((corner&4)?radius:-radius)};
        float origin[]={eye.x,eye.y,eye.z},middle[]={oc.x,oc.y,oc.z},half[]={ext.x,ext.y,ext.z};
        float enter=0,leave=1;
        for(unsigned k=0;k<3;k++){
            float direction=target[k]-origin[k];
            if(fabsf(direction)<1e-12f){if(origin[k]<middle[k]-half[k]||origin[k]>middle[k]+half[k])return false;continue;}
            float a=(middle[k]-half[k]-origin[k])/direction,b=(middle[k]+half[k]-origin[k])/direction;
            enter=fmaxf(enter,fminf(a,b));leave=fminf(leave,fmaxf(a,b));
        }
        if(enter>=leave||enter<=0||leave>=1)return false;
    }
    return true;
}

// Parallel light rays use an orthographic proof, independent of camera visibility.
__device__ bool wholeBoundShadowed(float3 center,float3 towardLight,float radius,float reach,const SpatialParticle& box){
    float magnitude=fmaxf(fmaxf(fabsf(center.x),fabsf(center.y)),fabsf(center.z));
    float margin=fmaxf(.001f,magnitude*0x1p-20f);radius+=margin;
    float middle[]={box.position.x,box.position.y,box.position.z};
    float half[]={box.velocity.x-margin,box.velocity.y-margin,box.velocity.z-margin};
    float direction[]={towardLight.x,towardLight.y,towardLight.z};
    if(radius<=0||half[0]<=0||half[1]<=0||half[2]<=0||reach<=0)return false;
    for(unsigned corner=0;corner<8;corner++){
        float origin[]={center.x+((corner&1)?radius:-radius),center.y+((corner&2)?radius:-radius),center.z+((corner&4)?radius:-radius)};
        float enter=0,leave=reach;
        for(unsigned axis=0;axis<3;axis++){
            if(fabsf(direction[axis])<1e-12f){if(origin[axis]<middle[axis]-half[axis]||origin[axis]>middle[axis]+half[axis])return false;continue;}
            float a=(middle[axis]-half[axis]-origin[axis])/direction[axis],b=(middle[axis]+half[axis]-origin[axis])/direction[axis];
            enter=fmaxf(enter,fminf(a,b));leave=fminf(leave,fmaxf(a,b));
        }
        if(enter>=leave||enter<=margin||enter>=reach-margin)return false;
    }
    return true;
}
