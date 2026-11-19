#include <windows.h>
#include <TraceLoggingProvider.h>
#include <d3d12.h>
#include <d3dcompiler.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <jni.h>
#include <xell/xell_d3d12.h>
#include <xess_fg/xefg_swapchain_d3d12.h>
#include <array>
#include <algorithm>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <memory>
#include <limits>
#include <stdexcept>
#include <mutex>
#include <optional>
#include <thread>
#include <exception>

namespace {
using Microsoft::WRL::ComPtr;
constexpr unsigned SlotCount = 4;
constexpr unsigned InputCount = 4;
constexpr unsigned BackBufferCount = 3;
constexpr UINT64 DrainValue = UINT64_MAX - 1;
TRACELOGGING_DEFINE_PROVIDER(presentTrace, "CreateManaIndustry.XeSSFG",
    (0x2dd7d232, 0xe6b6, 0x45d9, 0x9c, 0x9c, 0xb6, 0x1a, 0xe7, 0x67, 0x1b, 0xc1));
enum Group { Geometry, Color, GroupCount };
enum Plane { Final, Hudless, Depth, Motion, PlaneCount };
enum Transport { Rgba8, Rgb24, Ycbcr422 };
using Reprojection = std::array<float, 34>;
constexpr std::array<DXGI_FORMAT, PlaneCount> Formats{
    DXGI_FORMAT_R8G8B8A8_UNORM, DXGI_FORMAT_R8G8B8A8_UNORM, DXGI_FORMAT_R32_FLOAT, DXGI_FORMAT_R16G16_FLOAT
};

constexpr Plane firstPlane(Group group) { return group == Geometry ? Depth : Final; }
constexpr Group groupOf(Plane plane) { return plane >= Depth ? Geometry : Color; }
constexpr UINT64 transferToken(UINT64 sequence, Group group) { return sequence * GroupCount + group; }
constexpr unsigned timestampIndex(unsigned slot, Group group) { return slot * GroupCount + group; }

constexpr UINT64 alignUp(UINT64 bytes, UINT64 alignment) {
    return (bytes + alignment - 1) & ~(alignment - 1);
}


void check(HRESULT result, const char* operation) {
    if (SUCCEEDED(result)) return;
    char message[192];
    std::snprintf(message, sizeof(message), "%s (HRESULT 0x%08lx)", operation, static_cast<unsigned long>(result));
    throw std::runtime_error(message);
}

template<class Result>
void sdk(Result result, const char* operation) {
    if (result >= 0) return;
    char message[192];
    std::snprintf(message, sizeof(message), "%s (Intel SDK status %d)", operation, static_cast<int>(result));
    throw std::runtime_error(message);
}

void checkXeLL(xell_result_t result, const char* operation, uint32_t frame) {
    if (result >= 0) return;
    char message[192];
    std::snprintf(message, sizeof(message), "%s failed for frame %u (Intel SDK status %d)",
                  operation, frame, static_cast<int>(result));
    throw std::runtime_error(message);
}

void recordMarker(xell_context_handle_t context, uint32_t frame, int marker) {
    constexpr std::array operations{
        "XeLL simulation start marker", "XeLL simulation end marker",
        "XeLL render submit start marker", "XeLL render submit end marker",
        "XeLL present start marker", "XeLL present end marker", "XeLL input sample marker"
    };
    if (marker < 0 || marker >= XELL_MARKER_COUNT) throw std::invalid_argument("Invalid XeLL marker type");
    checkXeLL(xellAddMarkerData(context, frame, static_cast<xell_latency_marker_type_t>(marker)),
              operations.at(marker), frame);
}

struct Handle {
    HANDLE value = nullptr;
    Handle() = default;
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    ~Handle() { if (value) CloseHandle(value); }
    jlong detach() { auto previous = value; value = nullptr; return reinterpret_cast<jlong>(previous); }
};

LUID luid(jlong value) {
    auto id = static_cast<uint64_t>(value);
    return {static_cast<DWORD>(id), static_cast<LONG>(id >> 32)};
}

ComPtr<ID3D12Device> device(IDXGIFactory4* factory, jlong id, DXGI_ADAPTER_DESC1* description = nullptr) {
    ComPtr<IDXGIAdapter1> adapter;
    check(factory->EnumAdapterByLuid(luid(id), IID_PPV_ARGS(&adapter)), "Finding DXGI adapter by LUID");
    if (description) check(adapter->GetDesc1(description), "Reading DXGI adapter descriptor");
    ComPtr<ID3D12Device> result;
    check(D3D12CreateDevice(adapter.Get(), D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&result)), "Creating D3D12 device");
    if (result->GetNodeCount() != 1) throw std::runtime_error("XeSS-FG interop requires a single node per device");
    return result;
}

D3D12_HEAP_PROPERTIES defaultMemory() {
    D3D12_HEAP_PROPERTIES properties{};
    properties.Type = D3D12_HEAP_TYPE_DEFAULT;
    properties.CreationNodeMask = properties.VisibleNodeMask = 1;
    return properties;
}

D3D12_RESOURCE_DESC buffer(UINT64 bytes) {
    D3D12_RESOURCE_DESC desc{};
    desc.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
    desc.Width = bytes;
    desc.Height = desc.DepthOrArraySize = desc.MipLevels = desc.SampleDesc.Count = 1;
    desc.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
    return desc;
}

D3D12_RESOURCE_DESC texture(DXGI_FORMAT format, UINT width, UINT height) {
    D3D12_RESOURCE_DESC desc{};
    desc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    desc.Width = width;
    desc.Height = height;
    desc.DepthOrArraySize = desc.MipLevels = desc.SampleDesc.Count = 1;
    desc.Format = format;
    return desc;
}

void transition(ID3D12GraphicsCommandList* commands, ID3D12Resource* resource,
                D3D12_RESOURCE_STATES from, D3D12_RESOURCE_STATES to) {
    D3D12_RESOURCE_BARRIER barrier{};
    barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    barrier.Transition = {resource, D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES, from, to};
    commands->ResourceBarrier(1, &barrier);
}

struct Commands {
    ComPtr<ID3D12CommandAllocator> allocator;
    ComPtr<ID3D12GraphicsCommandList> list;

    void create(ID3D12Device* gpu, D3D12_COMMAND_LIST_TYPE type) {
        check(gpu->CreateCommandAllocator(type, IID_PPV_ARGS(&allocator)), "Creating a XeSS-FG command allocator");
        check(gpu->CreateCommandList(0, type, allocator.Get(), nullptr, IID_PPV_ARGS(&list)),
              "Creating a XeSS-FG command list");
        check(list->Close(), "Closing initial XeSS-FG commands");
    }

    ID3D12GraphicsCommandList* begin() {
        check(allocator->Reset(), "Recycling XeSS-FG command memory");
        check(list->Reset(allocator.Get(), nullptr), "Resetting XeSS-FG commands");
        return list.Get();
    }

    void submit(ID3D12CommandQueue* queue) {
        check(list->Close(), "Closing XeSS-FG commands");
        ID3D12CommandList* submitted = list.Get();
        queue->ExecuteCommandLists(1, &submitted);
    }
};

ComPtr<ID3D12CommandQueue> queue(ID3D12Device* gpu, D3D12_COMMAND_LIST_TYPE type) {
    D3D12_COMMAND_QUEUE_DESC desc{};
    desc.Type = type;
    ComPtr<ID3D12CommandQueue> result;
    check(gpu->CreateCommandQueue(&desc, IID_PPV_ARGS(&result)), "Creating a XeSS-FG command queue");
    return result;
}

struct Transfer {
    ComPtr<ID3D12Resource> glBuffer;
    ComPtr<ID3D12Heap> sourceHeap, destinationHeap;
    ComPtr<ID3D12Resource> source, destination, local;
    Commands producer, upload;
};

struct Slot {
    std::array<Transfer, GroupCount> groups;
    Commands prepare;
    UINT64 reuseAfter = 0;
    UINT64 profileSequence = 0;
    int64_t captureFrame = 0, profileFrame = 0;
    int64_t colorSubmitQpc = 0;
    UINT64 glFenceAtSubmit = 0;
    UINT64 geometryBytes = 0;
    std::optional<Reprojection> reprojection;
    unsigned recordedQueries = 0, pendingQueries = 0;
    bool geometrySubmitted = false, colorSubmitted = false, fullColor = false;
};

struct PreparedInput {
    std::array<ComPtr<ID3D12Resource>, PlaneCount> planes;
    Commands present;
    D3D12_GPU_DESCRIPTOR_HANDLE colorUavs{};
    UINT64 preparedAfter = 0; // Producer thread owns the texture reuse sequence.
    UINT64 submittedAfter = 0; // Presenter thread owns the command allocator reuse sequence.
};

constexpr unsigned ProducerGeometry = 1, ProducerColor = 2, UploadGeometry = 4, UploadColor = 8, LocalTextures = 16,
                   ColorUnpack = 32;
constexpr unsigned GpuProfileFields = 17;
constexpr unsigned ClockProfileFields = 10;

constexpr int64_t Unavailable = std::numeric_limits<int64_t>::min();
enum ProfileField {
    InputWaitEnqueue, Toggle, ArcSubmit, BeforePresent, DxgiPresent, Retire, ReadStatus, CrossBytes,
    DxgiResult, SdkStatus, FrameGenResult, FgEnabled, CompletedBefore, CompletedAfter, GpuMask, ProfileSetup,
    BackBufferIndex, SyncInterval, PresentFlags, GeometryBytes, ColorBytes,
    PresentEnterQpc, PresentExitQpc, ProxySwapChainAddress, QpcFrequency,
    ColorSubmitQpc, ColorFenceToken, GlFenceAtSubmit, GlFenceAtEnter, GlFenceAtExit,
    TransferFenceAtEnter, TransferFenceAtExit, ArcColorFenceObservedQpc,
    PresentQueueDelay, WorkerPresentDuration, ProxyPresentCpuTime, PresenterThreadId, ProxyTopLevel, ProfileCount
};

int64_t qpc() {
    LARGE_INTEGER value{};
    QueryPerformanceCounter(&value);
    return value.QuadPart;
}

int64_t threadCpuTime() {
    FILETIME created{}, exited{}, kernel{}, user{};
    if (!GetThreadTimes(GetCurrentThread(), &created, &exited, &kernel, &user)) return Unavailable;
    auto ticks = [](FILETIME time) { return (UINT64(time.dwHighDateTime) << 32) | time.dwLowDateTime; };
    return static_cast<int64_t>(100 * (ticks(kernel) + ticks(user)));
}

void registerPresentTrace() {
    static const struct Registration {
        Registration() { TraceLoggingRegister(presentTrace); }
        ~Registration() { TraceLoggingUnregister(presentTrace); }
    } registration;
}

