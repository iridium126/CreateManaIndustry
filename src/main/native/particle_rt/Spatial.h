#pragma once
#include <optix.h>
#include <vector_types.h>
#include "ResourceLayout.h"
#include <cstddef>

// Independent ABI 12 packet. Production particle records are never imported.
// Header: magic,schema,nonce[2],epoch[2],source[2],target[2],capacity,error,
// time,dt,horizon,reserved,scene[2],complete,reserved[13]. Rows are 64 bytes.
struct SpatialParticle {float4 position,velocity;uint4 identity;float4 preferred;};
struct SpatialResult {float4 hint;uint4 identity;float4 position,velocity;};
struct SpatialTerrainView {const unsigned* rows;unsigned count,reserved;};
static_assert(sizeof(SpatialParticle)==CMI_RESOURCE_SPATIAL_RECORD_BYTES,"RT input contract");
static_assert(sizeof(SpatialResult)==CMI_RESOURCE_SPATIAL_RECORD_BYTES,"RT output contract");
static_assert(offsetof(SpatialParticle,identity)==32&&offsetof(SpatialParticle,preferred)==48,"RT input offsets");
static_assert(offsetof(SpatialResult,identity)==16&&offsetof(SpatialResult,velocity)==48,"RT output offsets");
struct SpatialParams {
    const unsigned* input;unsigned* output;OptixInstance* instances;
    unsigned long long traversable,unit,nonce,epoch,generation,scene;
    unsigned capacity,terrainCapacity,probes,kind;float horizon;
    unsigned members,terrainCount;
    const SpatialTerrainView* terrainViews;
    const SpatialParticle* terrainRows;
    const struct StormStepMember* stormMembers;
    float4* stormHints;
};
#ifdef __CUDACC__
__device__ inline OptixInstance movingInstance(float4 position,float4 velocity,unsigned member,
        bool alive,OptixTraversableHandle unit){
    OptixInstance instance{};
    float radius=alive?position.w:.34f;
    float x=alive?velocity.x:0.f,y=alive?velocity.y:0.f,z=alive?velocity.z:0.f;
    instance.transform[0]=radius+fabsf(x)*.05f;
    instance.transform[5]=radius+fabsf(y)*.05f;
    instance.transform[10]=radius+fabsf(z)*.05f;
    instance.transform[3]=alive?position.x+x*.05f:0.f;
    instance.transform[7]=alive?position.y+y*.05f:0.f;
    instance.transform[11]=alive?position.z+z*.05f:0.f;
    instance.instanceId=member;
    instance.visibilityMask=alive?1u:0u;
    instance.flags=OPTIX_INSTANCE_FLAG_DISABLE_ANYHIT;
    instance.traversableHandle=unit;
    return instance;
}
#endif
