#include "StormDxrGpu.h"
#include "StormInput.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <immintrin.h>
#include <stdexcept>

namespace stormDxr {
using namespace stormInput;

namespace {
constexpr unsigned threadCount = 128;
constexpr size_t featherBytes = sizeof(StormFeatherReceipt);
constexpr unsigned descriptorSrvs = 10, descriptorUavs = 17;
constexpr size_t batchOffset = 32;
constexpr size_t featherOffset = batchOffset + sizeof(StormDomeBatch);
constexpr size_t timingOffset = featherOffset + featherBytes;
constexpr size_t headerOffset = timingOffset + timingMarks * sizeof(uint64_t);
constexpr size_t readbackBytes = headerOffset + sizeof(StormDisplayHeader);
constexpr size_t maxControls = CMI_RESOURCE_STORM_MAX_CONTROLS * sizeof(StormControl);
constexpr size_t maxRows = (CMI_RESOURCE_STORM_TERRAIN_MAX_ROWS + CMI_RESOURCE_STORM_TERRAIN_MAX_SECTIONS) * sizeof(int4);
constexpr size_t maxCells = CMI_RESOURCE_STORM_TERRAIN_MAX_PAGES * CMI_RESOURCE_STORM_TERRAIN_PAGE_BYTES;
// HLSL's structured buffers have natural scalar alignment. External
// buffers instead use the shared ABI offsets and are checked by fixtures.
constexpr std::array<unsigned, descriptorUavs> strides{184,128,112,320,1408,368,432,152,0,32,0,4,0,0,0,16,4};
constexpr std::array<unsigned, 10> inputStrides{sizeof(StormTick),sizeof(StormControl),16,16,4,4,4,4,0,4};

unsigned groups(unsigned rows) { return (rows + threadCount - 1) / threadCount; }
double nanos(std::chrono::steady_clock::duration duration) {
    return std::chrono::duration<double, std::nano>(duration).count();
}

std::pair<const void*, size_t> shader(unsigned index) {
    HMODULE module{};
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(&shader), &module))
        throw std::runtime_error("DXR Storm module handle unavailable");
    auto resource = FindResourceW(module, MAKEINTRESOURCEW(101 + index), MAKEINTRESOURCEW(10));
    if (!resource) throw std::runtime_error("Embedded DXIL Storm shader is missing");
    auto memory = LoadResource(module, resource);
    if (!memory) throw std::runtime_error("Cannot load embedded Storm shader");
    return {LockResource(memory), SizeofResource(module, resource)};
}
}

Queue::~Queue() {
    if (event) CloseHandle(event);
}

void Queue::initialize(ID3D12Device5* owner, D3D12_COMMAND_LIST_TYPE type) {
    device = owner;
    D3D12_COMMAND_QUEUE_DESC description{type};
    check(owner->CreateCommandQueue(&description, IID_PPV_ARGS(&queue)), "Creating Storm command queue");
    check(owner->CreateCommandAllocator(type, IID_PPV_ARGS(&allocator)), "Creating Storm command allocator");
    check(owner->CreateCommandList(0, type, allocator.Get(), nullptr, IID_PPV_ARGS(&list)), "Creating Storm command list");
    check(list->Close(), "Closing initial Storm command list");
    check(owner->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence)), "Creating Storm queue fence");
    event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!event) throw std::runtime_error("Creating Storm fence event failed");
}

void Queue::begin() {
    check(allocator->Reset(), "Resetting Storm command allocator");
    check(list->Reset(allocator.Get(), nullptr), "Resetting Storm command list");
}

void Queue::finish(ID3D12Fence* published, uint64_t value) {
    check(list->Close(), "Closing Storm command list");
    ID3D12CommandList* const lists[]{list.Get()};
    queue->ExecuteCommandLists(1, lists);
    auto completion = published ? published : fence.Get();
    const auto generation = published ? value : ++sequence;
    check(queue->Signal(completion, generation), "Signaling Storm queue fence");
    if (completion->GetCompletedValue() < generation) {
        check(completion->SetEventOnCompletion(generation, event), "Registering Storm queue fence");
        if (WaitForSingleObject(event, INFINITE) != WAIT_OBJECT_0) throw std::runtime_error("Storm queue fence wait failed");
    }
    if (completion->GetCompletedValue() == UINT64_MAX)
        check(device->GetDeviceRemovedReason(), "Storm GPU completion");
}

