#pragma once
#include <optix.h>
#include <vector_types.h>

// Independent ABI 10 packet. Production particle records are never imported.
// Header: magic,schema,nonce[2],epoch[2],source[2],target[2],capacity,error,
// time,dt,horizon,reserved,scene[2],complete,reserved[13]. Rows are 64 bytes.
struct AvoidanceParticle {float4 position,velocity;uint4 identity;float4 preferred;};
struct AvoidanceResult {float4 hint;uint4 identity;float4 position,velocity;};
struct AvoidanceParams {
    const unsigned* input;unsigned* output;OptixInstance* instances;
    unsigned long long traversable,unit,nonce,epoch,generation,scene;
    unsigned capacity;float horizon;
};
