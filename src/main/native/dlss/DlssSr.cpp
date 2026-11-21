#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <jni.h>
#include <nvsdk_ngx_helpers.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>

namespace {
using Microsoft::WRL::ComPtr;
constexpr size_t SlotCount = 3;
constexpr DXGI_FORMAT Formats[]{DXGI_FORMAT_R16G16B16A16_FLOAT, DXGI_FORMAT_R32_FLOAT,
                               DXGI_FORMAT_R16G16_FLOAT, DXGI_FORMAT_R16G16B16A16_FLOAT};
enum Plane { Color, Depth, Motion, Output, PlaneCount };
constexpr char ProjectId[] = "0fd48cb2-7604-4e92-b034-2c733ae02417";

void checkCode(bool ok, unsigned code, const char* operation) {
    if (ok) return;
    char message[192];
    std::snprintf(message, sizeof(message), "%s (0x%08X)", operation, code);
    throw std::runtime_error(message);
}

void check(HRESULT result, const char* operation) {
    checkCode(SUCCEEDED(result), static_cast<unsigned>(result), operation);
}

void ngx(NVSDK_NGX_Result result, const char* operation) {
    checkCode(NVSDK_NGX_SUCCEED(result), static_cast<unsigned>(result), operation);
}

std::wstring wideString(JNIEnv* env, jstring string) {
    const auto* chars = env->GetStringChars(string, nullptr);
    if (!chars) throw std::runtime_error("Reading NGX directory");
    std::wstring value(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(string));
    env->ReleaseStringChars(string, chars);
    return value;
}

void report(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck())
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}

struct Handle {
    HANDLE value = nullptr;
    ~Handle() { if (value) CloseHandle(value); }
    HANDLE release() {
        const auto result = value;
        value = nullptr;
        return result;
    }
};

struct Slot {
    std::array<ComPtr<ID3D12Resource>, PlaneCount> textures;
    ComPtr<ID3D12CommandAllocator> allocator;
    ComPtr<ID3D12GraphicsCommandList> commands;
    UINT64 pending = 0;
    bool timed = false;
};

class Session {
    ComPtr<ID3D12Device> gpu;
    ComPtr<ID3D12CommandQueue> queue;
    ComPtr<ID3D12Fence> published, evaluated;
    ComPtr<ID3D12QueryHeap> timestamps;
    ComPtr<ID3D12Resource> timingReadback;
    UINT64 timestampFrequency = 0;
    NVSDK_NGX_Parameter* parameters = nullptr;
    NVSDK_NGX_Handle* feature = nullptr;
    bool initialized = false;
    UINT64 submitted = 0;
    std::array<Slot, SlotCount> slots;
    UINT inputWidth, inputHeight, outputWidth, outputHeight;

