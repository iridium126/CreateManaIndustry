#pragma once
// Shared NVRTC/host ABI; isolated experiment, never a production particle layout.
struct Query {
    const float4* points;
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
