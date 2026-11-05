#include <cuda_runtime.h>
#include "Spatial.h"
__device__ bool descriptorValid(SpatialParams p){
 auto h=p.input;
 return h[0]==0x41313149u&&h[1]==12u&&h[2]==unsigned(p.nonce)&&h[3]==unsigned(p.nonce>>32)&&h[4]==unsigned(p.epoch)&&h[5]==unsigned(p.epoch>>32)
  &&h[6]==unsigned(p.generation)&&h[7]==unsigned(p.generation>>32)&&
  (static_cast<unsigned long long>(h[8])|(static_cast<unsigned long long>(h[9])<<32))==p.generation+1
  &&h[10]==p.capacity&&h[11]==0u&&h[16]==unsigned(p.scene)&&h[17]==unsigned(p.scene>>32)&&h[18]==1u&&h[19]==p.probes&&h[20]==p.kind
  &&h[28]==p.terrainCount&&h[28]<=p.terrainCapacity&&h[29]==32u+p.capacity*16u&&isfinite(__uint_as_float(h[12]))&&__uint_as_float(h[13])==.05f&&__uint_as_float(h[14])==.1f;
}
extern "C" __global__ void validateSpatial(SpatialParams p){if(threadIdx.x||blockIdx.x)return;for(unsigned k=0;k<32;k++)p.output[k]=p.input[k];p.output[0]=0x4131314fu;p.output[11]=descriptorValid(p)?0u:1u;p.output[18]=0u;}
extern "C" __global__ void prepareSpatial(SpatialParams p){
 unsigned compact=blockIdx.x*blockDim.x+threadIdx.x;
 if((p.kind?0u:p.members)+p.terrainCount==0u&&compact==0u){OptixInstance empty{};empty.transform[0]=empty.transform[5]=empty.transform[10]=1.f;empty.traversableHandle=p.unit;p.instances[0]=empty;}
 if(compact>=p.members+p.terrainCount)return;
 if(p.terrainViews&&compact>=p.members)return;
 unsigned i=compact<p.members?compact:p.capacity+compact-p.members;
 bool descriptor=p.output[11]==0u,terrain=i>=p.capacity;
 auto row=((const SpatialParticle*)(p.input+32))[i];
 bool present=terrain?i-p.capacity<p.input[28]:(row.identity.w&1u)!=0u;
 bool finite=isfinite(row.position.x)&&isfinite(row.position.y)&&isfinite(row.position.z)&&fabsf(row.position.x)<=31000000.f&&fabsf(row.position.y)<=31000000.f&&fabsf(row.position.z)<=31000000.f
   &&isfinite(row.velocity.x)&&isfinite(row.velocity.y)&&isfinite(row.velocity.z)&&isfinite(row.velocity.w)&&isfinite(row.preferred.x)&&isfinite(row.preferred.y)&&isfinite(row.preferred.z);
 bool valid=descriptor&&present&&finite;
 if(terrain)valid=valid&&row.velocity.x>0&&row.velocity.y>0&&row.velocity.z>0&&row.velocity.x<=16&&row.velocity.y<=16&&row.velocity.z<=16&&(row.identity.w&3u)!=0u;
 else valid=valid&&row.identity.x==i&&isfinite(row.position.w)&&row.position.w>=.34f&&row.position.w<=(p.kind?4096.f:1.f)&&row.velocity.w>=0&&row.velocity.w<=(p.kind?1000000.f:128.f)
   &&fabsf(row.velocity.x)<=128&&fabsf(row.velocity.y)<=128&&fabsf(row.velocity.z)<=128
   &&fabsf(row.preferred.x)<=(p.kind?8192.f:128.f)&&fabsf(row.preferred.y)<=(p.kind?8192.f:128.f)&&fabsf(row.preferred.z)<=(p.kind?8192.f:128.f);
 OptixInstance instance{};float r=valid&&!terrain?row.position.w:.34f;
 float vx=valid?row.velocity.x:0,vy=valid?row.velocity.y:0,vz=valid?row.velocity.z:0;
 instance.transform[0]=terrain&&valid?vx:r+fabsf(vx)*.05f;instance.transform[5]=terrain&&valid?vy:r+fabsf(vy)*.05f;instance.transform[10]=terrain&&valid?vz:r+fabsf(vz)*.05f;
 instance.transform[3]=valid?row.position.x+(terrain?0:vx*.05f):0;instance.transform[7]=valid?row.position.y+(terrain?0:vy*.05f):0;instance.transform[11]=valid?row.position.z+(terrain?0:vz*.05f):0;
 instance.instanceId=i;instance.visibilityMask=valid?(terrain?row.identity.w&3u:1u):0u;instance.flags=OPTIX_INSTANCE_FLAG_DISABLE_ANYHIT;instance.traversableHandle=p.unit;
 // Visibility rows describe queries, never physical occluders or IAS instances.
 if(!p.kind||terrain)p.instances[p.kind?compact-p.members:compact]=instance;
 if(!terrain){auto& out=((SpatialResult*)(p.output+32))[i];out={};out.hint.x=p.kind?1.f:0.f;out.identity=row.identity;out.identity.w=valid?row.identity.w:0;out.position=row.position;out.position.w=row.preferred.w;out.velocity=row.velocity;}
 if(descriptor&&present&&!valid)atomicOr(p.output+11,2u);
}
extern "C" __global__ void sealSpatial(SpatialParams p){if(threadIdx.x==0&&blockIdx.x==0)p.output[18]=1u;}