    static auto adapter(jlong luid) {
        LUID adapterLuid{};
        std::memcpy(&adapterLuid, &luid, sizeof(adapterLuid));
        ComPtr<IDXGIFactory4> factory;
        check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)), "Creating DLSS DXGI factory");
        ComPtr<IDXGIAdapter1> result;
        check(factory->EnumAdapterByLuid(adapterLuid, IID_PPV_ARGS(&result)), "Finding the Minecraft OpenGL adapter");
        DXGI_ADAPTER_DESC1 desc{};
        check(result->GetDesc1(&desc), "Reading the DLSS adapter descriptor");
        if (desc.VendorId != 0x10de || (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE))
            throw std::runtime_error("DLSS requires an NVIDIA RTX OpenGL adapter");
        return result;
    }

    NVSDK_NGX_PerfQuality_Value quality() const {
        if (inputWidth == outputWidth && inputHeight == outputHeight)
            return NVSDK_NGX_PerfQuality_Value_DLAA;
        constexpr NVSDK_NGX_PerfQuality_Value modes[]{NVSDK_NGX_PerfQuality_Value_MaxQuality,
            NVSDK_NGX_PerfQuality_Value_Balanced, NVSDK_NGX_PerfQuality_Value_MaxPerf};
        double bestDistance = std::numeric_limits<double>::infinity();
        auto selected = modes[0];
        for (const auto mode : modes) {
            unsigned optimalW, optimalH, maxW, maxH, minW, minH;
            float sharpness;
            if (NVSDK_NGX_FAILED(NGX_DLSS_GET_OPTIMAL_SETTINGS(parameters, outputWidth, outputHeight,
                    mode, &optimalW, &optimalH, &maxW, &maxH, &minW, &minH, &sharpness))) continue;
            if (inputWidth < minW || inputWidth > maxW || inputHeight < minH || inputHeight > maxH) continue;
            const auto distance = std::hypot((double(inputWidth) - optimalW) / outputWidth,
                                             (double(inputHeight) - optimalH) / outputHeight);
            if (distance < bestDistance) {
                bestDistance = distance;
                selected = mode;
            }
        }
        if (!std::isfinite(bestDistance))
            throw std::runtime_error("Requested DLSS dimensions are outside the SDK's supported ranges");
        return selected;
    }

    auto allocate(Plane plane) const {
        D3D12_HEAP_PROPERTIES heap{};
        heap.Type = D3D12_HEAP_TYPE_DEFAULT;
        heap.CreationNodeMask = heap.VisibleNodeMask = 1;
        D3D12_RESOURCE_DESC desc{};
        desc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
        desc.Width = plane == Output ? outputWidth : inputWidth;
        desc.Height = plane == Output ? outputHeight : inputHeight;
        desc.DepthOrArraySize = desc.MipLevels = 1;
        desc.Format = Formats[plane];
        desc.SampleDesc.Count = 1;
        desc.Flags = D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS | D3D12_RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS;
        ComPtr<ID3D12Resource> texture;
        check(gpu->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_SHARED, &desc, D3D12_RESOURCE_STATE_COMMON,
                    nullptr, IID_PPV_ARGS(&texture)), "Allocating a shared DLSS texture");
        return texture;
    }

    static void transition(ID3D12GraphicsCommandList* cmd, ID3D12Resource* texture, D3D12_RESOURCE_STATES from,
                           D3D12_RESOURCE_STATES to) {
        D3D12_RESOURCE_BARRIER barrier{};
        barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
        barrier.Transition = {texture, D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES, from, to};
        cmd->ResourceBarrier(1, &barrier);
    }

    static D3D12_RESOURCE_STATES shaderState(size_t index) {
        return index == Output ? D3D12_RESOURCE_STATE_UNORDERED_ACCESS : D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE;
    }

    void waitForSetup(UINT64 token) {
        if (evaluated->GetCompletedValue() >= token) return;
        Handle event;
        event.value = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (!event.value) throw std::runtime_error("Creating the DLSS setup event");
        check(evaluated->SetEventOnCompletion(token, event.value), "Waiting for DLSS setup");
        if (WaitForSingleObject(event.value, 12000) != WAIT_OBJECT_0)
            throw std::runtime_error("Timed out warming up the DLSS feature");
        check(gpu->GetDeviceRemovedReason(), "Checking the DLSS device after initialization");
    }

