#pragma once
#include "ParticleVectors.h"
#include "ResourceLayout.h"
#include <cstddef>

struct SpatialParticle {float4 position,velocity;uint4 identity;float4 preferred;};
static_assert(sizeof(SpatialParticle)==CMI_RESOURCE_SPATIAL_RECORD_BYTES,"Spatial input contract");
static_assert(offsetof(SpatialParticle,identity)==32&&offsetof(SpatialParticle,preferred)==48,"Spatial input offsets");