Buffer Gpu::buffer(size_t bytes, D3D12_HEAP_TYPE type, D3D12_RESOURCE_STATES state, D3D12_RESOURCE_FLAGS flags,
        D3D12_HEAP_FLAGS heapFlags) {
    Buffer result;
    D3D12_HEAP_PROPERTIES properties{type, D3D12_CPU_PAGE_PROPERTY_UNKNOWN, D3D12_MEMORY_POOL_UNKNOWN, 1, 1};
    D3D12_RESOURCE_DESC desc{D3D12_RESOURCE_DIMENSION_BUFFER, 0, bytes, 1, 1, 1,
        DXGI_FORMAT_UNKNOWN, {1,0}, D3D12_TEXTURE_LAYOUT_ROW_MAJOR, flags};
    check(device->CreateCommittedResource(&properties, heapFlags, &desc, state, nullptr,
            IID_PPV_ARGS(&result.resource)), "Allocating Storm GPU buffer");
    result.size = bytes;
    result.state = state;
    if (type == D3D12_HEAP_TYPE_UPLOAD || type == D3D12_HEAP_TYPE_READBACK) {
        D3D12_RANGE range{0, type == D3D12_HEAP_TYPE_READBACK ? bytes : 0};
        check(result.resource->Map(0, &range, reinterpret_cast<void**>(&result.mapping)), "Mapping Storm transfer buffer");
    }
    return result;
}

Buffer Gpu::uav(size_t bytes) {
    return buffer(bytes, D3D12_HEAP_TYPE_DEFAULT, D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS);
}

void Gpu::allocate(Buffer& target, size_t bytes, D3D12_RESOURCE_STATES state) {
    if (target.size < bytes) {
        if (target.resource) retiredBuffers.push_back(std::move(target));
        target = buffer(bytes, D3D12_HEAP_TYPE_DEFAULT, state, D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS);
    }
}

void Gpu::transition(Buffer& resource, D3D12_RESOURCE_STATES state) {
    if (resource.state == state) return;
    D3D12_RESOURCE_BARRIER entry{};
    entry.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    entry.Transition = {resource.resource.Get(), D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES, resource.state, state};
    compute.list->ResourceBarrier(1, &entry);
    resource.state = state;
}

void Gpu::barrier() {
    D3D12_RESOURCE_BARRIER entry{};
    entry.Type = D3D12_RESOURCE_BARRIER_TYPE_UAV;
    compute.list->ResourceBarrier(1, &entry);
}

D3D12_CPU_DESCRIPTOR_HANDLE Gpu::descriptor(unsigned index) const {
    auto ptr = heap->GetCPUDescriptorHandleForHeapStart();
    ptr.ptr += SIZE_T(index) * descriptorSize;
    return ptr;
}

void Gpu::srv(unsigned index, const Buffer& source, unsigned stride) {
    D3D12_SHADER_RESOURCE_VIEW_DESC view{};
    view.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
    view.ViewDimension = D3D12_SRV_DIMENSION_BUFFER;
    view.Buffer.NumElements = UINT(source.size / stride);
    view.Buffer.StructureByteStride = stride;
    device->CreateShaderResourceView(source.resource.Get(), &view, descriptor(index));
}

void Gpu::uavView(unsigned index, const Buffer& source, unsigned stride) {
    D3D12_UNORDERED_ACCESS_VIEW_DESC view{};
    view.ViewDimension = D3D12_UAV_DIMENSION_BUFFER;
    view.Buffer.NumElements = UINT(source.size / (stride ? stride : 4u));
    view.Buffer.StructureByteStride = stride;
    if (!stride) {
        view.Format = DXGI_FORMAT_R32_TYPELESS;
        view.Buffer.Flags = D3D12_BUFFER_UAV_FLAG_RAW;
    }
    device->CreateUnorderedAccessView(source.resource.Get(), nullptr, &view, descriptor(descriptorSrvs + index));
}

void Gpu::setAccelerationView() {
    D3D12_SHADER_RESOURCE_VIEW_DESC view{};
    view.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
    view.ViewDimension = D3D12_SRV_DIMENSION_RAYTRACING_ACCELERATION_STRUCTURE;
    view.RaytracingAccelerationStructure.Location = tlas.resource ? tlas.address() : 0;
    device->CreateShaderResourceView(nullptr, &view, descriptor(8));
}