struct PresentProfile {
    int64_t frequency, previous;
    std::array<int64_t, ProfileCount> fields;

    PresentProfile() {
        LARGE_INTEGER value{};
        QueryPerformanceFrequency(&value);
        frequency = value.QuadPart;
        previous = qpc();
        fields.fill(Unavailable);
    }

    void split(ProfileField field) {
        const auto now = qpc();
        if (frequency > 0)
            fields[field] = static_cast<int64_t>((now - previous) * 1.0e9 / frequency);
        previous = now;
    }
};

struct TimestampProbe {
    ComPtr<ID3D12QueryHeap> heap;
    ComPtr<ID3D12Resource> readback;
    UINT64 frequency = 0;

    bool initialize(ID3D12Device* gpu, ID3D12CommandQueue* commands, D3D12_QUERY_HEAP_TYPE type, unsigned rows = SlotCount) {
        if (FAILED(commands->GetTimestampFrequency(&frequency)) || !frequency) return false;
        D3D12_QUERY_HEAP_DESC query{type, 2 * rows, 0};
        if (FAILED(gpu->CreateQueryHeap(&query, IID_PPV_ARGS(&heap)))) return false;
        auto memory = defaultMemory();
        memory.Type = D3D12_HEAP_TYPE_READBACK;
        auto storage = buffer(2 * rows * sizeof(UINT64));
        if (SUCCEEDED(gpu->CreateCommittedResource(&memory, D3D12_HEAP_FLAG_NONE, &storage,
                D3D12_RESOURCE_STATE_COPY_DEST, nullptr, IID_PPV_ARGS(&readback)))) return true;
        heap.Reset();
        return false;
    }

    void begin(ID3D12GraphicsCommandList* list, UINT slot) {
        list->EndQuery(heap.Get(), D3D12_QUERY_TYPE_TIMESTAMP, slot * 2);
    }

    void end(ID3D12GraphicsCommandList* list, UINT slot) {
        list->EndQuery(heap.Get(), D3D12_QUERY_TYPE_TIMESTAMP, slot * 2 + 1);
        list->ResolveQueryData(heap.Get(), D3D12_QUERY_TYPE_TIMESTAMP, slot * 2, 2,
                               readback.Get(), slot * 2 * sizeof(UINT64));
    }

    int64_t read(UINT slot, int64_t* endTick = nullptr, int64_t* startTick = nullptr) {
        void* mapped = nullptr;
        D3D12_RANGE range{slot * 2 * sizeof(UINT64), (slot * 2 + 2) * sizeof(UINT64)};
        if (FAILED(readback->Map(0, &range, &mapped))) return Unavailable;
        const auto ticks = static_cast<const UINT64*>(mapped) + slot * 2;
        if (endTick) *endTick = static_cast<int64_t>(ticks[1]);
        if (startTick) *startTick = static_cast<int64_t>(ticks[0]);
        const auto elapsed = ticks[1] >= ticks[0] ? static_cast<int64_t>((ticks[1] - ticks[0]) * 1.0e9 / frequency)
                                                : Unavailable;
        const D3D12_RANGE written{0, 0};
        readback->Unmap(0, &written);
        return elapsed;
    }
};

struct TimestampProbes {
    TimestampProbe producer, upload, local, unpack;
    unsigned mask = 0;
};

struct Constants {
    float view[16], projection[16], jitterX, jitterY, scaleX, scaleY;
    uint32_t resetHistory;
    float frameMs;
};
static_assert(sizeof(Constants) == 152);
constexpr UINT ReprojectionWords = std::tuple_size_v<Reprojection>;

struct ColorDecoder {
    ComPtr<ID3D12RootSignature> root;
    ComPtr<ID3D12PipelineState> pipeline;
    ComPtr<ID3D12DescriptorHeap> views;
    UINT stride = 0;

    void initialize(ID3D12Device* gpu, bool sharedInputs) {
        constexpr char shader[] = R"(
ByteAddressBuffer packed : register(t0);
RWTexture2D<unorm float4> finalImage : register(u0);
RWTexture2D<unorm float4> hudlessImage : register(u1);
#if SHARED_INPUTS
RWTexture2D<float> depthImage : register(u2);
RWTexture2D<float2> motionImage : register(u3);
groupshared float depthTile[18 * 10];
#endif
cbuffer Layout : register(b0) {
    uint width, height, packedStride, packedPlane, mode;
#if SHARED_INPUTS
    uint3 padding;
    float4x4 currentToPrevious, skyToPrevious;
    float2 jitter;
#endif
};
uint decodeRgb(uint row, uint x) {
    uint byteOffset = x * 3;
    uint word = byteOffset & ~3u;
    uint shift = (byteOffset & 3u) * 8;
    uint rgb = packed.Load(row + word) >> shift;
    if (shift > 8) rgb |= packed.Load(row + word + 4) << (32 - shift);
    return rgb & 0xffffff;
}
uint decode422(uint row, uint x) {
    uint pair = packed.Load(row + (x / 2) * 4);
    float y = float((pair >> ((x & 1) * 16)) & 255) / 255.0;
    float cb = (float((pair >> 8) & 255) - 128.0) / 255.0;
    float cr = (float((pair >> 24) & 255) - 128.0) / 255.0;
    float r = y + 1.5748 * cr;
    float b = y + 1.8556 * cb;
    float g = (y - 0.2126 * r - 0.0722 * b) / 0.7152;
    uint3 rgb = (uint3)round(saturate(float3(r, g, b)) * 255);
    return rgb.r | (rgb.g << 8) | (rgb.b << 16);
}
float4 rgba(uint rgb) {
    return float4(float3(rgb & 255, (rgb >> 8) & 255, (rgb >> 16) & 255) / 255.0, 1.0);
}
#if SHARED_INPUTS
uint4 expandRgb(uint3 words) {
    return uint4(words.x, (words.x >> 24) | (words.y << 8),
                 (words.y >> 16) | (words.z << 16), words.z >> 8);
}

void reconstructMotion(uint2 pixel, uint2 localPixel) {
    const int2 limit = int2(width, height) - 1;
    const int2 groupOrigin = int2(pixel) - int2(localPixel);
    const uint lane = localPixel.y * 16 + localPixel.x;
    // The tile has a one-pixel halo. The export and UAV both use upper-left row order.
    for (uint entry = lane; entry < 180; entry += 128) {
        int2 p = clamp(groupOrigin + int2(entry % 18, entry / 18) - 1, 0, limit);
        depthTile[entry] = asfloat(packed.Load(p.y * packedStride + p.x * 4));
    }
    GroupMemoryBarrierWithGroupSync();
    if (pixel.x >= width || pixel.y >= height) return;
    const int2 center = int2(pixel);
    const int2 tileCenter = int2(localPixel) + 1;
    const float depth = depthTile[tileCenter.y * 18 + tileCenter.x];
    float closest = depth;
    int2 nearest = center;
    // Traverse in the same order as OpenGL, including ties at geometry edges.
    [unroll] for (int y = 1; y >= -1; --y) {
        [unroll] for (int x = -1; x <= 1; ++x) {
            int2 samplePixel = clamp(center + int2(x, y), 0, limit);
            int2 tilePixel = samplePixel - groupOrigin + 1;
            float candidate = depthTile[tilePixel.y * 18 + tilePixel.x];
            if (candidate < closest) {
                closest = candidate;
                nearest = samplePixel;
            }
        }
    }

    const float2 size = float2(width, height);
    const float2 currentNdc = ((float2(nearest.x, limit.y - nearest.y) + 0.5 - jitter) / size) * 2 - 1;
    const float4 currentClip = float4(currentNdc, closest * 2 - 1, 1);
    const float4 previous = closest >= 1 ? mul(skyToPrevious, currentClip) : mul(currentToPrevious, currentClip);
    float2 motion = 0;
    if (previous.w > 0.00001) {
        motion = (previous.xy / previous.w * 0.5 + 0.5 - (currentNdc * 0.5 + 0.5)) * float2(width, -float(height));
        motion = clamp(motion, -size, size);
    }
    depthImage[pixel] = depth;
    // Quantize explicitly like GLSL packHalf2x16 before writing the typed UAV.
    motionImage[pixel] = f16tof32(f32tof16(motion));
}
#endif
[numthreads(16, 8, 1)]
void main(uint3 pixel : SV_DispatchThreadID, uint2 localPixel : SV_GroupThreadID) {
#if SHARED_INPUTS
    if (mode == 6) {
        reconstructMotion(pixel.xy, localPixel);
        return;
    }
    if (mode == 5) {
        if (pixel.x >= width || pixel.y >= height) return;
        uint address = pixel.y * packedStride + pixel.x * 4;
        depthImage[pixel.xy] = asfloat(packed.Load(address));
        uint motion = packed.Load(packedPlane + address);
        motionImage[pixel.xy] = f16tof32(uint2(motion & 65535, motion >> 16));
        return;
    }
    if (mode == 4) {
        const uint block = pixel.x;
        const uint2 origin = uint2(block * 4, pixel.y);
        if (origin.x >= width || origin.y >= height) return;
        uint row = origin.y * packedStride + block * 12;
        uint mask = packed.Load(2 * packedPlane + 4 * (origin.y * ((width + 127) / 128) + block / 32));
        uint4 finalColors = expandRgb(packed.Load3(row));
        uint4 hudlessColors = finalColors;
        if ((mask & (1u << (block % 32))) != 0) hudlessColors = expandRgb(packed.Load3(row + packedPlane));
        [unroll] for (uint i = 0; i < 4; ++i) {
            if (origin.x + i < width) {
                uint2 p = origin + uint2(i, 0);
                finalImage[p] = rgba(finalColors[i]);
                hudlessImage[p] = rgba(hudlessColors[i]);
            }
        }
        return;
    }
#endif
    if (pixel.x >= width || pixel.y >= height) return;
    uint row = pixel.z * packedPlane + pixel.y * packedStride;
    uint rgb = mode == 1 ? decodeRgb(row, pixel.x) : decode422(row, pixel.x);
    float4 color = rgba(rgb);
    if (pixel.z == 0) finalImage[pixel.xy] = color;
    else hudlessImage[pixel.xy] = color;
}
)";
        D3D12_ROOT_PARAMETER parameters[3]{};
        parameters[0].ParameterType = D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;
        parameters[0].Constants.Num32BitValues = sharedInputs ? 8 + ReprojectionWords : 5;
        parameters[1].ParameterType = D3D12_ROOT_PARAMETER_TYPE_SRV;
        const UINT planes = sharedInputs ? PlaneCount : 2;
        D3D12_DESCRIPTOR_RANGE colors{D3D12_DESCRIPTOR_RANGE_TYPE_UAV, planes, 0, 0, 0};
        parameters[2].ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
        parameters[2].DescriptorTable = {1, &colors};
        D3D12_ROOT_SIGNATURE_DESC desc{};
        desc.NumParameters = 3;
        desc.pParameters = parameters;
        ComPtr<ID3DBlob> signature, code, errors;
        check(D3D12SerializeRootSignature(&desc, D3D_ROOT_SIGNATURE_VERSION_1, &signature, nullptr),
              "Serializing XeSS-FG color unpack root signature");
        check(gpu->CreateRootSignature(0, signature->GetBufferPointer(), signature->GetBufferSize(), IID_PPV_ARGS(&root)),
              "Creating XeSS-FG color unpack root signature");
        const D3D_SHADER_MACRO defines[]{{"SHARED_INPUTS", sharedInputs ? "1" : "0"}, {nullptr, nullptr}};
        HRESULT compiled = D3DCompile(shader, sizeof(shader) - 1, "XeSS-FG input unpack", defines, nullptr, "main",
                                      "cs_5_1", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &code, &errors);
        if (FAILED(compiled) && errors) throw std::runtime_error(static_cast<const char*>(errors->GetBufferPointer()));
        check(compiled, "Compiling XeSS-FG color unpack");
        D3D12_COMPUTE_PIPELINE_STATE_DESC pso{};
        pso.pRootSignature = root.Get();
        pso.CS = {code->GetBufferPointer(), code->GetBufferSize()};
        check(gpu->CreateComputePipelineState(&pso, IID_PPV_ARGS(&pipeline)), "Creating XeSS-FG color unpack pipeline");
        D3D12_DESCRIPTOR_HEAP_DESC heap{D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV, planes * InputCount,
                                       D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE, 0};
        check(gpu->CreateDescriptorHeap(&heap, IID_PPV_ARGS(&views)), "Allocating XeSS-FG color UAV descriptors");
        stride = gpu->GetDescriptorHandleIncrementSize(heap.Type);
    }

    void bindTextures(ID3D12Device* gpu, PreparedInput& input, unsigned index, bool sharedInputs) {
        const UINT planes = sharedInputs ? PlaneCount : 2;
        D3D12_UNORDERED_ACCESS_VIEW_DESC view{};
        view.ViewDimension = D3D12_UAV_DIMENSION_TEXTURE2D;
        auto cpu = views->GetCPUDescriptorHandleForHeapStart();
        cpu.ptr += SIZE_T(index) * planes * stride;
        input.colorUavs = views->GetGPUDescriptorHandleForHeapStart();
        input.colorUavs.ptr += UINT64(index) * planes * stride;
        for (unsigned i = 0; i < planes; ++i) {
            view.Format = Formats[i];
            gpu->CreateUnorderedAccessView(input.planes[i].Get(), nullptr, &view, cpu);
            cpu.ptr += stride;
        }
    }
};

