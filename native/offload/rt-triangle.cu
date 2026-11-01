#include <optix.h>
#include <cuda_runtime.h>
#include "Query.h"
extern "C" {__constant__ Query params;}
__device__ float padding(float4 p,float radius){return fmaxf(1.e-4f,fmaxf(fmaxf(fabsf(p.x),fabsf(p.y)),fabsf(p.z))*0x1p-20f+radius*0x1p-20f);}
extern "C" __global__ void __raygen__query(){
    unsigned i=optixGetLaunchIndex().x;if(i>=params.q||(params.write&&params.header[0]>params.capacity))return;
    float4 p=origin(params,i);unsigned count=0;
    float reach=2.f*(params.radius+padding(p,params.radius)+params.radius*1.e-5f);
    optixTrace((OptixTraversableHandle)params.traversable,make_float3(p.x,p.y,p.z),make_float3(1,0,0),0.f,reach,0.f,255,OPTIX_RAY_FLAG_CULL_FRONT_FACING_TRIANGLES|OPTIX_RAY_FLAG_DISABLE_CLOSESTHIT,0,1,0,count);
    if(!params.write)params.counts[i]=count;
}
extern "C" __global__ void __anyhit__candidate(){
    unsigned i=optixGetLaunchIndex().x,j=optixGetInstanceId();
    if(owner(params,i)!=j){float4 a=origin(params,i),b=params.points[j];float dx=a.x-b.x,dy=a.y-b.y,dz=a.z-b.z;
        if((dx*dx+dy*dy)+dz*dz<=queryRadius(params,i)*queryRadius(params,i)){unsigned count=optixGetPayload_0();if(params.paged){if(!appendPage(params,i,count,j))return;}else if(params.write)params.ids[params.offsets[i]+count]=resultId(params,j);optixSetPayload_0(count+1);}}
    optixIgnoreIntersection();
}
extern "C" __global__ void __miss__noop(){}
