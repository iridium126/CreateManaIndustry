#pragma once
#include "StormDxrAdapter.h"
#include "../particle_common/StormTerrain.h"
#include <array>
#include <atomic>
#include <memory>
#include <cstddef>
#include <cstdint>

namespace stormDxr {
constexpr unsigned slots = CMI_RESOURCE_RT_SLOTS;
constexpr size_t columnBytes = CMI_RESOURCE_STORM_COLUMN_MEMBERS * CMI_RESOURCE_STORM_DISPLAY_COLUMN_BYTES;
constexpr unsigned timingMarks = 10;

struct Buffer {
    ComPtr<ID3D12Resource> resource;
    unsigned char* mapping{};
    size_t size{};
    D3D12_RESOURCE_STATES state{};
    D3D12_GPU_VIRTUAL_ADDRESS address() const { return resource->GetGPUVirtualAddress(); }
};

struct Queue {
    ComPtr<ID3D12CommandQueue> queue;
    ComPtr<ID3D12GraphicsCommandList4> list;
    ComPtr<ID3D12CommandAllocator> allocator;
    ComPtr<ID3D12Fence> fence;
    ID3D12Device5* device{};
    HANDLE event{};
    UINT64 sequence{};
    ~Queue();
    void initialize(ID3D12Device5* owner, D3D12_COMMAND_LIST_TYPE type);
    void begin();
    void finish(ID3D12Fence* published = nullptr, uint64_t value = 0);
};

struct DomeUpload {
    std::vector<float> records;
    std::vector<uint32_t> keys, flows;
};

struct TickResult {
    StormStepReceipt receipt{};
    StormDomeBatch batch{};
    std::vector<float4> feathers;
    std::array<double, 13> timing{};
    bool hasBatch{};
};

struct DisplayTransfer {
    Buffer snapshot, readback;
    ComPtr<ID3D12Fence> sharedFence;
    StormDisplayHeader header{};
    double headerNanos{};
    unsigned char* mapping{};
    uint64_t sequence{};
    int64_t copyNanos{};
    enum State { Idle, Computing, HeaderReady, Copying, Ready, Failed };
    std::atomic<State> state{Idle};
    std::string error;
};

class Gpu {
    enum Kernel {
        Controls, Move, Instances, MotionHints, Prepare, FinishDome, ClearFeathers, CompactFeathers,
        Positions, CaptureColumns, Publish, Seal, KernelCount
    };
    struct Dispatch {
        uint32_t capacity, terrainMask, terrainRevision, terrainOverflow;
        uint32_t controlFirst, controlCount, controlMode, feathers;
        uint32_t display, probes, terrainBoxes, regional;
        uint64_t unitBlas, terrainBlas;
        uint32_t terrainEdited, editPad[3];
    } args{};
    static_assert(sizeof(Dispatch) == 80);

    ComPtr<ID3D12Device5> device;
    Queue compute, transfer;
    ComPtr<ID3D12RootSignature> root;
    ComPtr<ID3D12DescriptorHeap> heap;
    std::array<ComPtr<ID3D12PipelineState>, KernelCount> programs;
    ComPtr<ID3D12QueryHeap> queries;
    UINT64 frequency{};
    UINT descriptorSize{};
    Buffer inputs[10], storage[17];
    Buffer results, upload, tlas, terrainBlas, unitBlas, accelerationScratch, dummySnapshot;
    size_t uploadOffset{};
    std::vector<Buffer> retiredBuffers;
    std::shared_ptr<const std::vector<SpatialParticle>> selectedGeometry;
    bool domeInstalled{}, regional{}, outerFeedback{}, sharedDisplay;
    uint32_t geometryCount{};
    StormTerrainCache terrain;
    std::vector<uint32_t> commandMarks;
    uint32_t commandGroup{};

    Buffer buffer(size_t size, D3D12_HEAP_TYPE type, D3D12_RESOURCE_STATES state,
            D3D12_RESOURCE_FLAGS flags = D3D12_RESOURCE_FLAG_NONE,
            D3D12_HEAP_FLAGS heapFlags = D3D12_HEAP_FLAG_NONE);
    Buffer uav(size_t size);
    void allocate(Buffer& target, size_t size, D3D12_RESOURCE_STATES state);
    void transition(Buffer& source, D3D12_RESOURCE_STATES state);
    void barrier();
    D3D12_CPU_DESCRIPTOR_HANDLE descriptor(unsigned index) const;
    void srv(unsigned index, const Buffer& source, unsigned stride);
    void uavView(unsigned index, const Buffer& source, unsigned stride);
    void bind(DisplayTransfer* display);
    void setAccelerationView();
    void dispatch(Kernel shader, unsigned groups);
    void mark(unsigned index, bool enabled);
    void uploadData(Buffer& dst, size_t offset, const void* src, size_t bytes, D3D12_RESOURCE_STATES next);
    void initializeBuffers(std::array<DisplayTransfer, slots>& displays);
    void initializePipeline();
    void initializeTiming();
    void initializeGeometry();
    void uploadTerrain(const StormTick& tick, const unsigned char* rows);
    void buildBoxes(Buffer& blas, const SpatialParticle* boxes, unsigned count);
    void buildAcceleration(Buffer& target, D3D12_BUILD_RAYTRACING_ACCELERATION_STRUCTURE_INPUTS input);
    void dispatchControls(const StormTick& tick, const unsigned char* commands);

public:
    Gpu(const Adapter& adapter, unsigned capacity, unsigned probes, std::array<DisplayTransfer, slots>& displays, bool shared);
    void step(const StormTick& tick, const unsigned char* payload, std::shared_ptr<const std::vector<SpatialParticle>> geometry,
            const DomeUpload* dome, DisplayTransfer* display, bool timed, TickResult& result);
    void copyDisplay(DisplayTransfer& display, const StormTick& tick, bool timed);
};
}
