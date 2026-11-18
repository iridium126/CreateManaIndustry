#pragma once
#ifdef CMI_DXR_HOST
struct alignas(16) float4 { float x,y,z,w; };
struct alignas(16) int4 { int x,y,z,w; };
struct alignas(16) uint4 { unsigned x,y,z,w; };
#else
#include <vector_types.h>
#endif