struct PresentJob {
    unsigned slot;
    uint32_t frame;
    UINT64 sequence;
    bool interpolate, vsync, discard;
    Constants constants;
    int64_t captureFrame, enqueuedAt;
    unsigned recordedQueries = 0;
    UINT64 geometryBytes = 0;
    int64_t colorSubmitQpc = Unavailable;
    UINT64 glFenceAtSubmit = 0;
};

constexpr unsigned PresentResultFields = 3 + ProfileCount;
constexpr unsigned ResultCapacity = SlotCount * 4;
using PresentResult = std::array<int64_t, PresentResultFields>;

void logSdk(const char* message, xefg_swapchain_logging_level_t, void*) {
    OutputDebugStringA(message);
    OutputDebugStringA("\n");
}

LRESULT CALLBACK surfaceProc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
        case WM_NCHITTEST: return HTTRANSPARENT;
        case WM_MOUSEACTIVATE: return MA_NOACTIVATE;
        case WM_ERASEBKGND: return 1;
        default: return DefWindowProcW(window, message, wparam, lparam);
    }
}

bool clientOnScreen(HWND owner, RECT& rect) {
    POINT origin{};
    if (!GetClientRect(owner, &rect) || !ClientToScreen(owner, &origin)) return false;
    OffsetRect(&rect, origin.x, origin.y);
    return true;
}

HWND createPresentationSurface(HWND owner, bool topLevel) {
    constexpr wchar_t name[] = L"CmiXeSSFrameSurface";
    HINSTANCE application = GetModuleHandleW(nullptr);
    WNDCLASSEXW windowClass{};
    windowClass.cbSize = sizeof(windowClass);
    windowClass.lpfnWndProc = surfaceProc;
    windowClass.hInstance = application;
    windowClass.lpszClassName = name;
    if (!RegisterClassExW(&windowClass) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS)
        check(HRESULT_FROM_WIN32(GetLastError()), "Registering XeSS-FG presentation surface");
    RECT client{};
    if (!(topLevel ? clientOnScreen(owner, client) : GetClientRect(owner, &client)))
        check(HRESULT_FROM_WIN32(GetLastError()), "Reading Minecraft window client dimensions");
    const DWORD extended = WS_EX_NOACTIVATE | (topLevel ? WS_EX_TOOLWINDOW : WS_EX_NOPARENTNOTIFY);
    const DWORD style = WS_DISABLED | (topLevel ? WS_POPUP : WS_CHILD | WS_CLIPSIBLINGS);
    auto surface = CreateWindowExW(extended, name, L"", style, client.left, client.top,
        client.right - client.left, client.bottom - client.top, owner, nullptr, application, nullptr);
    if (!surface) check(HRESULT_FROM_WIN32(GetLastError()), "Creating XeSS-FG presentation surface");
    return surface;
}

struct Session {
    UINT width = 0, height = 0;
    UINT64 sequence = 0, slotWaitSequence = 0;
    std::array<UINT64, GroupCount> groupBytes{};
    Transport transport = Rgba8;
    UINT colorStride = 0;
    UINT64 colorPlaneBytes = 0;
    ColorDecoder decoder;
    bool directGeometry = false, sharedInputs = false;
    int activeSlot = -1;
    unsigned cursor = 0;
    bool cross = false, tearing = false, enabled = false;
    HWND surface = nullptr, host = nullptr;
    bool topLevel = false;
    RECT popupBounds{};
    bool surfaceShown = false;
    std::array<D3D12_PLACED_SUBRESOURCE_FOOTPRINT, PlaneCount> footprints{};
    std::array<Slot, SlotCount> slots;
    std::array<PreparedInput, InputCount> inputs;
    ComPtr<ID3D12Device> renderGpu, generationGpu;
    ComPtr<ID3D12CommandQueue> copyQueue, uploadQueue, prepareQueue, presentQueue;
    ComPtr<ID3D12Fence> glWritten, glReleased, transferOut, transferIn, uploadReady, prepared, completed;
    ComPtr<IDXGISwapChain4> swap;
    std::unique_ptr<TimestampProbes> profileProbes;
    std::array<ComPtr<ID3D12Resource>, BackBufferCount> backBuffers;
    Handle slotReady, presentAllocatorReady;
    Handle workReady, queueSpace, initialized, stopped;
    std::thread presenter;
    std::mutex workMutex, xellMutex;
    std::optional<PresentJob> waiting;
    bool closing = false, resetNext = false;
    std::exception_ptr failure;
    std::array<PresentResult, ResultCapacity> results{};
    unsigned resultStart = 0, resultCount = 0;
    xefg_swapchain_handle_t fg = nullptr;
    xell_context_handle_t ll = nullptr;

    ~Session() {
        if (presenter.joinable()) {
            {
                std::lock_guard guard(workMutex);
                closing = true;
                SetEvent(workReady.value);
            }
            unblockFailedProducer();
            waitWithMessages(stopped.value);
            presenter.join();
        } else {
            releasePresentation();
        }
    }

    void releasePresentation() {
        hideSurface();
        unblockFailedProducer();
        drain(presentQueue.Get(), completed.Get());
        drain(prepareQueue.Get(), prepared.Get());
        drain(uploadQueue.Get(), uploadReady.Get());
        drain(copyQueue.Get(), glReleased.Get());
        for (auto& image : backBuffers) image.Reset();
        swap.Reset();
        if (fg) xefgSwapChainDestroy(fg);
        if (ll) xellDestroyContext(ll);
        if (IsWindow(surface)) DestroyWindow(surface);
    }

    void unblockFailedProducer() {
        // Arc may be inside Present waiting on a copy the removed RTX can no longer signal.
        if (cross && transferIn && FAILED(renderGpu->GetDeviceRemovedReason())) transferIn->Signal(DrainValue);
    }

