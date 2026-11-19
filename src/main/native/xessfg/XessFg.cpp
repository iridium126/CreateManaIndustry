#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <jni.h>
#include <xell/xell_d3d12.h>
#include <xess_fg/xefg_swapchain_d3d12.h>
#include <array>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <memory>
#include <limits>
#include <stdexcept>

namespace {
using Microsoft::WRL::ComPtr;
constexpr unsigned SlotCount = 4;
constexpr unsigned BackBufferCount = 3;
constexpr UINT64 DrainValue = UINT64_MAX - 1;
enum Group { Geometry, Color, GroupCount };
enum Plane { Final, Hudless, Depth, Motion, PlaneCount };
constexpr std::array<DXGI_FORMAT, PlaneCount> Formats{
    DXGI_FORMAT_R8G8B8A8_UNORM, DXGI_FORMAT_R8G8B8A8_UNORM, DXGI_FORMAT_R32_FLOAT, DXGI_FORMAT_R16G16_FLOAT
};

constexpr Plane firstPlane(Group group) { return group == Geometry ? Depth : Final; }
constexpr Group groupOf(Plane plane) { return plane >= Depth ? Geometry : Color; }
constexpr UINT64 transferToken(UINT64 sequence, Group group) { return sequence * GroupCount + group; }
constexpr unsigned timestampIndex(unsigned slot, Group group) { return slot * GroupCount + group; }


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
    std::array<ComPtr<ID3D12Resource>, PlaneCount> planes;
    Commands present;
    UINT64 reuseAfter = 0;
    UINT64 profileSequence = 0;
    int64_t captureFrame = 0, profileFrame = 0;
    int64_t colorSubmitQpc = 0;
    UINT64 glFenceAtSubmit = 0;
    unsigned recordedQueries = 0, pendingQueries = 0;
    bool geometrySubmitted = false, colorSubmitted = false, fullColor = false;
};

constexpr unsigned ProducerGeometry = 1, ProducerColor = 2, UploadGeometry = 4, UploadColor = 8, LocalTextures = 16;
constexpr unsigned GpuProfileFields = 15;
constexpr unsigned ClockProfileFields = 10;

constexpr int64_t Unavailable = std::numeric_limits<int64_t>::min();
enum ProfileField {
    InputWaitEnqueue, Toggle, ArcSubmit, BeforePresent, DxgiPresent, Retire, ReadStatus, CrossBytes,
    DxgiResult, SdkStatus, FrameGenResult, FgEnabled, CompletedBefore, CompletedAfter, GpuMask, ProfileSetup,
    BackBufferIndex, SyncInterval, PresentFlags, GeometryBytes, ColorBytes,
    PresentEnterQpc, PresentExitQpc, ProxySwapChainAddress, QpcFrequency,
    ColorSubmitQpc, ColorFenceToken, GlFenceAtSubmit, GlFenceAtEnter, GlFenceAtExit,
    TransferFenceAtEnter, TransferFenceAtExit, ArcColorFenceObservedQpc, ProfileCount
};

int64_t qpc() {
    LARGE_INTEGER value{};
    QueryPerformanceCounter(&value);
    return value.QuadPart;
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
    TimestampProbe producer, upload, local;
    unsigned mask = 0;
};

struct Constants {
    float view[16], projection[16], jitterX, jitterY, scaleX, scaleY;
    uint32_t resetHistory;
    float frameMs;
};
static_assert(sizeof(Constants) == 152);

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

HWND createPresentationSurface(HWND parent) {
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
    if (!GetClientRect(parent, &client))
        check(HRESULT_FROM_WIN32(GetLastError()), "Reading Minecraft window client dimensions");
    auto child = CreateWindowExW(WS_EX_NOACTIVATE | WS_EX_NOPARENTNOTIFY, name, L"", WS_CHILD | WS_CLIPSIBLINGS,
        0, 0, client.right - client.left, client.bottom - client.top, parent, nullptr, application, nullptr);
    if (!child) check(HRESULT_FROM_WIN32(GetLastError()), "Creating XeSS-FG presentation surface");
    return child;
}

