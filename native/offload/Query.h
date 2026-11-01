#pragma once
// Shared NVRTC/host ABI; isolated experiment, never a production particle layout.
struct Query {
    const float4* points;
    const float4* requests; // xyz local origin, w radius; null = point/radius fixture
    const unsigned* owners; // index in points (not a compacted pool slot); null = query index
    const unsigned* poolIndices; // optional point-index -> original GL pool-slot mapping
    unsigned long long* offsets;
    unsigned* ids;
    unsigned* counts;
    unsigned long long* header;
    int* heads;
    int* next;
    unsigned n,q,capacity,hashMask,write;
    float radius;
    unsigned long long traversable;
};
#ifdef __CUDACC__
__device__ inline float4 origin(const Query& p,unsigned i){return p.requests?p.requests[i]:p.points[i];}
__device__ inline float queryRadius(const Query& p,unsigned i){return p.requests?p.requests[i].w:p.radius;}
__device__ inline unsigned owner(const Query& p,unsigned i){return p.owners?p.owners[i]:i;}
__device__ inline unsigned resultId(const Query& p,unsigned i){return p.poolIndices?p.poolIndices[i]:i;}
#endif