public:
    Session(UINT width, UINT height, UINT outWidth, UINT outHeight)
        : inputWidth(width), inputHeight(height), outputWidth(outWidth), outputHeight(outHeight) {}

    void initialize(jlong luid, bool hdr, const std::wstring& runtimePath, const std::wstring& cachePath) {
        auto target = adapter(luid);
        check(D3D12CreateDevice(target.Get(), D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&gpu)), "Creating the RTX DLSS device");
        D3D12_COMMAND_QUEUE_DESC desc{};
        desc.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
        check(gpu->CreateCommandQueue(&desc, IID_PPV_ARGS(&queue)), "Creating the RTX DLSS command queue");
        check(gpu->CreateFence(0, D3D12_FENCE_FLAG_SHARED, IID_PPV_ARGS(&published)), "Creating the DLSS input fence");
        check(gpu->CreateFence(0, D3D12_FENCE_FLAG_SHARED, IID_PPV_ARGS(&evaluated)), "Creating the DLSS output fence");
        if (SUCCEEDED(queue->GetTimestampFrequency(&timestampFrequency)) && timestampFrequency) {
            D3D12_QUERY_HEAP_DESC query{D3D12_QUERY_HEAP_TYPE_TIMESTAMP, SlotCount * 2, 0};
            D3D12_HEAP_PROPERTIES heap{D3D12_HEAP_TYPE_READBACK, D3D12_CPU_PAGE_PROPERTY_UNKNOWN,
                                      D3D12_MEMORY_POOL_UNKNOWN, 1, 1};
            D3D12_RESOURCE_DESC buffer{};
            buffer.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
            buffer.Width = SlotCount * 2 * sizeof(UINT64);
            buffer.Height = buffer.DepthOrArraySize = buffer.MipLevels = buffer.SampleDesc.Count = 1;
            buffer.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
            if (FAILED(gpu->CreateQueryHeap(&query, IID_PPV_ARGS(&timestamps)))
                    || FAILED(gpu->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &buffer, D3D12_RESOURCE_STATE_COPY_DEST,
                              nullptr, IID_PPV_ARGS(&timingReadback)))) timestamps.Reset();
        }
        const wchar_t* searchPath = runtimePath.c_str();
        NVSDK_NGX_FeatureCommonInfo common{};
        common.PathListInfo = {&searchPath, 1};
        ngx(NVSDK_NGX_D3D12_Init_with_ProjectID(ProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM, "1.21.1",
                        cachePath.c_str(), gpu.Get(), &common), "Initializing NVIDIA NGX");
        initialized = true;
        ngx(NVSDK_NGX_D3D12_GetCapabilityParameters(&parameters), "Reading NVIDIA NGX capabilities");
        int available = 0;
        ngx(parameters->Get(NVSDK_NGX_Parameter_SuperSampling_Available, &available), "Checking DLSS Super Resolution");
        if (!available) throw std::runtime_error("DLSS Super Resolution is unavailable on the render adapter");
        for (auto& slot : slots) {
            check(gpu->CreateCommandAllocator(desc.Type, IID_PPV_ARGS(&slot.allocator)), "Allocating DLSS commands");
            check(gpu->CreateCommandList(0, desc.Type, slot.allocator.Get(), nullptr, IID_PPV_ARGS(&slot.commands)),
                  "Creating DLSS commands");
            for (unsigned i = 0; i < PlaneCount; ++i) slot.textures[i] = allocate(static_cast<Plane>(i));
        }

        NVSDK_NGX_DLSS_Create_Params params{};
        params.Feature = {inputWidth, inputHeight, outputWidth, outputHeight, quality()};
        params.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes;
        if (hdr) params.InFeatureCreateFlags |= NVSDK_NGX_DLSS_Feature_Flags_IsHDR | NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
        ngx(NGX_D3D12_CREATE_DLSS_EXT(slots[0].commands.Get(), 1, 1, &feature, parameters, &params),
            "Creating the DLSS Super Resolution feature");
        for (auto& slot : slots) check(slot.commands->Close(), "Closing DLSS setup commands");
        ID3D12CommandList* commands[]{slots[0].commands.Get()};
        queue->ExecuteCommandLists(1, commands);
        check(queue->Signal(evaluated.Get(), 1), "Submitting DLSS initialization");
        submitted = 1;
        waitForSetup(1);
    }

    ~Session() {
        if (submitted && gpu && SUCCEEDED(gpu->GetDeviceRemovedReason())) {
            try { waitForSetup(submitted); }
            catch (...) {}
        }
        if (feature) NVSDK_NGX_D3D12_ReleaseFeature(feature);
        if (parameters) NVSDK_NGX_D3D12_DestroyParameters(parameters);
        if (initialized) NVSDK_NGX_D3D12_Shutdown1(gpu.Get());
    }

    bool available(unsigned index) const {
        check(gpu->GetDeviceRemovedReason(), "Checking the RTX DLSS device");
        return evaluated->GetCompletedValue() >= slots.at(index).pending;
    }

    double pollGpuTime(unsigned index) {
        auto& slot = slots.at(index);
        if (!slot.timed || !available(index)) return -1;
        const size_t position = 2 * index * sizeof(UINT64);
        const D3D12_RANGE range{position, position + 2 * sizeof(UINT64)};
        void* mapped = nullptr;
        check(timingReadback->Map(0, &range, &mapped), "Reading the DLSS GPU timestamp");
        const auto* ticks = reinterpret_cast<const UINT64*>(static_cast<const char*>(mapped) + position);
        const auto ms = (ticks[1] - ticks[0]) * 1000.0 / timestampFrequency;
        const D3D12_RANGE noWrites{0, 0};
        timingReadback->Unmap(0, &noWrites);
        slot.timed = false;
        return ms;
    }

    void exportTextures(JNIEnv* env, unsigned index, jlongArray output) const {
        const auto& slot = slots.at(index);
        std::array<Handle, PlaneCount + 2> handles;
        std::array<jlong, PlaneCount * 2 + 2> result{};
        for (unsigned i = 0; i < PlaneCount; ++i) {
            auto* resource = slot.textures[i].Get();
            check(gpu->CreateSharedHandle(resource, nullptr, GENERIC_ALL, nullptr, &handles[i].value),
                  "Exporting a DLSS texture");
            const auto desc = resource->GetDesc();
            result[i] = reinterpret_cast<jlong>(handles[i].value);
            result[i + PlaneCount + 2] = gpu->GetResourceAllocationInfo(0, 1, &desc).SizeInBytes;
        }
        check(gpu->CreateSharedHandle(published.Get(), nullptr, GENERIC_ALL, nullptr, &handles[PlaneCount].value),
              "Exporting the DLSS input fence");
        check(gpu->CreateSharedHandle(evaluated.Get(), nullptr, GENERIC_ALL, nullptr, &handles[PlaneCount + 1].value),
              "Exporting the DLSS output fence");
        result[PlaneCount] = reinterpret_cast<jlong>(handles[PlaneCount].value);
        result[PlaneCount + 1] = reinterpret_cast<jlong>(handles[PlaneCount + 1].value);
        env->SetLongArrayRegion(output, 0, static_cast<jsize>(result.size()), result.data());
        if (!env->ExceptionCheck()) for (auto& handle : handles) handle.release();
    }

    void evaluate(unsigned index, UINT64 token, float jitterX, float jitterY, float elapsed, bool reset, bool profile) {
        auto& slot = slots.at(index);
        if (!available(index) || token <= submitted) throw std::logic_error("DLSS submission is out of order");
        auto* cmd = slot.commands.Get();
        check(slot.allocator->Reset(), "Reusing DLSS command storage");
        check(cmd->Reset(slot.allocator.Get(), nullptr), "Recording DLSS commands");
        for (unsigned i = 0; i < PlaneCount; ++i)
            transition(cmd, slot.textures[i].Get(), D3D12_RESOURCE_STATE_COMMON, shaderState(i));

        NVSDK_NGX_D3D12_DLSS_Eval_Params params{};
        params.Feature.pInColor = slot.textures[Color].Get();
        params.Feature.pInOutput = slot.textures[Output].Get();
        params.pInDepth = slot.textures[Depth].Get();
        params.pInMotionVectors = slot.textures[Motion].Get();
        params.InJitterOffsetX = jitterX;
        params.InJitterOffsetY = jitterY;
        params.InMVScaleX = params.InMVScaleY = 1;
        params.InRenderSubrectDimensions = {inputWidth, inputHeight};
        params.InFrameTimeDeltaInMsec = elapsed;
        params.InReset = reset ? 1 : 0;
        const bool timed = profile && timestamps;
        const unsigned query = 2 * index;
        if (timed) cmd->EndQuery(timestamps.Get(), D3D12_QUERY_TYPE_TIMESTAMP, query);
        const auto status = NGX_D3D12_EVALUATE_DLSS_EXT(cmd, feature, parameters, &params);
        if (timed) {
            cmd->EndQuery(timestamps.Get(), D3D12_QUERY_TYPE_TIMESTAMP, query + 1);
            cmd->ResolveQueryData(timestamps.Get(), D3D12_QUERY_TYPE_TIMESTAMP, query, 2, timingReadback.Get(),
                                  query * sizeof(UINT64));
        }
        for (unsigned i = 0; i < PlaneCount; ++i)
            transition(cmd, slot.textures[i].Get(), shaderState(i), D3D12_RESOURCE_STATE_COMMON);
        check(cmd->Close(), "Closing DLSS evaluation commands");
        ngx(status, "Recording DLSS evaluation");
        check(queue->Wait(published.Get(), token), "Acquiring the DLSS frame inputs");
        ID3D12CommandList* commands[]{cmd};
        queue->ExecuteCommandLists(1, commands);
        check(queue->Signal(evaluated.Get(), token), "Publishing the DLSS output");
        submitted = slot.pending = token;
        slot.timed = timed;
    }
};
}

