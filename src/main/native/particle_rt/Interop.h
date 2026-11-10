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
bool queryInputReleased(void* bridge,unsigned slot,uint64_t value);
void acquireBridgeLease(void* bridge);
void releaseBridgeLease(void* bridge);
void validateBridgeCapacity(void* bridge,size_t bytes,unsigned slots);
void consumeQueryOutput(void* bridge,unsigned slot,uint64_t value);
void retireQueryWithoutOutput(void* bridge,unsigned slot,uint64_t value);