void Gpu::bind(DisplayTransfer* display) {
    for (unsigned index = 0; index < inputStrides.size(); ++index)
        if(inputStrides[index])srv(index, inputs[index], inputStrides[index]);
    for (unsigned index = 0; index < strides.size(); ++index)
        uavView(index, index == 12 ? (display ? display->snapshot : dummySnapshot) : storage[index], strides[index]);
    setAccelerationView();
    ID3D12DescriptorHeap* heaps[]{heap.Get()};
    compute.list->SetDescriptorHeaps(1, heaps);
    compute.list->SetComputeRootSignature(root.Get());
    compute.list->SetComputeRootDescriptorTable(1, heap->GetGPUDescriptorHandleForHeapStart());
}

void Gpu::dispatch(Kernel shader, unsigned count) {
    if (!count) return;
    compute.list->SetPipelineState(programs[shader].Get());
    compute.list->SetComputeRoot32BitConstants(0, sizeof(args)/4, &args, 0);
    compute.list->Dispatch(count, 1, 1);
    barrier();
}

void Gpu::mark(unsigned index, bool enabled) {
    if (enabled) compute.list->EndQuery(queries.Get(), D3D12_QUERY_TYPE_TIMESTAMP, index);
}

void Gpu::uploadData(Buffer& dst, size_t offset, const void* source, size_t bytes, D3D12_RESOURCE_STATES next) {
    if (!bytes) return;
    if (uploadOffset + bytes > upload.size || offset + bytes > dst.size)
        throw std::runtime_error("DXR Storm transfer exceeds buffer capacity");
    std::memcpy(upload.mapping + uploadOffset, source, bytes);
    transition(dst, D3D12_RESOURCE_STATE_COPY_DEST);
    compute.list->CopyBufferRegion(dst.resource.Get(), offset, upload.resource.Get(), uploadOffset, bytes);
    transition(dst, next);
    uploadOffset += bytes;
}

void Gpu::initializePipeline() {
    D3D12_DESCRIPTOR_RANGE ranges[]{
        {D3D12_DESCRIPTOR_RANGE_TYPE_SRV, descriptorSrvs, 0, 0, 0},
        {D3D12_DESCRIPTOR_RANGE_TYPE_UAV, descriptorUavs, 0, 0, descriptorSrvs}
    };
    D3D12_ROOT_PARAMETER params[2]{};
    params[0].ParameterType = D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;
    params[0].Constants = {0, 0, sizeof(args)/4};
    params[1].ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
    params[1].DescriptorTable = {2, ranges};
    D3D12_ROOT_SIGNATURE_DESC description{};
    description.NumParameters = 2;
    description.pParameters = params;
    ComPtr<ID3DBlob> binary, errors;
    check(D3D12SerializeRootSignature(&description, D3D_ROOT_SIGNATURE_VERSION_1, &binary, &errors),
            "Serializing Storm root signature");
    check(device->CreateRootSignature(0, binary->GetBufferPointer(), binary->GetBufferSize(), IID_PPV_ARGS(&root)),
            "Creating Storm root signature");
    for (unsigned index = 0; index < KernelCount; ++index) {
        auto [data, bytes] = shader(index);
        D3D12_COMPUTE_PIPELINE_STATE_DESC state{};
        state.pRootSignature = root.Get();
        state.CS = {data, bytes};
        check(device->CreateComputePipelineState(&state, IID_PPV_ARGS(&programs[index])), "Creating Storm DXIL compute pipeline");
    }
    D3D12_DESCRIPTOR_HEAP_DESC descriptors{D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV, descriptorSrvs + descriptorUavs,
        D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE, 0};
    check(device->CreateDescriptorHeap(&descriptors, IID_PPV_ARGS(&heap)), "Allocating Storm descriptors");
    descriptorSize = device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
}

void Gpu::initializeTiming() {
    if (queries) return;
    check(compute.queue->GetTimestampFrequency(&frequency), "Reading Storm timestamp frequency");
    D3D12_QUERY_HEAP_DESC timing{D3D12_QUERY_HEAP_TYPE_TIMESTAMP, timingMarks, 0};
    check(device->CreateQueryHeap(&timing, IID_PPV_ARGS(&queries)), "Allocating Storm timestamps");
}

