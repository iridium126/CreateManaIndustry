#include <optix.h>
#include <cuda_runtime.h>
#include "Query.h"
extern "C" {__constant__ Query params;}
extern "C" __global__ void __raygen__query(){
    unsigned i=optixGetLaunchIndex().x;if(i>=params.q||params.write&&params.header[0]>params.capacity)return;
    float4 p=origin(params,i);unsigned count=0;
    optixTrace((OptixTraversableHandle)params.traversable,make_float3(p.x,p.y,p.z),make_float3(1,0,0),0.f,1.e-5f,0.f,255,OPTIX_RAY_FLAG_NONE,0,1,0,count);
    if(!params.write)params.counts[i]=count;
}
extern "C" __global__ void __intersection__point(){
    unsigned i=optixGetLaunchIndex().x,j=optixGetPrimitiveIndex();if(owner(params,i)==j)return;
    float4 a=origin(params,i),b=params.points[j];float dx=a.x-b.x,dy=a.y-b.y,dz=a.z-b.z;
    if(params.fused){
        float radius=queryRadius(params,i),d2=(dx*dx+dy*dy)+dz*dz;
        if(d2>=radius*radius)return;
        if(d2<=1.e-8f){
            unsigned mine=__float_as_uint(params.points[owner(params,i)].w),other=__float_as_uint(b.w);
            float4 delta=params.overlapDirections[mine<other?owner(params,i):j];float sign=mine<other?1.f:-1.f;
            dx=delta.x*sign;dy=delta.y*sign;dz=delta.z*sign;d2=(dx*dx+dy*dy)+dz*dz;
        }
        if(d2<radius*radius&&d2>1.e-8f){
            float len=sqrtf(d2),weight=(radius-len)/(len*radius);
            float4 value=params.forces[i];value.x+=dx*weight;value.y+=dy*weight;value.z+=dz*weight;value.w+=1.f;params.forces[i]=value;
        }
        return;
    }
    if((dx*dx+dy*dy)+dz*dz<=queryRadius(params,i)*queryRadius(params,i)){unsigned count=optixGetPayload_0();if(params.paged){if(!appendPage(params,i,count,j))return;}else if(params.write)params.ids[params.offsets[i]+count]=resultId(params,j);optixSetPayload_0(count+1);}
    // No reportIntersection: visit every conservative AABB, never terminate on the first hit.
}
extern "C" __global__ void __miss__noop(){}
