#include <cuda_runtime.h>
#include "Spatial.h"
#include "StormStep.h"
#include "OcclusionProof.h"
extern "C" {__constant__ SpatialParams params;}
__device__ float3 scale(float3 a,float s){return make_float3(a.x*s,a.y*s,a.z*s);}
__device__ float3 add(float3 a,float3 b){return make_float3(a.x+b.x,a.y+b.y,a.z+b.z);}
__device__ float3 cross3(float3 a,float3 b){return make_float3(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x);}
__device__ float length3(float3 a){return sqrtf(a.x*a.x+a.y*a.y+a.z*a.z);}
__device__ float4 motionHint(float4 position,float3 forward,float speed,float cap){
 float3 axis=fabsf(forward.y)<.9f?make_float3(0,1,0):make_float3(1,0,0),right=cross3(forward,axis);right=scale(right,1.f/length3(right));float3 up=cross3(right,forward);
 float reach=fmaxf(.05f,fminf(speed,cap)*.1f+.34f),clearance=reach;float3 hint=make_float3(0,0,0);
 const float cs[3]={1.f,-.5f,-.5f},sn[3]={0,.866025404f,-.866025404f};
 for(unsigned k=0;k<params.probes;k++){
  unsigned side=params.probes==2?unsigned(params.generation%3):k-1;float3 direction=k?add(scale(forward,.906307787f),scale(add(scale(right,cs[side]),scale(up,sn[side])),.422618262f)):forward;
  unsigned nx=0,ny=0,nz=0,at=__float_as_uint(reach),hit=0;
  optixTrace((OptixTraversableHandle)params.traversable,make_float3(position.x,position.y,position.z),direction,0.f,reach,0.f,1,
    OPTIX_RAY_FLAG_CULL_BACK_FACING_TRIANGLES|OPTIX_RAY_FLAG_DISABLE_ANYHIT,0,1,0,nx,ny,nz,at,hit);
  if(hit){float d=__uint_as_float(at),w=fmaxf(0.f,1.f-d/reach)/params.probes;hint=add(hint,scale(make_float3(__uint_as_float(nx),__uint_as_float(ny),__uint_as_float(nz)),w));clearance=fminf(clearance,d);}
 }
 return make_float4(hint.x,hint.y,hint.z,clearance);
}
extern "C" __global__ void __raygen__spatial(){
 unsigned i=optixGetLaunchIndex().x;if(i>=params.members)return;
 if(params.stormMembers){
   const auto& member=params.stormMembers[i];
   auto steering=member.spatialSteering;
   float3 forward=make_float3(steering.x,steering.y,steering.z);
   float speed=length3(forward);
   bool needsQuery=member.identity==params.epoch&&member.state==StormAlive&&steering.w>0.f&&speed>=.01f;
   params.stormHints[i]=needsQuery?motionHint(member.position,scale(forward,1.f/speed),speed,steering.w):make_float4(0,0,0,0);
   return;
 }
 if(params.output[11])return;
 auto v=((const SpatialParticle*)(params.input+32))[i];auto& out=((SpatialResult*)(params.output+32))[i];if(!out.identity.w||(v.identity.w&0x80000000u)!=0u)return;
 float3 forward=make_float3(v.preferred.x,v.preferred.y,v.preferred.z);float speed=length3(forward);if(speed<.01f)return;forward=scale(forward,1.f/speed);
 if(params.kind){
   unsigned candidate=0,a=0,b=0,c=0,d=0;float3 eye=make_float3(v.position.x,v.position.y,v.position.z);
   optixTrace((OptixTraversableHandle)params.traversable,eye,forward,.001f,fmaxf(.001f,v.velocity.w),0.f,2,
     OPTIX_RAY_FLAG_TERMINATE_ON_FIRST_HIT|OPTIX_RAY_FLAG_DISABLE_ANYHIT,0,1,0,candidate,a,b,c,d);
   bool hidden=false;
   if(candidate>params.capacity&&candidate<=params.capacity+params.terrainCount){
     // Prove visibility from the very rows used to build this GAS. Production
     // packets contain only query rows; standalone fixtures can embed boxes.
     auto box=params.terrainRows?params.terrainRows[candidate-1-params.capacity]:((const SpatialParticle*)(params.input+32))[candidate-1];
     hidden=(box.identity.w&2u)!=0u;
     float3 center=add(eye,scale(forward,v.velocity.w));
     for(unsigned corner=0;corner<8&&hidden;corner++){
       float3 coverage=make_float3((corner&1)?v.velocity.x:-v.velocity.x,(corner&2)?v.velocity.y:-v.velocity.y,(corner&4)?v.velocity.z:-v.velocity.z);
       hidden=(v.identity.w&256u)!=0u?wholeBoundShadowed(eye,add(forward,coverage),v.position.w,v.velocity.w,box):wholeBoundOccluded(add(eye,coverage),center,v.position.w,box);
     }
     if(hidden){out.position=box.position;out.velocity=box.velocity;}
   }
   out.hint=make_float4(hidden?0.f:1.f,0,0,v.velocity.w);return;
 }
 out.hint=motionHint(v.position,forward,speed,v.velocity.w);
}
extern "C" __global__ void __closesthit__spatial(){
 unsigned instance=optixGetInstanceId(),primitive=optixGetPrimitiveIndex(),row=instance;
 if(params.terrainViews&&instance>=params.capacity){auto view=params.terrainViews[instance-params.capacity];unsigned box=primitive/12u;if(box>=view.count)return;row=view.rows[box];primitive%=12u;}
 if(params.kind){optixSetPayload_0(row+1u);return;}
 unsigned f=primitive/2;float3 n=f==0?make_float3(0,0,-1):f==1?make_float3(0,0,1):f==2?make_float3(-1,0,0):f==3?make_float3(1,0,0):f==4?make_float3(0,-1,0):make_float3(0,1,0);
 optixSetPayload_0(__float_as_uint(n.x));optixSetPayload_1(__float_as_uint(n.y));optixSetPayload_2(__float_as_uint(n.z));optixSetPayload_3(__float_as_uint(optixGetRayTmax()));optixSetPayload_4(1);
}
extern "C" __global__ void __miss__spatial(){if(params.kind)optixSetPayload_0(0);}
