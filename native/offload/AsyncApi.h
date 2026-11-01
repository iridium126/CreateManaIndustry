#pragma once
#include <cuda.h>
#include <array>
#include <cstdint>
#include <string>

// Only used by the optional worker. These are temporary query resources, not
// production particle records. GL entry points remain on the render thread.
struct BridgeTicket {
    CUcontext context{};
    CUdeviceptr input{},output{};
    CUexternalSemaphore inputReady{},outputReady{};
    size_t bytes{};
    uint64_t value{};
};
BridgeTicket releaseQueryInput(void* bridge,unsigned slot);
void acquireBridgeLease(void* bridge);
void releaseBridgeLease(void* bridge);
void consumeQueryOutput(void* bridge,unsigned slot,uint64_t value);
void retireQueryWithoutOutput(void* bridge,unsigned slot,uint64_t value);

struct QueryView {
    CUdeviceptr points{},requests{},owners{},poolIndices{},output{};
    unsigned n{},q{},capacity{},live{},idsOffsetWords{};
    float radius{};
    uint64_t nonce{},epoch{};
    bool diagnosticSort{};
    unsigned stage=1,lookupOffsetWords{};
    CUdeviceptr queryLookup{};
};
void* createDetachedQuery(unsigned n,unsigned q,unsigned capacity,const std::string& cuda,const std::string& optix,const std::string& source,const std::string& crt,unsigned mask=31);
CUstream detachedQueryStream(void* query);
void enqueueDetachedQuery(void* query,const QueryView& view,int backend,bool refit,CUexternalSemaphore done,uint64_t value);
CUresult detachedQueryReady(void* query);
std::array<double,7> detachedQueryTiming(void* query);
void destroyDetachedQuery(void* query);
