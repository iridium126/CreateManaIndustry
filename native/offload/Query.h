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
    unsigned *pageHeads,*pageTails,*pageCounter;
    unsigned* pages; // 36 words/page: next, 32 indices, padding
    unsigned pageCapacity,paged;
};
#ifdef __CUDACC__
__device__ inline float4 origin(const Query& p,unsigned i){return p.requests?p.requests[i]:p.points[i];}
__device__ inline float queryRadius(const Query& p,unsigned i){return p.requests?p.requests[i].w:p.radius;}
__device__ inline unsigned owner(const Query& p,unsigned i){return p.owners?p.owners[i]:i;}
__device__ inline unsigned resultId(const Query& p,unsigned i){return p.poolIndices?p.poolIndices[i]:i;}
__device__ inline bool appendPage(const Query& p,unsigned row,unsigned ordinal,unsigned point){
    if((ordinal&31u)==0){
        unsigned page;
        if(ordinal==0){page=row;if(page>=p.pageCapacity){atomicOr(p.header+1,2ull);return false;}}
        else{unsigned value=atomicAdd(p.pageCounter,0u);for(;;){if(value>=p.pageCapacity){atomicOr(p.header+1,2ull);return false;}unsigned old=atomicCAS(p.pageCounter,value,value+1);if(old==value){page=value;break;}value=old;}}
        p.pages[page*36u]=0xffffffffu;
        if(ordinal==0)p.pageHeads[row]=page;else p.pages[p.pageTails[row]*36u]=page;
        p.pageTails[row]=page;
    }
    p.pages[p.pageTails[row]*36u+1u+(ordinal&31u)]=resultId(p,point);return true;
}
#endif