struct Session {
    UINT width = 0, height = 0;
    UINT64 sequence = 0, slotWaitSequence = 0;
    std::array<UINT64, GroupCount> groupBytes{};
    int activeSlot = -1;
    unsigned cursor = 0;
    bool cross = false, tearing = false, enabled = false;
    HWND surface = nullptr;
    bool surfaceShown = false;
    std::array<D3D12_PLACED_SUBRESOURCE_FOOTPRINT, PlaneCount> footprints{};
    std::array<Slot, SlotCount> slots;
    ComPtr<ID3D12Device> renderGpu, generationGpu;
    ComPtr<ID3D12CommandQueue> copyQueue, uploadQueue, presentQueue;
    ComPtr<ID3D12Fence> glWritten, glReleased, transferOut, transferIn, uploadReady, completed;
    ComPtr<IDXGISwapChain4> swap;
    std::unique_ptr<TimestampProbes> profileProbes;
    std::array<ComPtr<ID3D12Resource>, BackBufferCount> backBuffers;
    Handle slotReady;
    xefg_swapchain_handle_t fg = nullptr;
    xell_context_handle_t ll = nullptr;

    ~Session() {
        hideSurface();
        if (cross && transferIn && FAILED(renderGpu->GetDeviceRemovedReason())) {
            // Allow the surviving device to drain submissions waiting on the removed peer.
            transferIn->Signal(DrainValue);
        }
        drain(presentQueue.Get(), completed.Get());
        drain(uploadQueue.Get(), uploadReady.Get());
        drain(copyQueue.Get(), glReleased.Get());
        for (auto& image : backBuffers) image.Reset();
        swap.Reset();
        if (fg) xefgSwapChainDestroy(fg);
        if (ll) xellDestroyContext(ll);
        if (IsWindow(surface)) DestroyWindow(surface);
    }

    void hideSurface() {
        if (surfaceShown && IsWindow(surface)) ShowWindow(surface, SW_HIDE);
        surfaceShown = false;
    }

    void drain(ID3D12CommandQueue* q, ID3D12Fence* f) {
        if (!q || !f || sequence == 0 || f->GetCompletedValue() == UINT64_MAX) return;
        Handle retired;
        retired.value = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (!retired.value) return;
        if (SUCCEEDED(q->Signal(f, DrainValue)) && SUCCEEDED(f->SetEventOnCompletion(DrainValue, retired.value)))
            WaitForSingleObject(retired.value, 3000);
    }

