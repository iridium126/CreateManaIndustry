#include <cuda_runtime.h>
#include "Avoidance.h"
__device__ bool headerValid(AvoidanceParams p){
    auto h=p.input;
    return h[0]==0x41313049u&&h[1]==10u&&h[2]==unsigned(p.nonce)&&h[3]==unsigned(p.nonce>>32)&&
        h[4]==unsigned(p.epoch)&&h[5]==unsigned(p.epoch>>32)&&h[6]==unsigned(p.generation)&&h[7]==unsigned(p.generation>>32)&&
        (static_cast<unsigned long long>(h[8])|(static_cast<unsigned long long>(h[9])<<32))==p.generation+1&&h[10]==p.capacity&&h[11]==0&&h[16]==unsigned(p.scene)&&h[17]==unsigned(p.scene>>32)&&
        isfinite(__uint_as_float(h[12]))&&isfinite(__uint_as_float(h[13]))&&__uint_as_float(h[13])>=0&&__uint_as_float(h[13])<=.5f&&
        __uint_as_float(h[14])==p.horizon&&h[18]==1;
}
extern "C" __global__ void validateAvoidance(AvoidanceParams p){
    if(threadIdx.x||blockIdx.x)return;for(unsigned k=0;k<32;k++)p.output[k]=p.input[k];p.output[0]=0x4131304fu;p.output[11]=headerValid(p)?0u:1u;p.output[18]=0;
}
extern "C" __global__ void prepareAvoidance(AvoidanceParams p){
    unsigned i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=p.capacity)return;
    bool valid=headerValid(p);auto v=((const AvoidanceParticle*)(p.input+32))[i];
    bool active=valid&&(v.identity.w&1u)&&v.identity.x==i&&isfinite(v.position.x)&&isfinite(v.position.y)&&isfinite(v.position.z)&&
        fabsf(v.position.x)<=31000000.f&&fabsf(v.position.y)<=31000000.f&&fabsf(v.position.z)<=31000000.f&&isfinite(v.position.w)&&v.position.w>=.34f&&v.position.w<=1.f&&isfinite(v.velocity.x)&&isfinite(v.velocity.y)&&isfinite(v.velocity.z)&&
        isfinite(v.preferred.x)&&isfinite(v.preferred.y)&&isfinite(v.preferred.z)&&isfinite(v.velocity.w)&&v.velocity.w>=0&&v.velocity.w<=128.f&&fabsf(v.velocity.x)<=128.f&&fabsf(v.velocity.y)<=128.f&&fabsf(v.velocity.z)<=128.f;
    OptixInstance instance{};float r=active?v.position.w:.34f;
    float vx=active?v.velocity.x:0,vy=active?v.velocity.y:0,vz=active?v.velocity.z:0;
    instance.transform[0]=r+fabsf(vx)*p.horizon*.5f;instance.transform[5]=r+fabsf(vy)*p.horizon*.5f;instance.transform[10]=r+fabsf(vz)*p.horizon*.5f;
    instance.transform[3]=active?v.position.x+vx*p.horizon*.5f:0;instance.transform[7]=active?v.position.y+vy*p.horizon*.5f:0;instance.transform[11]=active?v.position.z+vz*p.horizon*.5f:0;
    instance.instanceId=i;instance.visibilityMask=active?255:0;instance.flags=OPTIX_INSTANCE_FLAG_DISABLE_ANYHIT;instance.traversableHandle=p.unit;p.instances[i]=instance;
    auto& out=((AvoidanceResult*)(p.output+32))[i];out={};out.identity=v.identity;out.identity.w=active?v.identity.w:0;out.position=v.position;out.position.w=v.preferred.w;out.velocity=v.velocity;
    if(valid&&(v.identity.w&1u)&&!active)atomicOr(p.output+11,2u);
}
extern "C" __global__ void sealAvoidance(AvoidanceParams p){if(threadIdx.x==0&&blockIdx.x==0)p.output[18]=1;}
