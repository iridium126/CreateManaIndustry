#include <cuda_runtime.h>
#include "Avoidance.h"
extern "C" {__constant__ AvoidanceParams params;}
__device__ float3 scale(float3 a,float s){return make_float3(a.x*s,a.y*s,a.z*s);}
__device__ float3 add(float3 a,float3 b){return make_float3(a.x+b.x,a.y+b.y,a.z+b.z);}
__device__ float3 cross3(float3 a,float3 b){return make_float3(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x);}
__device__ float length3(float3 a){return sqrtf(a.x*a.x+a.y*a.y+a.z*a.z);}
extern "C" __global__ void __raygen__avoidance(){
    unsigned i=optixGetLaunchIndex().x;if(i>=params.capacity||params.output[11])return;
    auto v=((const AvoidanceParticle*)(params.input+32))[i];auto& out=((AvoidanceResult*)(params.output+32))[i];if(!out.identity.w)return;
    float3 forward=make_float3(v.preferred.x,v.preferred.y,v.preferred.z);float speed=length3(forward);if(speed<.01f)return;
    forward=scale(forward,1.f/speed);float3 axis=fabsf(forward.y)<.9f?make_float3(0,1,0):make_float3(1,0,0);
    float3 right=cross3(forward,axis);right=scale(right,1.f/length3(right));float3 up=cross3(right,forward);
    float reach=fmaxf(.05f,fminf(speed,v.velocity.w)*params.horizon+.34f),clearance=reach;float3 hint=make_float3(0,0,0);
    const float cs[3]={1.f,-.5f,-.5f},sn[3]={0,.866025404f,-.866025404f};
    for(unsigned k=0;k<4;k++){
        float3 direction=forward;if(k)direction=add(scale(forward,.906307787f),scale(add(scale(right,cs[k-1]),scale(up,sn[k-1])),.422618262f));
        unsigned nx=0,ny=0,nz=0,at=__float_as_uint(reach),hit=0;
        optixTrace((OptixTraversableHandle)params.traversable,make_float3(v.position.x,v.position.y,v.position.z),direction,0.f,reach,0.f,255,
            OPTIX_RAY_FLAG_CULL_BACK_FACING_TRIANGLES|OPTIX_RAY_FLAG_DISABLE_ANYHIT,0,1,0,nx,ny,nz,at,hit);
        if(hit){float distance=__uint_as_float(at),weight=fmaxf(0.f,1.f-distance/reach)*.25f;hint=add(hint,scale(make_float3(__uint_as_float(nx),__uint_as_float(ny),__uint_as_float(nz)),weight));clearance=fminf(clearance,distance);}
    }
    out.hint=make_float4(hint.x,hint.y,hint.z,clearance);
}
extern "C" __global__ void __closesthit__avoidance(){
    unsigned face=optixGetPrimitiveIndex()/2;float3 n=face==0?make_float3(0,0,-1):face==1?make_float3(0,0,1):face==2?make_float3(-1,0,0):face==3?make_float3(1,0,0):face==4?make_float3(0,-1,0):make_float3(0,1,0);
    optixSetPayload_0(__float_as_uint(n.x));optixSetPayload_1(__float_as_uint(n.y));optixSetPayload_2(__float_as_uint(n.z));optixSetPayload_3(__float_as_uint(optixGetRayTmax()));optixSetPayload_4(1);
}
extern "C" __global__ void __miss__avoidance(){}
