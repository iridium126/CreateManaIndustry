#pragma once
#include "Spatial.h"
#include <cuda.h>
#include <cstdint>
#include <memory>

struct TerrainSnapshot;
struct StormTick;
struct StormSpatial;
std::shared_ptr<const TerrainSnapshot> copyTerrainSnapshot(uint64_t scene,uint64_t revision,
        const SpatialParticle* data,int count,size_t bytes,unsigned capacity);
StormSpatial* createStormSpatial(unsigned capacity,unsigned probes,CUstream stream);
void destroyStormSpatial(StormSpatial* spatial);
CUdeviceptr queryStormSpatial(StormSpatial& spatial,unsigned slot,CUfunction prepare,
        CUdeviceptr members,const StormTick& tick,const TerrainSnapshot* terrain,const CUevent* timing);