    static void pumpMessages(std::optional<int>& quit) {
        MSG message{};
        while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
            if (message.message == WM_QUIT) quit = static_cast<int>(message.wParam);
            else {
                TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
    }

    static void waitWithMessages(HANDLE event) {
        std::optional<int> quit;
        while (MsgWaitForMultipleObjectsEx(1, &event, INFINITE, QS_ALLINPUT, MWMO_INPUTAVAILABLE) == WAIT_OBJECT_0 + 1)
            pumpMessages(quit);
        if (quit) PostQuitMessage(*quit);
    }

    void start(HWND hwnd, jlong render, jlong generate, UINT w, UINT h, Transport requested,
               bool popup = false, bool copyGeometryToTextures = false, bool allowSharedInputs = false) {
        workReady.value = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        queueSpace.value = CreateEventW(nullptr, TRUE, TRUE, nullptr);
        initialized.value = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        stopped.value = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (!workReady.value || !queueSpace.value || !initialized.value || !stopped.value)
            throw std::runtime_error("Creating XeSS-FG presenter events");
        presenter = std::thread([=] {
            SetThreadDescription(GetCurrentThread(), L"CMI XeSS-FG Presenter");
            try {
                initialize(hwnd, render, generate, w, h, requested, popup, copyGeometryToTextures, allowSharedInputs);
            } catch (...) {
                recordFailure();
            }
            SetEvent(initialized.value);
            presentLoop();
            releasePresentation();
            SetEvent(stopped.value);
        });
        waitWithMessages(initialized.value);
        checkPresenter();
    }

    void recordFailure() {
        std::lock_guard guard(workMutex);
        failure = std::current_exception();
        SetEvent(queueSpace.value);
    }

    void checkPresenter() {
        std::lock_guard guard(workMutex);
        if (failure) std::rethrow_exception(failure);
    }

    bool enqueue(PresentJob job) {
        std::lock_guard guard(workMutex);
        if (failure) std::rethrow_exception(failure);
        if (closing) throw std::runtime_error("XeSS-FG presenter is closing");
        if (waiting) return false;
        auto& slot = active(job.slot, job.sequence);
        if (!job.discard && (!slot.colorSubmitted || slot.fullColor != job.interpolate))
            throw std::logic_error("Missing XeSS-FG inputs for presentation");
        if (job.captureFrame > 0) job.enqueuedAt = qpc();
        if (!slot.colorSubmitted) discardTransfer(slot, job.sequence);
        job.geometryBytes = slot.geometryBytes;
        job.recordedQueries = slot.recordedQueries;
        if (slot.captureFrame > 0 && slot.colorSubmitted) {
            job.colorSubmitQpc = slot.colorSubmitQpc;
            job.glFenceAtSubmit = slot.glFenceAtSubmit;
        }
        waiting = job;
        activeSlot = -1;
        ResetEvent(queueSpace.value);
        SetEvent(workReady.value);
        return true;
    }

    void presentLoop() {
        std::optional<int> quit;
        for (;;) {
            pumpMessages(quit);
            std::optional<PresentJob> job;
            {
                std::lock_guard guard(workMutex);
                if (closing) break;
                if (quit) {
                    failure = std::make_exception_ptr(std::runtime_error("XeSS-FG presenter received WM_QUIT"));
                    quit.reset();
                    SetEvent(queueSpace.value);
                }
                if (!failure && waiting) {
                    job = waiting;
                    waiting.reset();
                    SetEvent(queueSpace.value);
                }
            }
            if (!job) {
                MsgWaitForMultipleObjectsEx(1, &workReady.value, INFINITE, QS_ALLINPUT, MWMO_INPUTAVAILABLE);
                continue;
            }
            try {
                run(*job);
            } catch (...) {
                recordFailure();
            }
        }
    }

    void run(PresentJob& job) {
        if (job.discard) {
            retirePresentation(job.sequence);
            resetNext = true;
            return;
        }
        if (resetNext && job.interpolate) job.constants.resetHistory = 1;
        std::optional<PresentProfile> profile;
        if (job.captureFrame > 0) {
            profile.emplace();
            profile->fields[PresentQueueDelay] = (profile->previous - job.enqueuedAt) * 1.0e9 / profile->frequency;
        }
        const int queued = present(job, profile ? &*profile : nullptr);
        retirePresentation(job.sequence);
        resetNext = !queued || !job.interpolate;
        if (!profile) return;
        std::lock_guard guard(workMutex);
        profile->fields[WorkerPresentDuration] = (qpc() - job.enqueuedAt) * 1.0e9 / profile->frequency
                                                 - profile->fields[PresentQueueDelay];
        auto& result = results[(resultStart + resultCount) % ResultCapacity];
        result[0] = job.captureFrame;
        result[1] = queued;
        result[2] = job.interpolate && job.constants.resetHistory;
        std::copy(profile->fields.begin(), profile->fields.end(), result.begin() + 3);
        if (resultCount == ResultCapacity) resultStart = (resultStart + 1) % ResultCapacity;
        else ++resultCount;
    }

    int pollPresents(int64_t* output) {
        std::lock_guard guard(workMutex);
        const auto count = resultCount;
        for (unsigned i = 0; i < count; ++i) {
            const auto& result = results[(resultStart + i) % ResultCapacity];
            std::copy(result.begin(), result.end(), output + i * PresentResultFields);
        }
        resultStart = resultCount = 0;
        return count;
    }

    void hideSurface() {
        if (surfaceShown && IsWindow(surface)) ShowWindow(surface, SW_HIDE);
        surfaceShown = false;
    }

    bool positionPopup() {
        RECT bounds{};
        if (!IsWindowVisible(host) || IsIconic(host) || !clientOnScreen(host, bounds)
                || bounds.right - bounds.left != static_cast<LONG>(width)
                || bounds.bottom - bounds.top != static_cast<LONG>(height)) {
            hideSurface();
            return false;
        }
        if (!surfaceShown || !EqualRect(&bounds, &popupBounds)) {
            if (!SetWindowPos(surface, nullptr, bounds.left, bounds.top, width, height,
                    SWP_NOACTIVATE | SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_SHOWWINDOW))
                check(HRESULT_FROM_WIN32(GetLastError()), "Positioning the borderless XeSS-FG surface");
            popupBounds = bounds;
            surfaceShown = true;
        }
        return true;
    }

    void drain(ID3D12CommandQueue* q, ID3D12Fence* f) {
        if (!q || !f || sequence == 0 || f->GetCompletedValue() == UINT64_MAX) return;
        Handle retired;
        retired.value = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (!retired.value) return;
        if (SUCCEEDED(q->Signal(f, DrainValue)) && SUCCEEDED(f->SetEventOnCompletion(DrainValue, retired.value)))
            WaitForSingleObject(retired.value, 3000);
    }

    void initialize(HWND hwnd, jlong render, jlong generate, UINT w, UINT h, Transport requested, bool popup,
                    bool copyGeometryToTextures = false, bool allowSharedInputs = false) {
        width = w;
        height = h;
        host = hwnd;
        topLevel = popup;
        cross = render != generate;
        transport = cross ? requested : Rgba8;
        slotReady.value = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        presentAllocatorReady.value = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (!slotReady.value || !presentAllocatorReady.value) throw std::runtime_error("Creating XeSS-FG fence events");
        ComPtr<IDXGIFactory4> factory;
        check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)), "Creating XeSS-FG DXGI factory");
        DXGI_ADAPTER_DESC1 renderAdapter{}, generationAdapter{};
        renderGpu = device(factory.Get(), render, &renderAdapter);
        generationGpu = cross ? device(factory.Get(), generate, &generationAdapter) : renderGpu;
        sharedInputs = allowSharedInputs && cross && transport == Rgb24 && renderAdapter.VendorId == 0x10de
            && generationAdapter.VendorId == 0x8086 && generationAdapter.DeviceId == 0x56a1
            && supportsUavStore(Formats[Depth]) && supportsUavStore(Formats[Motion]);
        directGeometry = cross && copyGeometryToTextures && !sharedInputs;
        presentQueue = queue(generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_DIRECT);
        prepareQueue = queue(generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_COMPUTE);
        check(renderGpu->CreateFence(0, D3D12_FENCE_FLAG_SHARED, IID_PPV_ARGS(&glWritten)), "Creating OpenGL producer fence");
        check(renderGpu->CreateFence(0, D3D12_FENCE_FLAG_SHARED, IID_PPV_ARGS(&glReleased)), "Creating OpenGL release fence");
        check(generationGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&completed)), "Creating presentation completion fence");
        check(generationGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&prepared)), "Creating input preparation fence");
        if (cross) {
            copyQueue = queue(renderGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
            if (!sharedInputs) {
                uploadQueue = queue(generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
                check(generationGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&uploadReady)),
                      "Creating generation GPU upload fence");
            }
            check(renderGpu->CreateFence(0, D3D12_FENCE_FLAG_SHARED | D3D12_FENCE_FLAG_SHARED_CROSS_ADAPTER,
                  IID_PPV_ARGS(&transferOut)), "Creating cross-adapter fence");
            Handle fence;
            check(renderGpu->CreateSharedHandle(transferOut.Get(), nullptr, GENERIC_ALL, nullptr, &fence.value),
                  "Exporting cross-adapter fence");
            check(generationGpu->OpenSharedHandle(fence.value, IID_PPV_ARGS(&transferIn)), "Importing cross-adapter fence");
        }

        for (unsigned i = 0; i < PlaneCount; ++i) {
            auto desc = texture(Formats[i], width, height);
            auto& bytes = groupBytes[groupOf(static_cast<Plane>(i))];
            generationGpu->GetCopyableFootprints(&desc, 0, 1, bytes, &footprints[i], nullptr, nullptr, nullptr);
            UINT64 end = footprints[i].Offset + UINT64(footprints[i].Footprint.RowPitch) * height;
            bytes = (end + D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT - 1)
                    & ~(UINT64(D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT) - 1);
        }
        colorStride = footprints[Final].Footprint.RowPitch;
        colorPlaneBytes = footprints[Hudless].Offset;
        if (transport != Rgba8) {
            const UINT64 packedRow = transport == Rgb24 ? ((UINT64(width) + 3) / 4) * 12 : ((UINT64(width) + 1) / 2) * 4;
            colorStride = static_cast<UINT>(alignUp(packedRow, D3D12_TEXTURE_DATA_PITCH_ALIGNMENT));
            colorPlaneBytes = alignUp(UINT64(colorStride) * height, D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT);
            groupBytes[Color] = 2 * colorPlaneBytes;
            if (sharedInputs) groupBytes[Color] += UINT64((width + 127) / 128) * height * sizeof(uint32_t);
            decoder.initialize(generationGpu.Get(), sharedInputs);
        }
        for (auto& slot : slots) allocate(slot);
        for (unsigned i = 0; i < InputCount; ++i) allocate(inputs[i], i);

        sdk(xellD3D12CreateContext(generationGpu.Get(), &ll), "Creating XeLL context");
        xell_sleep_params_t sleep{};
        sleep.bLowLatencyMode = 1;
        sdk(xellSetSleepMode(ll, &sleep), "Enabling XeLL");
        sdk(xefgSwapChainD3D12CreateContext(generationGpu.Get(), &fg), "Creating XeSS-FG context");
        sdk(xefgSwapChainSetLoggingCallback(fg, XEFG_SWAPCHAIN_LOGGING_LEVEL_WARNING, logSdk, nullptr),
            "Installing XeSS-FG logging");
        sdk(xefgSwapChainSetLatencyReduction(fg, ll), "Connecting XeSS-FG with XeLL");
        xefg_swapchain_d3d12_init_params_t params{};
        params.initFlags = XEFG_SWAPCHAIN_INIT_FLAG_HIGH_RES_MV;
        params.maxInterpolatedFrames = 1;
        params.creationNodeMask = params.visibleNodeMask = 1;
        params.uiMode = XEFG_SWAPCHAIN_UI_MODE_BACKBUFFER_HUDLESS;
        sdk(xefgSwapChainD3D12BuildPipelines(fg, nullptr, 0, params.initFlags), "Precompiling XeSS-FG kernels");

        ComPtr<IDXGIFactory5> factory5;
        BOOL canTear = FALSE;
        if (SUCCEEDED(factory.As(&factory5)))
            factory5->CheckFeatureSupport(DXGI_FEATURE_PRESENT_ALLOW_TEARING, &canTear, sizeof(canTear));
        tearing = canTear != FALSE;
        DXGI_SWAP_CHAIN_DESC1 desc{};
        desc.Width = width;
        desc.Height = height;
        desc.Format = Formats[Final];
        desc.SampleDesc.Count = 1;
        desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
        desc.BufferCount = BackBufferCount;
        desc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
        desc.AlphaMode = DXGI_ALPHA_MODE_IGNORE;
        desc.Flags = tearing ? DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING : 0;
        // GLFW's WGL presentation surface can itself occupy the HWND's DXGI flip path.
        // Give the proxy a separate surface while GLFW retains focus and input.
        surface = createPresentationSurface(hwnd, topLevel);
        sdk(xefgSwapChainD3D12InitFromSwapChainDesc(fg, surface, &desc, nullptr, presentQueue.Get(), factory.Get(), &params),
            "Creating Minecraft's XeSS-FG proxy swap chain");
        sdk(xefgSwapChainD3D12GetSwapChainPtr(fg, IID_PPV_ARGS(&swap)), "Opening XeSS-FG proxy swap chain");
        for (UINT i = 0; i < BackBufferCount; ++i)
            check(swap->GetBuffer(i, IID_PPV_ARGS(&backBuffers[i])), "Opening XeSS-FG proxy back buffer");
        sdk(xefgSwapChainSetUiCompositionState(fg, XEFG_SWAPCHAIN_UI_COMPOSITION_STATE_ENABLED),
            "Enabling XeSS-FG HUD composition");
        sdk(xefgSwapChainSetEnabled(fg, 0), "Initializing XeSS-FG in passthrough mode");
    }

    bool supportsUavStore(DXGI_FORMAT format) const {
        D3D12_FEATURE_DATA_FORMAT_SUPPORT support{format};
        return SUCCEEDED(generationGpu->CheckFeatureSupport(D3D12_FEATURE_FORMAT_SUPPORT, &support, sizeof(support)))
            && (support.Support1 & D3D12_FORMAT_SUPPORT1_TYPED_UNORDERED_ACCESS_VIEW) != 0
            && (support.Support2 & D3D12_FORMAT_SUPPORT2_UAV_TYPED_STORE) != 0;
    }

    void allocate(Slot& slot) {
        auto memory = defaultMemory();
        slot.prepare.create(generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_COMPUTE);
        for (unsigned i = 0; i < GroupCount; ++i) {
            auto& transfer = slot.groups[i];
            auto storage = buffer(groupBytes[i]);
            storage.Flags = D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;
            check(renderGpu->CreateCommittedResource(&memory, D3D12_HEAP_FLAG_SHARED, &storage,
                  D3D12_RESOURCE_STATE_COMMON, nullptr, IID_PPV_ARGS(&transfer.glBuffer)),
                  "Allocating shared OpenGL frame storage");
            if (!cross) continue;
            transfer.producer.create(renderGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
            if (!sharedInputs) transfer.upload.create(generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
            storage.Flags = D3D12_RESOURCE_FLAG_NONE;
            if (!sharedInputs && (!directGeometry || i != Geometry))
                check(generationGpu->CreateCommittedResource(&memory, D3D12_HEAP_FLAG_NONE, &storage,
                      D3D12_RESOURCE_STATE_COMMON, nullptr, IID_PPV_ARGS(&transfer.local)),
                      "Allocating generation GPU staging buffer");

            storage.Flags = D3D12_RESOURCE_FLAG_ALLOW_CROSS_ADAPTER;
            auto info = renderGpu->GetResourceAllocationInfo(0, 1, &storage);
            D3D12_HEAP_DESC heap{info.SizeInBytes, memory, info.Alignment,
                D3D12_HEAP_FLAG_SHARED | D3D12_HEAP_FLAG_SHARED_CROSS_ADAPTER};
            check(renderGpu->CreateHeap(&heap, IID_PPV_ARGS(&transfer.sourceHeap)), "Allocating cross-adapter frame heap");
            Handle shared;
            check(renderGpu->CreateSharedHandle(transfer.sourceHeap.Get(), nullptr, GENERIC_ALL, nullptr, &shared.value),
                  "Exporting cross-adapter frame heap");
            check(generationGpu->OpenSharedHandle(shared.value, IID_PPV_ARGS(&transfer.destinationHeap)),
                  "Importing cross-adapter frame heap");
            check(renderGpu->CreatePlacedResource(transfer.sourceHeap.Get(), 0, &storage, D3D12_RESOURCE_STATE_COMMON,
                  nullptr, IID_PPV_ARGS(&transfer.source)), "Opening source cross-adapter buffer");
            check(generationGpu->CreatePlacedResource(transfer.destinationHeap.Get(), 0, &storage, D3D12_RESOURCE_STATE_COMMON,
                  nullptr, IID_PPV_ARGS(&transfer.destination)), "Opening destination cross-adapter buffer");
        }
    }

    void allocate(PreparedInput& input, unsigned index) {
        const auto memory = defaultMemory();
        input.present.create(generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_DIRECT);
        for (unsigned i = 0; i < PlaneCount; ++i) {
            auto desc = texture(Formats[i], width, height);
            if (transport != Rgba8 && (i <= Hudless || sharedInputs)) desc.Flags = D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;
            check(generationGpu->CreateCommittedResource(&memory, D3D12_HEAP_FLAG_NONE, &desc, inputState(static_cast<Plane>(i)), nullptr,
                IID_PPV_ARGS(&input.planes[i])), "Allocating local XeSS-FG input");
        }
        if (transport != Rgba8) decoder.bindTextures(generationGpu.Get(), input, index, sharedInputs);
    }

    UINT64 preparedSequence() {
        auto value = prepared->GetCompletedValue();
        if (value == UINT64_MAX) throw std::runtime_error("XeSS-FG device removed");
        return value;
    }

    int slotsInUse() {
        const auto finished = preparedSequence();
        int count = 0;
        for (const auto& slot : slots) count += slot.reuseAfter > finished;
        return count;
    }

    int acquire(UINT64 next, int64_t captureFrame) {
        std::lock_guard guard(workMutex);
        if (failure) std::rethrow_exception(failure);
        if (activeSlot >= 0 || next <= sequence) throw std::logic_error("XeSS-FG frame acquisition out of order");
        const auto finished = preparedSequence();
        for (unsigned i = 0; i < SlotCount; ++i) {
            const unsigned index = (cursor + i) % SlotCount;
            auto& slot = slots[index];
            if (finished < slot.reuseAfter) continue;
            if (captureFrame > 0) prepareProfile();
            slot.captureFrame = slot.pendingQueries ? 0 : captureFrame;
            slot.recordedQueries = 0;
            slot.geometryBytes = 0;
            slot.reprojection.reset();
            slot.geometrySubmitted = slot.colorSubmitted = slot.fullColor = false;
            cursor = (index + 1) % SlotCount;
            sequence = next;
            return activeSlot = static_cast<int>(index);
        }
        return -1;
    }

    Slot& active(unsigned index, UINT64 next) {
        if (activeSlot != static_cast<int>(index) || sequence != next)
            throw std::logic_error("XeSS-FG transfer does not match the acquired frame");
        return slots.at(index);
    }

    void waitForSlot() {
        checkPresenter();
        if (slotsInUse() < SlotCount) return;
        UINT64 oldest = slots.front().reuseAfter;
        for (const auto& slot : slots) if (slot.reuseAfter < oldest) oldest = slot.reuseAfter;
        if (slotWaitSequence != oldest) {
            if (!ResetEvent(slotReady.value)) throw std::runtime_error("Resetting XeSS-FG slot fence event");
            check(prepared->SetEventOnCompletion(oldest, slotReady.value), "Waiting for earliest XeSS-FG frame slot");
            slotWaitSequence = oldest;
        }
        // Give GLFW time to handle e.g. resize or iconify if the GPU is still busy.
        if (MsgWaitForMultipleObjectsEx(1, &slotReady.value, 4, QS_ALLINPUT, MWMO_INPUTAVAILABLE) == WAIT_FAILED)
            throw std::runtime_error("Waiting for XeSS-FG slot or window event");
        check(renderGpu->GetDeviceRemovedReason(), "Checking XeSS-FG producer during slot wait");
    }

    void retireTransfer(Slot& slot, UINT64 retiredSequence) {
        check(prepareQueue->Signal(prepared.Get(), retiredSequence), "Retiring XeSS-FG transfer slot");
        slot.reuseAfter = retiredSequence;
        if (slot.recordedQueries) {
            slot.profileFrame = slot.captureFrame;
            slot.profileSequence = retiredSequence;
            slot.pendingQueries = slot.recordedQueries;
        }
    }

    void discardTransfer(Slot& slot, UINT64 next) {
        if (slot.geometrySubmitted) {
            const auto token = transferToken(next, Geometry);
            waitForTransfer(token);
            releaseGl(token);
        }
        retireTransfer(slot, next);
    }

    void retirePresentation(UINT64 next) {
        check(presentQueue->Signal(completed.Get(), next), "Retiring XeSS-FG SDK inputs");
    }

    static bool copyTimestamps(TimestampProbe& probe, ID3D12Device* gpu, ID3D12CommandQueue* queue) {
        D3D12_FEATURE_DATA_D3D12_OPTIONS3 options{};
        return SUCCEEDED(gpu->CheckFeatureSupport(D3D12_FEATURE_D3D12_OPTIONS3, &options, sizeof(options)))
                && options.CopyQueueTimestampQueriesSupported
                && probe.initialize(gpu, queue, D3D12_QUERY_HEAP_TYPE_COPY_QUEUE_TIMESTAMP, SlotCount * GroupCount);
    }

    void prepareProfile() {
        if (profileProbes) return;
        registerPresentTrace();
        profileProbes = std::make_unique<TimestampProbes>();
        if (cross && copyTimestamps(profileProbes->producer, renderGpu.Get(), copyQueue.Get()))
            profileProbes->mask |= ProducerGeometry | ProducerColor;
        if (uploadQueue && copyTimestamps(profileProbes->upload, generationGpu.Get(), uploadQueue.Get()))
            profileProbes->mask |= UploadGeometry | UploadColor;
        if (profileProbes->local.initialize(generationGpu.Get(), prepareQueue.Get(), D3D12_QUERY_HEAP_TYPE_TIMESTAMP))
            profileProbes->mask |= LocalTextures;
        if (transport != Rgba8 && profileProbes->unpack.initialize(generationGpu.Get(), prepareQueue.Get(),
                D3D12_QUERY_HEAP_TYPE_TIMESTAMP)) profileProbes->mask |= ColorUnpack;
    }

    int pollProfile(int64_t* output) {
        if (!profileProbes) return 0;
        const UINT64 finished = prepared->GetCompletedValue();
        if (finished == UINT64_MAX) return 0;
        int rows = 0;
        for (UINT i = 0; i < SlotCount; ++i) {
            auto& slot = slots[i];
            const auto mask = slot.pendingQueries;
            if (!mask || finished < slot.profileSequence) continue;
            *output++ = slot.profileFrame;
            int64_t dmaEnd = Unavailable, inputsEnd = Unavailable, dmaStart = Unavailable;
            int64_t rtxStart = Unavailable, rtxEnd = Unavailable;
            int64_t rtxGeometryStart = Unavailable, rtxGeometryEnd = Unavailable;
            int64_t arcGeometryStart = Unavailable, arcGeometryEnd = Unavailable;
            *output++ = mask & ProducerGeometry
                ? profileProbes->producer.read(timestampIndex(i, Geometry), &rtxGeometryEnd, &rtxGeometryStart) : Unavailable;
            *output++ = mask & ProducerColor
                ? profileProbes->producer.read(timestampIndex(i, Color), &rtxEnd, &rtxStart) : Unavailable;
            *output++ = mask & UploadGeometry
                ? profileProbes->upload.read(timestampIndex(i, Geometry), &arcGeometryEnd, &arcGeometryStart) : Unavailable;
            *output++ = mask & UploadColor
                ? profileProbes->upload.read(timestampIndex(i, Color), &dmaEnd, &dmaStart) : Unavailable;
            int64_t copyStart = Unavailable, unpackStart = Unavailable, unpackEnd = Unavailable;
            const auto unpackNs = mask & ColorUnpack ? profileProbes->unpack.read(i, &unpackEnd, &unpackStart) : Unavailable;
            *output++ = mask & LocalTextures ? profileProbes->local.read(i, &inputsEnd, &copyStart) : Unavailable;
            *output++ = dmaEnd;
            *output++ = inputsEnd == Unavailable ? unpackEnd : inputsEnd;
            *output++ = rtxStart;
            *output++ = rtxEnd;
            *output++ = dmaStart;
            *output++ = rtxGeometryStart;
            *output++ = rtxGeometryEnd;
            *output++ = arcGeometryStart;
            *output++ = arcGeometryEnd;
            *output++ = unpackNs;
            *output++ = unpackStart == Unavailable ? copyStart : unpackStart;
            slot.pendingQueries = 0;
            ++rows;
        }
        return rows;
    }

    void sampleClocks(int64_t* output) {
        std::array<int64_t, ClockProfileFields> values;
        values.fill(Unavailable);
        LARGE_INTEGER frequency{};
        if (QueryPerformanceFrequency(&frequency)) values[0] = frequency.QuadPart;
        clockAnchor(uploadQueue.Get(), values.data() + 1);
        clockAnchor(prepareQueue.Get(), values.data() + 4);
        clockAnchor(copyQueue.Get(), values.data() + 7);
        std::memcpy(output, values.data(), sizeof(values));
    }

    static void clockAnchor(ID3D12CommandQueue* queue, int64_t* anchor) {
        UINT64 gpu, cpu, frequency;
        if (!queue || FAILED(queue->GetTimestampFrequency(&frequency)) || !frequency
                || FAILED(queue->GetClockCalibration(&gpu, &cpu))) return;
        anchor[0] = static_cast<int64_t>(gpu);
        anchor[1] = static_cast<int64_t>(cpu);
        anchor[2] = static_cast<int64_t>(frequency);
    }

    void sampleColorTransferFence(PresentProfile& profile, ProfileField field, UINT64 token) {
        const UINT64 value = transferIn->GetCompletedValue();
        if (value == UINT64_MAX) return;
        profile.fields[field] = static_cast<int64_t>(value);
        if (value >= token && profile.fields[ArcColorFenceObservedQpc] == Unavailable)
            profile.fields[ArcColorFenceObservedQpc] = qpc();
    }

    void submitTransfer(unsigned index, Slot& slot, Group group, UINT64 size) {
        if (!cross) return;
        const auto token = transferToken(sequence, group);
        auto& transfer = slot.groups[group];
        const unsigned producerBit = ProducerGeometry << group;
        const unsigned uploadBit = UploadGeometry << group;
        const bool timedProducer = slot.captureFrame > 0 && (profileProbes->mask & producerBit);
        const bool timedUpload = slot.captureFrame > 0 && (profileProbes->mask & uploadBit);
        const unsigned query = timestampIndex(index, group);

        auto producer = transfer.producer.begin();
        if (timedProducer) profileProbes->producer.begin(producer, query);
        producer->CopyBufferRegion(transfer.source.Get(), 0, transfer.glBuffer.Get(), 0, size);
        if (timedProducer) profileProbes->producer.end(producer, query);
        check(copyQueue->Wait(glWritten.Get(), token), "Waiting for OpenGL frame input");
        transfer.producer.submit(copyQueue.Get());
        check(copyQueue->Signal(glReleased.Get(), token), "Releasing OpenGL frame storage");
        check(copyQueue->Signal(transferOut.Get(), token), "Publishing cross-adapter frame input");
        if (timedProducer) slot.recordedQueries |= producerBit;
        if (sharedInputs) return;

        auto upload = transfer.upload.begin();
        if (timedUpload) profileProbes->upload.begin(upload, query);
        if (directGeometry && group == Geometry) {
            auto& input = inputsFor(sequence);
            // The SDK can still read this geometry until the previous use of the input slot completes.
            if (input.preparedAfter && completed->GetCompletedValue() < input.preparedAfter)
                check(uploadQueue->Wait(completed.Get(), input.preparedAfter), "Waiting for previous XeSS-FG geometry use");
            copyPlane(upload, transfer.destination.Get(), input.planes[Depth].Get(), Depth, D3D12_RESOURCE_STATE_COMMON);
            copyPlane(upload, transfer.destination.Get(), input.planes[Motion].Get(), Motion, D3D12_RESOURCE_STATE_COMMON);
        } else {
            upload->CopyBufferRegion(transfer.local.Get(), 0, transfer.destination.Get(), 0, size);
        }
        if (timedUpload) profileProbes->upload.end(upload, query);
        check(uploadQueue->Wait(transferIn.Get(), token), "Acquiring cross-adapter frame input");
        transfer.upload.submit(uploadQueue.Get());
        check(uploadQueue->Signal(uploadReady.Get(), token), "Publishing generation GPU input");
        if (timedUpload) slot.recordedQueries |= uploadBit;
    }

    void submitGeometry(unsigned index, UINT64 next, const Reprojection* reprojection = nullptr) {
        auto& slot = active(index, next);
        if (slot.geometrySubmitted || slot.colorSubmitted) throw std::logic_error("XeSS-FG geometry submitted out of order");
        if (reprojection) {
            if (!sharedInputs) throw std::logic_error("Unexpected XeSS-FG reprojection constants");
            slot.reprojection = *reprojection;
        }
        const auto bytes = reprojection ? footprints[Motion].Offset : groupBytes[Geometry];
        submitTransfer(index, slot, Geometry, bytes);
        slot.geometryBytes = cross ? bytes : 0;
        slot.geometrySubmitted = true;
    }

    void submitColor(unsigned index, UINT64 next, bool full) {
        auto& slot = active(index, next);
        if (slot.colorSubmitted || (full && !slot.geometrySubmitted))
            throw std::logic_error("XeSS-FG color submitted out of order");
        if (slot.captureFrame > 0) {
            slot.colorSubmitQpc = qpc();
            slot.glFenceAtSubmit = glWritten->GetCompletedValue();
        }
        submitTransfer(index, slot, Color, full ? groupBytes[Color] : colorPlaneBytes);
        prepareInputs(index, slot, next, full);
        slot.colorSubmitted = true;
        slot.fullColor = full;
    }

    void waitForTransfer(UINT64 token) {
        auto* fence = sharedInputs ? transferIn.Get() : cross ? uploadReady.Get() : glWritten.Get();
        check(prepareQueue->Wait(fence, token), "Acquiring XeSS-FG frame inputs");
    }

    void releaseGl(UINT64 token) {
        if (!cross) check(prepareQueue->Signal(glReleased.Get(), token), "Releasing shared OpenGL frame storage");
    }

    ID3D12Resource* sourceFor(Slot& slot, Plane plane) {
        auto& transfer = slot.groups[groupOf(plane)];
        if (sharedInputs) return transfer.destination.Get();
        return cross ? transfer.local.Get() : transfer.glBuffer.Get();
    }

    void unpackColor(ID3D12GraphicsCommandList* cmd, Slot& slot, PreparedInput& input, bool full) {
        auto* finalImage = input.planes[Final].Get();
        auto* hudless = input.planes[Hudless].Get();
        transition(cmd, finalImage, D3D12_RESOURCE_STATE_COPY_SOURCE, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
        transition(cmd, hudless, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
        const bool paired = sharedInputs && full;
        constexpr UINT PairedRgb24 = 4;
        const UINT layout[]{width, height, colorStride, static_cast<UINT>(colorPlaneBytes),
                            paired ? PairedRgb24 : static_cast<UINT>(transport)};
        ID3D12DescriptorHeap* heaps[]{decoder.views.Get()};
        cmd->SetDescriptorHeaps(1, heaps);
        cmd->SetPipelineState(decoder.pipeline.Get());
        cmd->SetComputeRootSignature(decoder.root.Get());
        cmd->SetComputeRoot32BitConstants(0, 5, layout, 0);
        cmd->SetComputeRootShaderResourceView(1, sourceFor(slot, Final)->GetGPUVirtualAddress());
        cmd->SetComputeRootDescriptorTable(2, input.colorUavs);
        const UINT groupWidth = paired ? 64 : 16;
        cmd->Dispatch((width + groupWidth - 1) / groupWidth, (height + 7) / 8, full && !paired ? 2 : 1);
        transition(cmd, finalImage, D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COPY_SOURCE);
        transition(cmd, hudless, D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
    }

    void unpackGeometry(ID3D12GraphicsCommandList* cmd, Slot& slot, PreparedInput& input) {
        for (auto plane : {Depth, Motion}) {
            transition(cmd, input.planes[plane].Get(), D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,
                       D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
        }
        constexpr UINT PackedGeometry = 5, DepthReprojection = 6;
        const UINT layout[]{width, height, footprints[Depth].Footprint.RowPitch,
                            static_cast<UINT>(footprints[Motion].Offset), slot.reprojection ? DepthReprojection : PackedGeometry};
        // Reuse the descriptor table and pipeline bound by unpackColor.
        cmd->SetComputeRoot32BitConstants(0, 5, layout, 0);
        if (slot.reprojection) cmd->SetComputeRoot32BitConstants(0, ReprojectionWords, slot.reprojection->data(), 8);
        cmd->SetComputeRootShaderResourceView(1, sourceFor(slot, Depth)->GetGPUVirtualAddress());
        cmd->Dispatch((width + 15) / 16, (height + 7) / 8, 1);
        for (auto plane : {Depth, Motion}) {
            transition(cmd, input.planes[plane].Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS,
                       D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        }
    }

    void copyPlane(ID3D12GraphicsCommandList* commands, ID3D12Resource* sourceBuffer,
                   ID3D12Resource* image, Plane plane, D3D12_RESOURCE_STATES state) {
        D3D12_TEXTURE_COPY_LOCATION src{};
        src.pResource = sourceBuffer;
        src.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
        src.PlacedFootprint = footprints[plane];
        D3D12_TEXTURE_COPY_LOCATION dst{};
        dst.pResource = image;
        dst.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
        // COPY queue accesses promote from COMMON and decay after the command list executes.
        if (state != D3D12_RESOURCE_STATE_COMMON)
            transition(commands, image, state, D3D12_RESOURCE_STATE_COPY_DEST);
        commands->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);
        if (state != D3D12_RESOURCE_STATE_COMMON)
            transition(commands, image, D3D12_RESOURCE_STATE_COPY_DEST, state);
    }

    D3D12_RESOURCE_STATES inputState(Plane plane) const {
        if (plane == Final) return D3D12_RESOURCE_STATE_COPY_SOURCE;
        if (directGeometry && groupOf(plane) == Geometry) return D3D12_RESOURCE_STATE_COMMON;
        return D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE;
    }

    PreparedInput& inputsFor(UINT64 next) { return inputs[(next - 1) % InputCount]; }

    void prepareInputs(unsigned index, Slot& slot, UINT64 next, bool full) {
        auto& input = inputsFor(next);
        const auto token = transferToken(next, Color);
        waitForTransfer(token);
        if (input.preparedAfter && completed->GetCompletedValue() < input.preparedAfter)
            check(prepareQueue->Wait(completed.Get(), input.preparedAfter), "Waiting for previous XeSS-FG texture use");
        auto* cmd = slot.prepare.begin();
        const unsigned timing = slot.captureFrame > 0 ? profileProbes->mask : 0;
        if (transport != Rgba8) {
            if (timing & ColorUnpack) profileProbes->unpack.begin(cmd, index);
            unpackColor(cmd, slot, input, full);
            if (timing & ColorUnpack) profileProbes->unpack.end(cmd, index);
        }
        const unsigned fullPlaneCount = directGeometry || sharedInputs ? Depth : PlaneCount;
        const unsigned count = full ? fullPlaneCount : 1;
        const unsigned first = transport == Rgba8 ? Final : Depth;
        const bool decodeGeometry = sharedInputs && full;
        const bool copyTimed = (first < count || decodeGeometry) && (timing & LocalTextures);
        if (copyTimed) profileProbes->local.begin(cmd, index);
        if (decodeGeometry) unpackGeometry(cmd, slot, input);
        for (unsigned i = first; i < count; ++i) {
            const auto plane = static_cast<Plane>(i);
            copyPlane(cmd, sourceFor(slot, plane), input.planes[i].Get(), plane, inputState(plane));
        }
        if (copyTimed) {
            profileProbes->local.end(cmd, index);
            slot.recordedQueries |= LocalTextures;
        }
        slot.prepare.submit(prepareQueue.Get());
        releaseGl(token);
        input.preparedAfter = next;
        slot.recordedQueries |= timing & ColorUnpack;
        retireTransfer(slot, next);
    }

    void waitForPresentAllocator(UINT64 previous) {
        UINT64 finished = completed->GetCompletedValue();
        if (finished < previous) {
            check(completed->SetEventOnCompletion(previous, presentAllocatorReady.value), "Waiting for XeSS-FG present commands");
            waitWithMessages(presentAllocatorReady.value);
            finished = completed->GetCompletedValue();
        }
        if (finished == UINT64_MAX) throw std::runtime_error("XeSS-FG presentation device removed");
    }

    int present(const PresentJob& job, PresentProfile* profile = nullptr) {
        if (profile) profile->fields[ProxyTopLevel] = topLevel;
        if (topLevel && !positionPopup()) return 0;
        auto& local = inputsFor(job.sequence);
        const auto frame = job.frame;
        const auto interpolate = job.interpolate;
        const auto& input = job.constants;
        const auto colorBytes = interpolate ? groupBytes[Color] : colorPlaneBytes;
        if (profile) {
            profile->fields[CompletedBefore] = completed->GetCompletedValue();
            profile->fields[GeometryBytes] = job.geometryBytes;
            profile->fields[ColorBytes] = cross ? colorBytes : 0;
            profile->fields[CrossBytes] = profile->fields[ColorBytes] + job.geometryBytes;
            profile->fields[GpuMask] = 0;
            profile->split(ProfileSetup);
        }
        waitForPresentAllocator(local.submittedAfter);
        const auto token = transferToken(job.sequence, Color);
        check(presentQueue->Wait(prepared.Get(), job.sequence), "Acquiring prepared XeSS-FG textures");
        if (profile) profile->split(InputWaitEnqueue);

        if (interpolate != enabled) {
            sdk(xefgSwapChainSetEnabled(fg, interpolate), "Updating XeSS-FG enable state");
            enabled = interpolate;
            if (profile) profile->split(Toggle);
        }
        auto cmd = local.present.begin();
        const UINT backBufferIndex = swap->GetCurrentBackBufferIndex();
        if (profile) profile->fields[BackBufferIndex] = backBufferIndex;
        auto* backBuffer = backBuffers.at(backBufferIndex).Get();
        transition(cmd, backBuffer, D3D12_RESOURCE_STATE_PRESENT, D3D12_RESOURCE_STATE_COPY_DEST);
        cmd->CopyResource(backBuffer, local.planes[Final].Get());
        transition(cmd, backBuffer, D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_PRESENT);
        if (interpolate) {
            constexpr xefg_swapchain_resource_type_t types[]{XEFG_SWAPCHAIN_RES_HUDLESS_COLOR,
                XEFG_SWAPCHAIN_RES_DEPTH, XEFG_SWAPCHAIN_RES_MOTION_VECTOR};
            for (unsigned i = Hudless; i < PlaneCount; ++i) {
                xefg_swapchain_d3d12_resource_data_t data{};
                data.type = types[i - Hudless];
                data.validity = XEFG_SWAPCHAIN_RV_UNTIL_NEXT_PRESENT;
                data.resourceSize = {width, height};
                data.pResource = local.planes[i].Get();
                data.incomingState = inputState(static_cast<Plane>(i));
                sdk(xefgSwapChainD3D12TagFrameResource(fg, cmd, frame, &data), "Tagging XeSS-FG frame texture");
            }
            xefg_swapchain_frame_constant_data_t constants{};
            std::memcpy(constants.viewMatrix, input.view, sizeof(input.view));
            std::memcpy(constants.projectionMatrix, input.projection, sizeof(input.projection));
            constants.jitterOffsetX = input.jitterX;
            constants.jitterOffsetY = input.jitterY;
            constants.motionVectorScaleX = input.scaleX;
            constants.motionVectorScaleY = input.scaleY;
            constants.resetHistory = input.resetHistory;
            constants.frameRenderTime = input.frameMs;
            sdk(xefgSwapChainTagFrameConstants(fg, frame, &constants), "Tagging XeSS-FG frame constants");
        }
        local.present.submit(presentQueue.Get());
        local.submittedAfter = job.sequence;
        if (profile) profile->split(ArcSubmit);
        sdk(xefgSwapChainSetPresentId(fg, frame), "Selecting XeSS-FG present ID");
        if (!surfaceShown) {
            if (!SetWindowPos(surface, HWND_TOP, 0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW))
                check(HRESULT_FROM_WIN32(GetLastError()), "Showing XeSS-FG presentation surface");
            surfaceShown = true;
        }
        // Interval zero allows a newer flip to replace a pending generated frame.
        const bool syncPresent = job.vsync || interpolate;
        const UINT syncInterval = syncPresent ? 1 : 0;
        const UINT presentFlags = !syncPresent && tearing ? DXGI_PRESENT_ALLOW_TEARING : 0;
        if (profile) {
            profile->fields[SyncInterval] = syncInterval;
            profile->fields[PresentFlags] = presentFlags;
            profile->fields[ProxySwapChainAddress] = reinterpret_cast<intptr_t>(swap.Get());
            profile->fields[QpcFrequency] = profile->frequency;
            profile->fields[ColorFenceToken] = token;
            if (job.colorSubmitQpc != Unavailable) {
                profile->fields[ColorSubmitQpc] = job.colorSubmitQpc;
                profile->fields[GlFenceAtSubmit] = job.glFenceAtSubmit;
            }
            profile->fields[GlFenceAtEnter] = glWritten->GetCompletedValue();
            if (cross) sampleColorTransferFence(*profile, TransferFenceAtEnter, token);
        }
        {
            std::lock_guard guard(xellMutex);
            recordMarker(ll, frame, XELL_RENDERSUBMIT_END);
            recordMarker(ll, frame, XELL_PRESENT_START);
        }
        int64_t cpuStart = Unavailable;
        if (profile) {
            profile->fields[PresenterThreadId] = GetCurrentThreadId();
            TraceLoggingWrite(presentTrace, "ProxyPresentStart",
                TraceLoggingUInt32(frame, "PresentId"), TraceLoggingInt64(job.captureFrame, "CaptureFrame"));
            cpuStart = threadCpuTime();
            profile->split(BeforePresent);
            profile->fields[PresentEnterQpc] = profile->previous;
        }
        const HRESULT status = swap->Present(syncInterval, presentFlags);
        if (profile) {
            profile->split(DxgiPresent);
            profile->fields[PresentExitQpc] = profile->previous;
            const auto cpuEnd = threadCpuTime();
            if (cpuStart != Unavailable && cpuEnd != Unavailable && cpuEnd >= cpuStart)
                profile->fields[ProxyPresentCpuTime] = cpuEnd - cpuStart;
            TraceLoggingWrite(presentTrace, "ProxyPresentStop",
                TraceLoggingUInt32(frame, "PresentId"), TraceLoggingInt64(job.captureFrame, "CaptureFrame"),
                TraceLoggingInt64(profile->fields[PresentEnterQpc], "EnterQpc"),
                TraceLoggingInt64(profile->fields[PresentExitQpc], "ExitQpc"),
                TraceLoggingInt64(profile->fields[ProxyPresentCpuTime], "ThreadCpuNs"),
                TraceLoggingHResult(status, "Result"));
            profile->fields[DxgiResult] = status;
            profile->fields[GlFenceAtExit] = glWritten->GetCompletedValue();
            if (cross) sampleColorTransferFence(*profile, TransferFenceAtExit, token);
        }
        {
            std::lock_guard guard(xellMutex);
            xellAddMarkerData(ll, frame, XELL_PRESENT_END);
        }
        if (profile) {
            profile->fields[GpuMask] = job.recordedQueries;
            profile->fields[CompletedAfter] = completed->GetCompletedValue();
            profile->split(Retire);
        }
        check(status, "Presenting through XeSS-FG");
        if (status == DXGI_STATUS_OCCLUDED) return 0;
        if (!interpolate) return 1;
        xefg_swapchain_present_status_t presentStatus{};
        const auto sdkResult = xefgSwapChainGetLastPresentStatus(fg, &presentStatus);
        if (profile) {
            profile->split(ReadStatus);
            profile->fields[SdkStatus] = sdkResult;
            if (sdkResult >= 0) {
                profile->fields[FrameGenResult] = presentStatus.frameGenResult;
                profile->fields[FgEnabled] = presentStatus.isFrameGenEnabled;
            }
        }
        if (sdkResult < 0) return 1;
        return static_cast<int>(presentStatus.framesPresented);
    }
};

Session& session(jlong pointer) { return *reinterpret_cast<Session*>(pointer); }

void report(JNIEnv* env, const std::exception& error) {
    if (auto type = env->FindClass("java/lang/IllegalStateException")) env->ThrowNew(type, error.what());
}

void* directBuffer(JNIEnv* env, jobject buffer, size_t bytes) {
    const auto address = env->GetDirectBufferAddress(buffer);
    if (!address || env->GetDirectBufferCapacity(buffer) < static_cast<jlong>(bytes))
        throw std::invalid_argument("Invalid XeSS-FG direct buffer");
    return address;
}
}

#define JAVA(name) Java_com_iridium126_createmanaindustry_client_xessfg_XessFgNative_##name
extern "C" {
JNIEXPORT jint JNICALL JAVA(adapter)(JNIEnv*, jclass, jlong id) {
    try {
        ComPtr<IDXGIFactory4> factory;
        check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)), "Creating DXGI factory");
        DXGI_ADAPTER_DESC1 desc{};
        auto gpu = device(factory.Get(), id, &desc);
        xefg_swapchain_handle_t fg = nullptr;
        if (xefgSwapChainD3D12CreateContext(gpu.Get(), &fg) != XEFG_SWAPCHAIN_RESULT_SUCCESS) return 0;
        xefgSwapChainDestroy(fg);
        xell_context_handle_t ll = nullptr;
        if (xellD3D12CreateContext(gpu.Get(), &ll) != XELL_RESULT_SUCCESS) return 0;
        xellDestroyContext(ll);
        return static_cast<jint>(desc.VendorId << 16 | desc.DeviceId);
    } catch (const std::exception&) {
        return 0;
    }
}

JNIEXPORT jlong JNICALL JAVA(create)(JNIEnv* env, jclass, jlong hwnd, jlong render, jlong generate, jint w, jint h,
                                    jint transport, jboolean topLevel) {
    try {
        if (!hwnd || !render || !generate || w <= 0 || h <= 0 || transport < Rgba8 || transport > Ycbcr422)
            throw std::invalid_argument("Invalid XeSS-FG session configuration");
        auto owner = std::make_unique<Session>();
        const auto mode = static_cast<Transport>(transport);
        owner->start(reinterpret_cast<HWND>(hwnd), render, generate, w, h, mode, topLevel != 0, mode != Rgba8, true);
        return reinterpret_cast<jlong>(owner.release());
    } catch (const std::exception& error) { report(env, error); return 0; }
}

JNIEXPORT jlongArray JNICALL JAVA(exportGroup)(JNIEnv* env, jclass, jlong ptr, jint index, jint groupIndex) {
    try {
        auto& s = session(ptr);
        const auto& transfer = s.slots.at(index).groups.at(groupIndex);
        const auto first = firstPlane(static_cast<Group>(groupIndex));
        Handle memory, producer, consumer;
        auto gpu = s.renderGpu.Get();
        check(gpu->CreateSharedHandle(transfer.glBuffer.Get(), nullptr, GENERIC_ALL, nullptr, &memory.value),
              "Exporting OpenGL frame buffer");
        check(gpu->CreateSharedHandle(s.glWritten.Get(), nullptr, GENERIC_ALL, nullptr, &producer.value),
              "Exporting OpenGL producer fence");
        check(gpu->CreateSharedHandle(s.glReleased.Get(), nullptr, GENERIC_ALL, nullptr, &consumer.value),
              "Exporting OpenGL consumer fence");
        constexpr size_t fields = 9;
        jlongArray array = env->NewLongArray(fields);
        if (!array) return nullptr;
        auto desc = transfer.glBuffer->GetDesc();
        auto allocation = gpu->GetResourceAllocationInfo(0, 1, &desc);
        std::array<jlong, fields> out{reinterpret_cast<jlong>(memory.value), reinterpret_cast<jlong>(producer.value),
            reinterpret_cast<jlong>(consumer.value), static_cast<jlong>(allocation.SizeInBytes),
            static_cast<jlong>(desc.Width), groupIndex == Color ? s.colorStride : s.footprints[first].Footprint.RowPitch,
            static_cast<jlong>(groupIndex == Color ? s.colorPlaneBytes : s.footprints[first + 1].Offset),
            groupIndex == Color ? s.transport : Rgba8, s.sharedInputs};
        env->SetLongArrayRegion(array, 0, fields, out.data());
        if (env->ExceptionCheck()) return nullptr;
        memory.detach();
        producer.detach();
        consumer.detach();
        return array;
    } catch (const std::exception& error) { report(env, error); return nullptr; }
}

JNIEXPORT void JNICALL JAVA(closeHandle)(JNIEnv*, jclass, jlong handle) {
    if (handle) CloseHandle(reinterpret_cast<HANDLE>(handle));
}

JNIEXPORT jint JNICALL JAVA(acquire)(JNIEnv* env, jclass, jlong ptr, jlong sequence, jlong captureFrame) {
    try { return session(ptr).acquire(sequence, captureFrame); }
    catch (const std::exception& error) { report(env, error); return -1; }
}

JNIEXPORT jint JNICALL JAVA(slotsInUse)(JNIEnv* env, jclass, jlong ptr) {
    try { return session(ptr).slotsInUse(); }
    catch (const std::exception& error) { report(env, error); return 0; }
}

JNIEXPORT void JNICALL JAVA(waitForSlot)(JNIEnv* env, jclass, jlong ptr) {
    try { session(ptr).waitForSlot(); }
    catch (const std::exception& error) { report(env, error); }
}

JNIEXPORT void JNICALL JAVA(submitGeometry)(JNIEnv* env, jclass, jlong ptr, jint index, jlong sequence, jobject constants) {
    try {
        Reprojection reprojection{};
        if (constants) {
            const void* data = env->GetDirectBufferAddress(constants);
            if (!data || env->GetDirectBufferCapacity(constants) < sizeof(reprojection))
                throw std::invalid_argument("Invalid XeSS-FG reprojection constants");
            std::memcpy(reprojection.data(), data, sizeof(reprojection));
        }
        session(ptr).submitGeometry(index, sequence, constants ? &reprojection : nullptr);
    }
    catch (const std::exception& error) { report(env, error); }
}

JNIEXPORT void JNICALL JAVA(submitColor)(JNIEnv* env, jclass, jlong ptr, jint index, jlong sequence, jboolean full) {
    try { session(ptr).submitColor(index, sequence, full); }
    catch (const std::exception& error) { report(env, error); }
}

JNIEXPORT jboolean JNICALL JAVA(discard)(JNIEnv* env, jclass, jlong ptr, jint index, jlong sequence) {
    try { return session(ptr).enqueue({static_cast<unsigned>(index), 0, static_cast<UINT64>(sequence),
                                       false, false, true, {}, 0, 0}); }
    catch (const std::exception& error) { report(env, error); return false; }
}

JNIEXPORT jint JNICALL JAVA(sleep)(JNIEnv* env, jclass, jlong ptr, jint frame) {
    try {
        auto& s = session(ptr);
        std::lock_guard guard(s.xellMutex);
        const auto result = xellSleep(s.ll, frame);
        // XeLL sleep only predicts a CPU wait. Continue submitting frame markers if the driver rejects the wait.
        if (result != XELL_RESULT_ERROR_INVALID_ARGUMENT) checkXeLL(result, "xellSleep", frame);
        return static_cast<jint>(result);
    } catch (const std::exception& error) { report(env, error); return 0; }
}

JNIEXPORT void JNICALL JAVA(marker)(JNIEnv* env, jclass, jlong ptr, jint frame, jint marker) {
    try {
        auto& s = session(ptr);
        std::lock_guard guard(s.xellMutex);
        recordMarker(s.ll, frame, marker);
    } catch (const std::exception& error) { report(env, error); }
}

JNIEXPORT jboolean JNICALL JAVA(enqueuePresent)(JNIEnv* env, jclass, jlong ptr, jint index, jint frame, jlong sequence,
        jboolean interpolate, jboolean vsync, jobject constants, jlong captureFrame) {
    try {
        PresentJob job{static_cast<unsigned>(index), static_cast<uint32_t>(frame), static_cast<UINT64>(sequence),
                       interpolate != 0, vsync != 0, false, {}, captureFrame, 0};
        if (interpolate) std::memcpy(&job.constants, directBuffer(env, constants, sizeof(Constants)), sizeof(Constants));
        return session(ptr).enqueue(job);
    } catch (const std::exception& error) { report(env, error); return false; }
}

JNIEXPORT void JNICALL JAVA(waitForPresentSpace)(JNIEnv* env, jclass, jlong ptr) {
    try {
        auto& s = session(ptr);
        if (MsgWaitForMultipleObjectsEx(1, &s.queueSpace.value, 4, QS_ALLINPUT, MWMO_INPUTAVAILABLE) == WAIT_FAILED)
            throw std::runtime_error("Waiting for XeSS-FG presenter slot");
        s.checkPresenter();
        check(s.renderGpu->GetDeviceRemovedReason(), "Checking XeSS-FG producer during present queue wait");
    } catch (const std::exception& error) { report(env, error); }
}

JNIEXPORT jint JNICALL JAVA(pollPresents)(JNIEnv* env, jclass, jlong ptr, jobject output) {
    try {
        return session(ptr).pollPresents(static_cast<int64_t*>(directBuffer(env, output,
            ResultCapacity * PresentResultFields * sizeof(int64_t))));
    } catch (const std::exception& error) { report(env, error); return 0; }
}

JNIEXPORT jint JNICALL JAVA(pollGpuProfile)(JNIEnv* env, jclass, jlong ptr, jobject output) {
    try {
        return session(ptr).pollProfile(static_cast<int64_t*>(directBuffer(env, output, SlotCount * GpuProfileFields * sizeof(int64_t))));
    } catch (const std::exception& error) { report(env, error); return 0; }
}

JNIEXPORT void JNICALL JAVA(sampleClocks)(JNIEnv* env, jclass, jlong ptr, jobject output) {
    try {
        session(ptr).sampleClocks(static_cast<int64_t*>(directBuffer(env, output, ClockProfileFields * sizeof(int64_t))));
    } catch (const std::exception& error) { report(env, error); }
}

JNIEXPORT void JNICALL JAVA(destroy)(JNIEnv*, jclass, jlong pointer) {
    delete reinterpret_cast<Session*>(pointer);
}
}