void Gpu::initializeBuffers(std::array<DisplayTransfer, slots>& displays) {
    const size_t displayBytes = sizeof(StormDisplayHeader)+args.capacity*sizeof(StormDisplayRecord)+columnSlots*columnBytes;
    const std::array<size_t, descriptorUavs> bytes{
        args.capacity*strides[0],args.capacity*strides[1],args.capacity*strides[2],domeSlots*strides[3],
        domeSlots*strides[4],(columnSlots+CMI_RESOURCE_STORM_RING_SLOTS)*strides[5],columnSlots*columnMembers*strides[6],
        columnSlots*columnMembers*strides[7],sizeof(StormDomeBatch),featherImpactLimit*strides[9],featherBytes,
        8,0,32,sizeof(D3D12_RAYTRACING_INSTANCE_DESC)*(args.capacity+1),args.capacity*strides[15],domeSlots*domeFlows*4
    };
    const std::array<size_t, 10> inputBytes{sizeof(StormTick),maxControls,playerBytes(CMI_RESOURCE_STORM_PLAYER_LIMIT),
        maxRows,maxCells,4,4,4,0,16};
    for (unsigned index = 0; index < inputBytes.size(); ++index)
        if(inputBytes[index])inputs[index] = buffer(inputBytes[index], D3D12_HEAP_TYPE_DEFAULT, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
    for (unsigned index = 0; index < bytes.size(); ++index)
        if (bytes[index]) storage[index] = uav(bytes[index]);
    results = buffer(readbackBytes, D3D12_HEAP_TYPE_READBACK, D3D12_RESOURCE_STATE_COPY_DEST);
    dummySnapshot = uav(sizeof(StormDisplayHeader));
    for (auto& display : displays) {
        if (sharedDisplay) {
            display.snapshot = buffer(displayBytes, D3D12_HEAP_TYPE_DEFAULT, D3D12_RESOURCE_STATE_COMMON,
                    D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS, D3D12_HEAP_FLAG_SHARED);
            check(device->CreateFence(0, D3D12_FENCE_FLAG_SHARED, IID_PPV_ARGS(&display.sharedFence)),
                    "Creating shared Storm display fence");
        } else {
            display.snapshot = uav(displayBytes);
            display.readback = buffer(displayBytes, D3D12_HEAP_TYPE_READBACK, D3D12_RESOURCE_STATE_COPY_DEST);
        }
    }
    auto zero = buffer(*std::max_element(bytes.begin(), bytes.end()), D3D12_HEAP_TYPE_UPLOAD, D3D12_RESOURCE_STATE_GENERIC_READ);
    std::memset(zero.mapping, 0, zero.size);
    compute.begin();
    for (unsigned index : {0u,1u,2u,3u,4u,5u,6u,7u,8u}) {
        auto& dst = storage[index];
        transition(dst, D3D12_RESOURCE_STATE_COPY_DEST);
        compute.list->CopyBufferRegion(dst.resource.Get(), 0, zero.resource.Get(), 0, dst.size);
        transition(dst, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
    }
    compute.finish();
}

void Gpu::buildAcceleration(Buffer& target, D3D12_BUILD_RAYTRACING_ACCELERATION_STRUCTURE_INPUTS input) {
    D3D12_RAYTRACING_ACCELERATION_STRUCTURE_PREBUILD_INFO info{};
    device->GetRaytracingAccelerationStructurePrebuildInfo(&input, &info);
    if (!info.ResultDataMaxSizeInBytes || !info.ScratchDataSizeInBytes)
        throw std::runtime_error("DXR Storm acceleration size unavailable");
    allocate(accelerationScratch, info.ScratchDataSizeInBytes, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
    allocate(target, info.ResultDataMaxSizeInBytes, D3D12_RESOURCE_STATE_RAYTRACING_ACCELERATION_STRUCTURE);
    D3D12_BUILD_RAYTRACING_ACCELERATION_STRUCTURE_DESC build{};
    build.Inputs = input;
    build.DestAccelerationStructureData = target.address();
    build.ScratchAccelerationStructureData = accelerationScratch.address();
    compute.list->BuildRaytracingAccelerationStructure(&build, 0, nullptr);
    barrier();
}

void Gpu::buildBoxes(Buffer& blas, const SpatialParticle* boxes, unsigned count) {
    constexpr int corners[][3]{{-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}};
    constexpr unsigned faces[]{0,2,1,0,3,2,4,5,6,4,6,7,0,4,7,0,7,3,1,2,6,1,6,5,0,1,5,0,5,4,3,7,6,3,6,2};
    auto vertices = reinterpret_cast<float*>(upload.mapping + uploadOffset);
    size_t vertexBytes = size_t(count)*24*sizeof(float);
    size_t indexBytes = size_t(count)*36*sizeof(uint32_t);
    if (uploadOffset + vertexBytes + indexBytes > upload.size)
        throw std::runtime_error("DXR Storm geometry exceeds upload buffer");
    auto indices = reinterpret_cast<uint32_t*>(upload.mapping + uploadOffset + vertexBytes);
    for (unsigned i=0; i<count; ++i) {
        auto& box = boxes[i];
        for (unsigned corner=0; corner<8; ++corner) {
            const auto& scale = corners[corner];
            *vertices++ = box.position.x+scale[0]*box.velocity.x;
            *vertices++ = box.position.y+scale[1]*box.velocity.y;
            *vertices++ = box.position.z+scale[2]*box.velocity.z;
        }
        for (unsigned face : faces) *indices++ = i*8+face;
    }
    D3D12_RAYTRACING_GEOMETRY_DESC geometry{};
    geometry.Type = D3D12_RAYTRACING_GEOMETRY_TYPE_TRIANGLES;
    geometry.Flags = D3D12_RAYTRACING_GEOMETRY_FLAG_OPAQUE;
    geometry.Triangles.IndexFormat = DXGI_FORMAT_R32_UINT;
    geometry.Triangles.VertexFormat = DXGI_FORMAT_R32G32B32_FLOAT;
    geometry.Triangles.IndexCount = count*36;
    geometry.Triangles.VertexCount = count*8;
    geometry.Triangles.IndexBuffer = upload.address()+uploadOffset+vertexBytes;
    geometry.Triangles.VertexBuffer = {upload.address()+uploadOffset, 12};
    uploadOffset += vertexBytes + indexBytes;
    D3D12_BUILD_RAYTRACING_ACCELERATION_STRUCTURE_INPUTS input{};
    input.Type = D3D12_RAYTRACING_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL;
    input.Flags = D3D12_RAYTRACING_ACCELERATION_STRUCTURE_BUILD_FLAG_PREFER_FAST_TRACE;
    input.NumDescs = 1;
    input.pGeometryDescs = &geometry;
    buildAcceleration(blas, input);
}

Gpu::Gpu(const Adapter& adapter, unsigned capacity, unsigned probes, std::array<DisplayTransfer, slots>& displays, bool shared)
        : device(stormDxr::device(adapter)), sharedDisplay(shared), commandMarks(capacity) {
    args.capacity = capacity;
    args.probes = probes;
    compute.initialize(device.Get(), D3D12_COMMAND_LIST_TYPE_DIRECT);
    if (!sharedDisplay) transfer.initialize(device.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
    initializePipeline();
    initializeBuffers(displays);
    initializeGeometry();
}

void Gpu::initializeGeometry() {
    upload = buffer(1024, D3D12_HEAP_TYPE_UPLOAD, D3D12_RESOURCE_STATE_GENERIC_READ);
    SpatialParticle unit{};
    unit.velocity = {1.f,1.f,1.f,0.f};
    compute.begin();
    buildBoxes(unitBlas, &unit, 1);
    compute.finish();
    args.unitBlas = unitBlas.address();
}

void Gpu::uploadTerrain(const StormTick& tick, const unsigned char* rows) {
    auto pages = rows + rowBytes(tick.terrainRows);
    uploadData(inputs[3], 0, terrain.index.data(), terrain.index.size()*sizeof(int4), D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
    auto& patches = terrain.uploads;
    std::sort(patches.begin(), patches.end());
    transition(inputs[4], D3D12_RESOURCE_STATE_COPY_DEST);
    for (auto patch : patches)
        uploadData(inputs[4], cellBytes(patch.first), pages+cellBytes(patch.second), cellBytes(1), D3D12_RESOURCE_STATE_COPY_DEST);
    transition(inputs[4], D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
    args.terrainMask = terrain.primary-1;
    args.terrainOverflow = terrain.overflow;
    ++args.terrainRevision;
}

void Gpu::dispatchControls(const StormTick& tick, const unsigned char* commands) {
    auto read=[commands](unsigned index) {
        StormControl row;
        std::memcpy(&row, commands+commandBytes(index), sizeof(row));
        return row;
    };
    for (unsigned first=0; first<tick.controls;) {
        auto op=read(first);
        unsigned end=first+1;
        if (op.identity!=tick.identity||op.op==CMI_RESOURCE_STORM_CONTROL_LIFECYCLE) { first=end; continue; }
        if (memberOnly(op)) {
            if (++commandGroup==0) {
                std::fill(commandMarks.begin(),commandMarks.end(),0);
                commandGroup=1;
            }
            commandMarks[op.member]=commandGroup;
            for (;end<tick.controls;++end) {
                auto next=read(end);
                if (next.identity!=tick.identity||next.op==CMI_RESOURCE_STORM_CONTROL_LIFECYCLE
                        ||!memberOnly(next)||commandMarks[next.member]==commandGroup) break;
                commandMarks[next.member]=commandGroup;
            }
        } else if (payloadPart(op)) {
            std::array<uint64_t, 4> mask{};
            mask[op.member/64]=1ull<<(op.member&63u);
            for (;end<tick.controls;++end) {
                auto next=read(end);
                if (next.identity!=tick.identity||next.op!=op.op||next.scope!=op.scope) break;
                auto bit=1ull<<(next.member&63u);
                auto& seen=mask[next.member/64];
                if (seen&bit) break;
                seen|=bit;
            }
        }
        args.controlFirst=first;
        args.controlCount=end-first;
        args.controlMode=args.controlCount>1;
        dispatch(Controls, groups(args.controlMode ? args.controlCount : op.span));
        first=end;
    }
}

void Gpu::step(const StormTick& tick, const unsigned char* payload,
        std::shared_ptr<const std::vector<SpatialParticle>> geometry, const DomeUpload* dome,
        DisplayTransfer* display, bool timed, TickResult& result) {
    if (timed) initializeTiming();
    const auto start = timed ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
    const auto rows = payload + commandBytes(tick.controls);
    const auto players = rows + rowBytes(tick.terrainRows) + cellBytes(tick.terrainPages);
    const bool changedTerrain = terrain.apply(tick, rows);
    const auto terrainDone = timed ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
    std::vector<SpatialParticle> derivedGeometry;
    const std::vector<SpatialParticle>* boxes = nullptr;
    if (geometry != selectedGeometry || changedTerrain && !geometry) {
        if (geometry) boxes = geometry.get();
        else {
            derivedGeometry = terrain.geometryRows();
            boxes = &derivedGeometry;
        }
        geometryCount = unsigned(boxes->size());
        selectedGeometry = std::move(geometry);
    }

    bool needsBatch = tick.positionSnapshot || tick.columnCaptureCount;
    for (unsigned i=0; i<tick.controls; ++i) {
        StormControl command;
        std::memcpy(&command,payload+commandBytes(i),sizeof(command));
        if (command.identity != tick.identity) continue;
        regional |= regionalCommand(command);
        needsBatch |= outerCommand(command);
    }
    // An outer flight can finish on a later tick with an empty control stream.
    outerFeedback |= needsBatch;
    result.hasBatch = regional || outerFeedback;
    args.display = display != nullptr;
    args.feathers = tick.featherImpacts && tick.population && (tick.active & 1u);
    args.regional = regional;
    args.terrainBoxes = geometryCount;
    auto terrainBytes = changedTerrain ? terrain.index.size()*sizeof(int4) + cellBytes(unsigned(terrain.uploads.size())) : 0;
    auto capacity = sizeof(tick)+commandBytes(tick.controls)+playerBytes(tick.playerCount)+terrainBytes;
    const auto editData=players+playerBytes(tick.playerCount);
    if(tick.terrainEditBytes){
        uint32_t primary{};std::memcpy(&primary,editData,sizeof(primary));
        args.terrainEdited=primary!=0;
        ++args.terrainRevision;
        if(args.terrainEdited){
            capacity+=tick.terrainEditBytes;
            if(tick.terrainEditBytes>inputs[9].size)
                inputs[9]=buffer((size_t(tick.terrainEditBytes)+4095)&~size_t(4095),D3D12_HEAP_TYPE_DEFAULT,
                        D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        }
    }
    if (boxes) capacity += boxes->size()*(24*sizeof(float)+36*sizeof(uint32_t));
    if (dome) {
        const size_t sizes[]{dome->records.size()*4,dome->keys.size()*4,dome->flows.size()*4};
        for (unsigned index=0; index<3; ++index) {
            capacity += sizes[index];
            inputs[5+index] = buffer(sizes[index],D3D12_HEAP_TYPE_DEFAULT,D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        }
        domeInstalled = true;
    }
    if (capacity > upload.size) upload = buffer((capacity+65535)&~size_t(65535),
            D3D12_HEAP_TYPE_UPLOAD,D3D12_RESOURCE_STATE_GENERIC_READ);

    uploadOffset = 0;
    compute.begin();
    bind(display);
    mark(0, timed);
    if (display) transition(display->snapshot, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
    constexpr auto srvState = D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE;
    uploadData(inputs[0],0,&tick,sizeof(tick),srvState);
    uploadData(inputs[1],0,payload,commandBytes(tick.controls),srvState);
    uploadData(inputs[2],0,players,playerBytes(tick.playerCount),srvState);
    if (changedTerrain) uploadTerrain(tick, rows);
    if(tick.terrainEditBytes&&args.terrainEdited)uploadData(inputs[9],0,editData,tick.terrainEditBytes,srvState);
    if (dome) {
        uploadData(inputs[5],0,dome->records.data(),dome->records.size()*4,srvState);
        uploadData(inputs[6],0,dome->keys.data(),dome->keys.size()*4,srvState);
        uploadData(inputs[7],0,dome->flows.data(),dome->flows.size()*4,srvState);
    }
    mark(8, timed);
    dispatchControls(tick, payload);
    dispatch(Prepare, regional ? domeSlots : 1);
    if (args.feathers) dispatch(ClearFeathers,groups(featherImpactLimit));
    mark(1, timed);

    if (boxes && !boxes->empty()) buildBoxes(terrainBlas,boxes->data(),geometryCount);
    args.terrainBlas = geometryCount ? terrainBlas.address() : 0;
    if ((tick.active&1u) && tick.population) {
        unsigned count = tick.population+(geometryCount ? 1u : 0u);
        dispatch(Instances, groups(count));
        mark(2, timed);
        transition(storage[14],srvState);
        D3D12_BUILD_RAYTRACING_ACCELERATION_STRUCTURE_INPUTS input{};
        input.Type = D3D12_RAYTRACING_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL;
        input.Flags = D3D12_RAYTRACING_ACCELERATION_STRUCTURE_BUILD_FLAG_PREFER_FAST_BUILD;
        input.NumDescs = count;
        input.InstanceDescs = storage[14].address();
        buildAcceleration(tlas,input);
        transition(storage[14],D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
        setAccelerationView();
        mark(3, timed);
        dispatch(MotionHints, groups(tick.population));
        mark(4, timed);
        dispatch(Move, groups(tick.population));
        if (tick.positionSnapshot) dispatch(Positions, groups(tick.population));
    } else {
        for (unsigned index=2; index<=4; ++index) mark(index,timed);
    }
    mark(5, timed);
    if (args.feathers) dispatch(CompactFeathers, groups(featherImpactLimit));
    if (tick.columnCaptureCount) dispatch(CaptureColumns,tick.columnCaptureCount);
    if (regional && domeInstalled && (tick.active & 2u)) dispatch(FinishDome,domeSlots);
    auto copy = [&](Buffer& source,size_t offset,size_t bytes) {
        transition(source,D3D12_RESOURCE_STATE_COPY_SOURCE);
        compute.list->CopyBufferRegion(results.resource.Get(),offset,source.resource.Get(),0,bytes);
        transition(source,D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
    };
    if (result.hasBatch) copy(storage[8],batchOffset,batchBytes(tick));
    if (args.feathers) copy(storage[10],featherOffset,featherBytes);
    mark(6,timed);

    if (display && (tick.active&1u)) dispatch(Publish,groups(tick.population));
    dispatch(Seal,1);
    copy(storage[13],0,32);
    mark(7,timed);
    if (display) {
        if (!sharedDisplay) {
            transition(display->snapshot,D3D12_RESOURCE_STATE_COPY_SOURCE);
            compute.list->CopyBufferRegion(results.resource.Get(),headerOffset,
                    display->snapshot.resource.Get(),0,sizeof(StormDisplayHeader));
        }
        transition(display->snapshot,D3D12_RESOURCE_STATE_COMMON);
    }
    mark(9,timed);
    if (timed) compute.list->ResolveQueryData(queries.Get(),D3D12_QUERY_TYPE_TIMESTAMP,0,timingMarks,
            results.resource.Get(),timingOffset);
    const auto encoded = timed ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
    compute.finish(display ? display->sharedFence.Get() : nullptr, tick.sequence * 2 - 1);
    retiredBuffers.clear();

    std::memcpy(&result.receipt,results.mapping,sizeof(result.receipt));
    if (result.receipt.sequence!=tick.sequence||result.receipt.tick!=tick.tick||result.receipt.identity!=tick.identity)
        throw std::runtime_error("DXR Storm receipt identity mismatch");
    if (result.hasBatch) std::memcpy(&result.batch,results.mapping+batchOffset,batchBytes(tick));
    result.feathers.clear();
    if (args.feathers) {
        unsigned count;
        std::memcpy(&count,results.mapping+featherOffset,sizeof(count));
        result.feathers.resize(std::min(count,featherImpactLimit));
        std::memcpy(result.feathers.data(),results.mapping+featherOffset+16,result.feathers.size()*sizeof(float4));
    }
    if (display && !sharedDisplay) {
        std::memcpy(&display->header,results.mapping+headerOffset,sizeof(display->header));
        display->headerNanos = 0;
    }
    if (timed) {
        uint64_t marks[timingMarks];
        std::memcpy(marks,results.mapping+timingOffset,sizeof(marks));
        for (unsigned index=0; index<7; ++index) result.timing[index]=(marks[index+1]-marks[index])*1000.0/frequency;
        result.timing[7]=result.receipt.displayRecords;
        result.timing[8]=nanos(terrainDone-start)*1e-6;
        result.timing[9]=nanos(encoded-terrainDone)*1e-6;
        result.timing[10]=(marks[8]-marks[0])*1000.0/frequency;
        result.timing[11]=double(terrainBytes);
        result.timing[12]=geometryCount;
        if (display && !sharedDisplay) display->headerNanos=(marks[9]-marks[7])*1e9/frequency;
    }
}

void Gpu::copyDisplay(DisplayTransfer& display, const StormTick& tick, bool timed) {
    const auto& header=display.header;
    if (header.magic != CMI_RESOURCE_STORM_DISPLAY_MAGIC || header.abi != CMI_RESOURCE_ABI
            || header.ready != 1 || header.capacity != args.capacity || header.count > args.capacity
            || header.sequence != tick.sequence || header.tick != tick.tick || header.identity != tick.identity)
        throw std::runtime_error("Invalid DXR Storm display header");
    const size_t columnBase = sizeof(StormDisplayHeader)+args.capacity*sizeof(StormDisplayRecord);
    const size_t memberBytes = size_t(header.count)*sizeof(StormDisplayRecord);
    uint64_t columns = uint64_t(header.columnMask[0])|(uint64_t(header.columnMask[1])<<32u);
    std::array<std::pair<size_t,size_t>,columnSlots/2+1> ranges{};
    unsigned count=0;
    if (memberBytes) ranges[count++]={sizeof(header),memberBytes};
    for (unsigned column=0; column<columnSlots;) {
        if (!(columns&(uint64_t(1)<<column))) { ++column;continue; }
        unsigned first=column++;
        while(column<columnSlots&&(columns&(uint64_t(1)<<column))) ++column;
        ranges[count++]={columnBase+first*columnBytes,(column-first)*columnBytes};
    }
    const auto start=timed ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
    if (count) {
        transfer.begin();
        for (unsigned index=0;index<count;++index) {
            auto [offset,bytes]=ranges[index];
            transfer.list->CopyBufferRegion(display.readback.resource.Get(),offset,display.snapshot.resource.Get(),offset,bytes);
        }
        transfer.finish();
    }
    display.copyNanos = timed ? static_cast<int64_t>(display.headerNanos+nanos(std::chrono::steady_clock::now()-start)) : 0;
    std::memcpy(display.mapping,&header,sizeof(header));
    for (unsigned index=0;index<count;++index) {
        auto [offset,bytes]=ranges[index];
        std::memcpy(display.mapping+offset,display.readback.mapping+offset,bytes);
    }
    _mm_sfence(); // The persistent OpenGL upload mapping may be write-combined.
}
}