    void initialize(HWND hwnd, jlong render, jlong generate, UINT w, UINT h) {
        width = w;
        height = h;
        cross = render != generate;
        slotReady.value = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (!slotReady.value) throw std::runtime_error("Creating XeSS-FG fence events");
        ComPtr<IDXGIFactory4> factory;
        check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)), "Creating XeSS-FG DXGI factory");
        renderGpu = device(factory.Get(), render);
        generationGpu = cross ? device(factory.Get(), generate) : renderGpu;
        presentQueue = queue(generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_DIRECT);
        check(renderGpu->CreateFence(0, D3D12_FENCE_FLAG_SHARED, IID_PPV_ARGS(&glWritten)), "Creating OpenGL producer fence");
        check(renderGpu->CreateFence(0, D3D12_FENCE_FLAG_SHARED, IID_PPV_ARGS(&glReleased)), "Creating OpenGL release fence");
        check(generationGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&completed)), "Creating presentation completion fence");
        if (cross) {
            copyQueue = queue(renderGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
            uploadQueue = queue(generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
            check(generationGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&uploadReady)),
                  "Creating generation GPU upload fence");
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
        for (auto& slot : slots) allocate(slot);

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
        // Give the proxy its own child surface while GLFW retains input and window ownership.
        surface = createPresentationSurface(hwnd);
        sdk(xefgSwapChainD3D12InitFromSwapChainDesc(fg, surface, &desc, nullptr, presentQueue.Get(), factory.Get(), &params),
            "Creating Minecraft's XeSS-FG proxy swap chain");
        sdk(xefgSwapChainD3D12GetSwapChainPtr(fg, IID_PPV_ARGS(&swap)), "Opening XeSS-FG proxy swap chain");
        for (UINT i = 0; i < BackBufferCount; ++i)
            check(swap->GetBuffer(i, IID_PPV_ARGS(&backBuffers[i])), "Opening XeSS-FG proxy back buffer");
        sdk(xefgSwapChainSetUiCompositionState(fg, XEFG_SWAPCHAIN_UI_COMPOSITION_STATE_ENABLED),
            "Enabling XeSS-FG HUD composition");
        sdk(xefgSwapChainSetEnabled(fg, 0), "Initializing XeSS-FG in passthrough mode");
    }

    void allocate(Slot& slot) {
        auto memory = defaultMemory();
        slot.present.create(generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_DIRECT);
        for (unsigned i = 0; i < GroupCount; ++i) {
            auto& transfer = slot.groups[i];
            auto storage = buffer(groupBytes[i]);
            storage.Flags = D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;
            check(renderGpu->CreateCommittedResource(&memory, D3D12_HEAP_FLAG_SHARED, &storage,
                  D3D12_RESOURCE_STATE_COMMON, nullptr, IID_PPV_ARGS(&transfer.glBuffer)),
                  "Allocating shared OpenGL frame storage");
            if (!cross) continue;
            transfer.producer.create(renderGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
            transfer.upload.create(generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
            storage.Flags = D3D12_RESOURCE_FLAG_NONE;
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
        for (unsigned i = Hudless; i < PlaneCount; ++i) {
            auto desc = texture(Formats[i], width, height);
            check(generationGpu->CreateCommittedResource(&memory, D3D12_HEAP_FLAG_NONE, &desc,
                  D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE, nullptr, IID_PPV_ARGS(&slot.planes[i])),
                  "Allocating local XeSS-FG input");
        }
    }

    UINT64 completedSequence() {
        auto value = completed->GetCompletedValue();
        if (value == UINT64_MAX) throw std::runtime_error("XeSS-FG device removed");
        return value;
    }

    int slotsInUse() {
        const auto finished = completedSequence();
        int count = 0;
        for (const auto& slot : slots) count += slot.reuseAfter > finished;
        return count;
    }

    int acquire(UINT64 next, int64_t captureFrame) {
        if (activeSlot >= 0 || next <= sequence) throw std::logic_error("XeSS-FG frame acquisition out of order");
        const auto finished = completedSequence();
        for (unsigned i = 0; i < SlotCount; ++i) {
            const unsigned index = (cursor + i) % SlotCount;
            auto& slot = slots[index];
            if (finished < slot.reuseAfter) continue;
            if (captureFrame > 0) prepareProfile();
            slot.captureFrame = slot.pendingQueries ? 0 : captureFrame;
            slot.recordedQueries = 0;
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
        if (slotsInUse() < SlotCount) return;
        UINT64 oldest = slots.front().reuseAfter;
        for (const auto& slot : slots) if (slot.reuseAfter < oldest) oldest = slot.reuseAfter;
        if (slotWaitSequence != oldest) {
            if (!ResetEvent(slotReady.value)) throw std::runtime_error("Resetting XeSS-FG slot fence event");
            check(completed->SetEventOnCompletion(oldest, slotReady.value), "Waiting for earliest XeSS-FG frame slot");
            slotWaitSequence = oldest;
        }
        // Give GLFW time to handle e.g. resize or iconify if the GPU is still busy.
        if (MsgWaitForMultipleObjectsEx(1, &slotReady.value, 4, QS_ALLINPUT, MWMO_INPUTAVAILABLE) == WAIT_FAILED)
            throw std::runtime_error("Waiting for XeSS-FG slot or window event");
        check(renderGpu->GetDeviceRemovedReason(), "Checking XeSS-FG producer during slot wait");
    }

    void retire(Slot& slot) {
        check(presentQueue->Signal(completed.Get(), sequence), "Retiring XeSS-FG frame slot");
        slot.reuseAfter = sequence;
        if (slot.recordedQueries) {
            slot.profileFrame = slot.captureFrame;
            slot.profileSequence = sequence;
            slot.pendingQueries = slot.recordedQueries;
        }
        activeSlot = -1;
    }

    void discard(unsigned index, UINT64 next) {
        auto& slot = active(index, next);
        if (slot.colorSubmitted || slot.geometrySubmitted) {
            const auto token = transferToken(next, slot.colorSubmitted ? Color : Geometry);
            waitForTransfer(token);
            releaseGl(token);
        }
        retire(slot);
    }

    static bool copyTimestamps(TimestampProbe& probe, ID3D12Device* gpu, ID3D12CommandQueue* queue) {
        D3D12_FEATURE_DATA_D3D12_OPTIONS3 options{};
        return SUCCEEDED(gpu->CheckFeatureSupport(D3D12_FEATURE_D3D12_OPTIONS3, &options, sizeof(options)))
                && options.CopyQueueTimestampQueriesSupported
                && probe.initialize(gpu, queue, D3D12_QUERY_HEAP_TYPE_COPY_QUEUE_TIMESTAMP, SlotCount * GroupCount);
    }

    void prepareProfile() {
        if (profileProbes) return;
        profileProbes = std::make_unique<TimestampProbes>();
        if (cross && copyTimestamps(profileProbes->producer, renderGpu.Get(), copyQueue.Get()))
            profileProbes->mask |= ProducerGeometry | ProducerColor;
        if (cross && copyTimestamps(profileProbes->upload, generationGpu.Get(), uploadQueue.Get()))
            profileProbes->mask |= UploadGeometry | UploadColor;
        if (profileProbes->local.initialize(generationGpu.Get(), presentQueue.Get(), D3D12_QUERY_HEAP_TYPE_TIMESTAMP))
            profileProbes->mask |= LocalTextures;
    }

    int pollProfile(int64_t* output) {
        if (!profileProbes) return 0;
        const UINT64 finished = completed->GetCompletedValue();
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
            *output++ = mask & LocalTextures ? profileProbes->local.read(i, &inputsEnd) : Unavailable;
            *output++ = dmaEnd;
            *output++ = inputsEnd;
            *output++ = rtxStart;
            *output++ = rtxEnd;
            *output++ = dmaStart;
            *output++ = rtxGeometryStart;
            *output++ = rtxGeometryEnd;
            *output++ = arcGeometryStart;
            *output++ = arcGeometryEnd;
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
        clockAnchor(presentQueue.Get(), values.data() + 4);
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

        auto upload = transfer.upload.begin();
        if (timedUpload) profileProbes->upload.begin(upload, query);
        upload->CopyBufferRegion(transfer.local.Get(), 0, transfer.destination.Get(), 0, size);
        if (timedUpload) profileProbes->upload.end(upload, query);
        check(uploadQueue->Wait(transferIn.Get(), token), "Acquiring cross-adapter frame input");
        transfer.upload.submit(uploadQueue.Get());
        check(uploadQueue->Signal(uploadReady.Get(), token), "Publishing generation GPU input");
        if (timedProducer) slot.recordedQueries |= producerBit;
        if (timedUpload) slot.recordedQueries |= uploadBit;
    }

    void submitGeometry(unsigned index, UINT64 next) {
        auto& slot = active(index, next);
        if (slot.geometrySubmitted || slot.colorSubmitted) throw std::logic_error("XeSS-FG geometry submitted out of order");
        submitTransfer(index, slot, Geometry, groupBytes[Geometry]);
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
        submitTransfer(index, slot, Color, full ? groupBytes[Color] : footprints[Hudless].Offset);
        slot.colorSubmitted = true;
        slot.fullColor = full;
    }

    void waitForTransfer(UINT64 token) {
        check(presentQueue->Wait(cross ? uploadReady.Get() : glWritten.Get(), token), "Acquiring XeSS-FG frame inputs");
    }

    void releaseGl(UINT64 token) {
        if (!cross) check(presentQueue->Signal(glReleased.Get(), token), "Releasing shared OpenGL frame storage");
    }

    ID3D12Resource* sourceFor(Slot& slot, Plane plane) {
        auto& transfer = slot.groups[groupOf(plane)];
        return cross ? transfer.local.Get() : transfer.glBuffer.Get();
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
        transition(commands, image, state, D3D12_RESOURCE_STATE_COPY_DEST);
        commands->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);
        transition(commands, image, D3D12_RESOURCE_STATE_COPY_DEST, state);
    }

    int present(unsigned index, uint32_t frame, UINT64 sequence, bool interpolate, bool vsync, const Constants& input,
                PresentProfile* profile = nullptr) {
        auto& slot = active(index, sequence);
        if (!slot.colorSubmitted || interpolate != slot.fullColor)
            throw std::logic_error("Missing XeSS-FG inputs for presentation");

        const auto colorBytes = interpolate ? groupBytes[Color] : footprints[Hudless].Offset;
        const bool timedLocal = slot.captureFrame > 0 && (profileProbes->mask & LocalTextures);
        if (profile) {
            const auto geometryBytes = cross && slot.geometrySubmitted ? groupBytes[Geometry] : 0;
            profile->fields[CompletedBefore] = completed->GetCompletedValue();
            profile->fields[GeometryBytes] = geometryBytes;
            profile->fields[ColorBytes] = cross ? colorBytes : 0;
            profile->fields[CrossBytes] = profile->fields[ColorBytes] + geometryBytes;
            profile->fields[GpuMask] = 0;
            profile->split(ProfileSetup);
        }
        const auto token = transferToken(sequence, Color);
        waitForTransfer(token);
        if (profile) profile->split(InputWaitEnqueue);

        if (interpolate != enabled) {
            sdk(xefgSwapChainSetEnabled(fg, interpolate), "Updating XeSS-FG enable state");
            enabled = interpolate;
            if (profile) profile->split(Toggle);
        }
        auto cmd = slot.present.begin();
        const UINT backBufferIndex = swap->GetCurrentBackBufferIndex();
        if (profile) profile->fields[BackBufferIndex] = backBufferIndex;
        if (timedLocal) profileProbes->local.begin(cmd, index);
        copyPlane(cmd, sourceFor(slot, Final), backBuffers.at(backBufferIndex).Get(), Final,
                  D3D12_RESOURCE_STATE_PRESENT);
        if (interpolate) {
            constexpr xefg_swapchain_resource_type_t types[]{XEFG_SWAPCHAIN_RES_HUDLESS_COLOR,
                XEFG_SWAPCHAIN_RES_DEPTH, XEFG_SWAPCHAIN_RES_MOTION_VECTOR};
            for (unsigned i = Hudless; i < PlaneCount; ++i) {
                auto image = slot.planes[i].Get();
                copyPlane(cmd, sourceFor(slot, static_cast<Plane>(i)), image, static_cast<Plane>(i), D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
                xefg_swapchain_d3d12_resource_data_t data{};
                data.type = types[i - Hudless];
                data.validity = XEFG_SWAPCHAIN_RV_UNTIL_NEXT_PRESENT;
                data.resourceSize = {width, height};
                data.pResource = image;
                data.incomingState = D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE;
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
        if (timedLocal) {
            profileProbes->local.end(cmd, index);
            slot.recordedQueries |= LocalTextures;
        }
        slot.present.submit(presentQueue.Get());
        releaseGl(token);
        if (profile) profile->split(ArcSubmit);
        sdk(xefgSwapChainSetPresentId(fg, frame), "Selecting XeSS-FG present ID");
        recordMarker(ll, frame, XELL_RENDERSUBMIT_END);
        recordMarker(ll, frame, XELL_PRESENT_START);
        if (!surfaceShown) {
            if (!SetWindowPos(surface, HWND_TOP, 0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW))
                check(HRESULT_FROM_WIN32(GetLastError()), "Showing XeSS-FG presentation surface");
            surfaceShown = true;
        }
        // Interval zero allows a newer flip to replace a pending generated frame.
        const bool syncPresent = vsync || interpolate;
        const UINT syncInterval = syncPresent ? 1 : 0;
        const UINT presentFlags = !syncPresent && tearing ? DXGI_PRESENT_ALLOW_TEARING : 0;
        if (profile) {
            profile->fields[SyncInterval] = syncInterval;
            profile->fields[PresentFlags] = presentFlags;
            profile->fields[ProxySwapChainAddress] = reinterpret_cast<intptr_t>(swap.Get());
            profile->fields[QpcFrequency] = profile->frequency;
            profile->fields[ColorFenceToken] = token;
            if (slot.captureFrame > 0) {
                profile->fields[ColorSubmitQpc] = slot.colorSubmitQpc;
                profile->fields[GlFenceAtSubmit] = slot.glFenceAtSubmit;
            }
            profile->fields[GlFenceAtEnter] = glWritten->GetCompletedValue();
            if (cross) sampleColorTransferFence(*profile, TransferFenceAtEnter, token);
            profile->split(BeforePresent);
            profile->fields[PresentEnterQpc] = profile->previous;
        }
        const HRESULT status = swap->Present(syncInterval, presentFlags);
        if (profile) {
            profile->split(DxgiPresent);
            profile->fields[PresentExitQpc] = profile->previous;
            profile->fields[DxgiResult] = status;
            profile->fields[GlFenceAtExit] = glWritten->GetCompletedValue();
            if (cross) sampleColorTransferFence(*profile, TransferFenceAtExit, token);
        }
        // Keep tagged input textures and command allocators alive through SDK queue submission.
        retire(slot);
        xellAddMarkerData(ll, frame, XELL_PRESENT_END);
        if (profile) {
            profile->fields[GpuMask] = slot.recordedQueries;
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

JNIEXPORT jlong JNICALL JAVA(create)(JNIEnv* env, jclass, jlong hwnd, jlong render, jlong generate, jint w, jint h) {
    try {
        if (!hwnd || !render || !generate || w <= 0 || h <= 0) throw std::invalid_argument("Invalid XeSS-FG surface");
        auto owner = std::make_unique<Session>();
        owner->initialize(reinterpret_cast<HWND>(hwnd), render, generate, w, h);
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
        constexpr size_t fields = 7;
        jlongArray array = env->NewLongArray(fields);
        if (!array) return nullptr;
        auto desc = transfer.glBuffer->GetDesc();
        auto allocation = gpu->GetResourceAllocationInfo(0, 1, &desc);
        std::array<jlong, fields> out{reinterpret_cast<jlong>(memory.value), reinterpret_cast<jlong>(producer.value),
            reinterpret_cast<jlong>(consumer.value), static_cast<jlong>(allocation.SizeInBytes),
            static_cast<jlong>(desc.Width), s.footprints[first].Footprint.RowPitch,
            static_cast<jlong>(s.footprints[first + 1].Offset)};
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

JNIEXPORT void JNICALL JAVA(submitGeometry)(JNIEnv* env, jclass, jlong ptr, jint index, jlong sequence) {
    try { session(ptr).submitGeometry(index, sequence); }
    catch (const std::exception& error) { report(env, error); }
}

JNIEXPORT void JNICALL JAVA(submitColor)(JNIEnv* env, jclass, jlong ptr, jint index, jlong sequence, jboolean full) {
    try { session(ptr).submitColor(index, sequence, full); }
    catch (const std::exception& error) { report(env, error); }
}

JNIEXPORT void JNICALL JAVA(discard)(JNIEnv* env, jclass, jlong ptr, jint index, jlong sequence) {
    try { session(ptr).discard(index, sequence); }
    catch (const std::exception& error) { report(env, error); }
}

JNIEXPORT jint JNICALL JAVA(sleep)(JNIEnv* env, jclass, jlong ptr, jint frame) {
    try {
        const auto result = xellSleep(session(ptr).ll, frame);
        // XeLL sleep only predicts a CPU wait. Continue submitting frame markers if the driver rejects the wait.
        if (result != XELL_RESULT_ERROR_INVALID_ARGUMENT) checkXeLL(result, "xellSleep", frame);
        return static_cast<jint>(result);
    } catch (const std::exception& error) { report(env, error); return 0; }
}

JNIEXPORT void JNICALL JAVA(marker)(JNIEnv* env, jclass, jlong ptr, jint frame, jint marker) {
    try {
        recordMarker(session(ptr).ll, frame, marker);
    } catch (const std::exception& error) { report(env, error); }
}

JNIEXPORT jint JNICALL JAVA(present)(JNIEnv* env, jclass, jlong ptr, jint index, jint frame, jlong sequence,
                                    jboolean interpolate, jboolean vsync, jobject constants) {
    try {
        Constants input;
        std::memcpy(&input, directBuffer(env, constants, sizeof(input)), sizeof(input));
        return session(ptr).present(index, frame, sequence, interpolate, vsync, input);
    } catch (const std::exception& error) { report(env, error); return 0; }
}

JNIEXPORT jint JNICALL JAVA(presentProfiled)(JNIEnv* env, jclass, jlong ptr, jint index, jint frame, jlong sequence,
        jboolean interpolate, jboolean vsync, jobject constants, jobject output) {
    try {
        PresentProfile profile;
        const auto data = directBuffer(env, output, sizeof(profile.fields));
        try {
            Constants input;
            std::memcpy(&input, directBuffer(env, constants, sizeof(input)), sizeof(input));
            int queued = session(ptr).present(index, frame, sequence, interpolate, vsync, input, &profile);
            std::memcpy(data, profile.fields.data(), sizeof(profile.fields));
            return queued;
        } catch (const std::exception&) {
            std::memcpy(data, profile.fields.data(), sizeof(profile.fields));
            throw;
        }
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