#define JAVA(method) Java_com_iridium126_createmanaindustry_client_dlss_DlssNative_##method

extern "C" {
JNIEXPORT jlong JNICALL JAVA(create)(JNIEnv* env, jclass, jlong luid, jint width, jint height, jint outWidth, jint outHeight,
                                   jboolean hdr, jstring runtimePath, jstring cachePath) {
    try {
        constexpr auto limit = D3D12_REQ_TEXTURE2D_U_OR_V_DIMENSION;
        if (!luid || width < 1 || height < 1 || outWidth < width || outHeight < height
                || outWidth > limit || outHeight > limit)
            throw std::invalid_argument("Invalid DLSS adapter or dimensions");
        auto session = std::make_unique<Session>(width, height, outWidth, outHeight);
        session->initialize(luid, hdr, wideString(env, runtimePath), wideString(env, cachePath));
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& error) { report(env, error); return 0; }
}

JNIEXPORT void JNICALL JAVA(destroy)(JNIEnv*, jclass, jlong session) {
    delete reinterpret_cast<Session*>(session);
}

JNIEXPORT void JNICALL JAVA(exportTextures)(JNIEnv* env, jclass, jlong session, jint slot, jlongArray output) {
    try { reinterpret_cast<Session*>(session)->exportTextures(env, slot, output); }
    catch (const std::exception& error) { report(env, error); }
}

JNIEXPORT void JNICALL JAVA(closeHandle)(JNIEnv*, jclass, jlong handle) {
    if (handle) CloseHandle(reinterpret_cast<HANDLE>(handle));
}

JNIEXPORT jboolean JNICALL JAVA(available)(JNIEnv* env, jclass, jlong session, jint slot) {
    try { return reinterpret_cast<Session*>(session)->available(slot); }
    catch (const std::exception& error) { report(env, error); return false; }
}

JNIEXPORT void JNICALL JAVA(evaluate)(JNIEnv* env, jclass, jlong session, jint slot, jlong token,
                                      jfloat jitterX, jfloat jitterY, jfloat elapsed, jboolean reset, jboolean profile) {
    try { reinterpret_cast<Session*>(session)->evaluate(slot, token, jitterX, jitterY, elapsed, reset, profile); }
    catch (const std::exception& error) { report(env, error); }
}

JNIEXPORT jdouble JNICALL JAVA(pollGpuTime)(JNIEnv* env, jclass, jlong session, jint slot) {
    try { return reinterpret_cast<Session*>(session)->pollGpuTime(slot); }
    catch (const std::exception& error) { report(env, error); return -1; }
}
}
