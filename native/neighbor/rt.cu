#include <optix.h>
#include <cuda_runtime.h>
#include "Query.h"
extern "C" {__constant__ Query params;}
extern "C" __global__ void __raygen__query(){
    unsigned i=optixGetLaunchIndex().x;if(i>=params.q||params.write&&params.header[0]>params.capacity)return;
    float4 p=params.points[i];unsigned count=0;
    optixTrace((OptixTraversableHandle)params.traversable,make_float3(p.x,p.y,p.z),make_float3(1,0,0),0.f,1.e-5f,0.f,255,OPTIX_RAY_FLAG_NONE,0,1,0,count);
    if(!params.write)params.counts[i]=count;
}
extern "C" __global__ void __intersection__point(){
    unsigned i=optixGetLaunchIndex().x,j=optixGetPrimitiveIndex();if(i==j)return;
    float4 a=params.points[i],b=params.points[j];float dx=a.x-b.x,dy=a.y-b.y,dz=a.z-b.z;
    if((dx*dx+dy*dy)+dz*dz<=params.radius*params.radius){unsigned count=optixGetPayload_0();if(params.write)params.ids[params.offsets[i]+count]=j;optixSetPayload_0(count+1);}
    // No reportIntersection: visit every conservative AABB, never terminate on the first hit.
}
extern "C" __global__ void __miss__noop(){}
