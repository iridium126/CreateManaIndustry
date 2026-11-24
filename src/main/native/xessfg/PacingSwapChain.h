#pragma once

// The SDK-facing chain publishes textures to AsyncOutput. Only its worker
// presents the real flip-model chain.
#include "AsyncOutput.h"

#include <windows.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <new>
#include <atomic>
#include <cstdint>
#include <memory>
#include <stdexcept>

namespace cmi::xessfg::pacing {
using Microsoft::WRL::ComPtr;

class PacingFactory;

class PacingSwapChain final : public IDXGISwapChain4 {
public:
    // The initial reference transfers to the caller of the wrapped creation call.
    PacingSwapChain(ComPtr<IDXGISwapChain4>&& real, std::shared_ptr<AsyncOutput> output, PacingFactory* factory);

    ~PacingSwapChain();

    PacingSwapChain(const PacingSwapChain&) = delete;
    PacingSwapChain& operator=(const PacingSwapChain&) = delete;

    // IUnknown
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppvObject) override;

    ULONG STDMETHODCALLTYPE AddRef() override { return references_.fetch_add(1, std::memory_order_relaxed) + 1; }

    ULONG STDMETHODCALLTYPE Release() override {
        const ULONG previous = references_.fetch_sub(1, std::memory_order_relaxed);
        if (previous == 1) delete this;
        return previous - 1;
    }

    // IDXGIObject
    HRESULT STDMETHODCALLTYPE SetPrivateData(REFGUID name, UINT dataSize, const void* data) override {
        return real_->SetPrivateData(name, dataSize, data);
    }

    HRESULT STDMETHODCALLTYPE SetPrivateDataInterface(REFGUID name, const IUnknown* unknown) override {
        return real_->SetPrivateDataInterface(name, unknown);
    }

    HRESULT STDMETHODCALLTYPE GetPrivateData(REFGUID name, UINT* dataSize, void* data) override {
        return real_->GetPrivateData(name, dataSize, data);
    }

    HRESULT STDMETHODCALLTYPE GetParent(REFIID riid, void** ppvParent) override;

    // IDXGIDeviceSubObject
    HRESULT STDMETHODCALLTYPE GetDevice(REFIID riid, void** ppvDevice) override {
        return real_->GetDevice(riid, ppvDevice);
    }

    // IDXGISwapChain
    HRESULT STDMETHODCALLTYPE Present(UINT interval, UINT flags) override {
        return output_->capture(interval, flags);
    }

    HRESULT STDMETHODCALLTYPE GetBuffer(UINT buffer, REFIID riid, void** ppvSurface) override {
        return output_->buffer(buffer, riid, ppvSurface);
    }

    HRESULT STDMETHODCALLTYPE SetFullscreenState(BOOL fullscreen, IDXGIOutput* target) override {
        return fullscreen ? DXGI_ERROR_UNSUPPORTED : S_OK;
    }

    HRESULT STDMETHODCALLTYPE GetFullscreenState(BOOL* fullscreen, IDXGIOutput** target) override {
        return real_->GetFullscreenState(fullscreen, target);
    }

    HRESULT STDMETHODCALLTYPE GetDesc(DXGI_SWAP_CHAIN_DESC* desc) override {
        auto status = real_->GetDesc(desc);
        if (SUCCEEDED(status)) desc->BufferCount = output_->size();
        return status;
    }

    HRESULT STDMETHODCALLTYPE ResizeBuffers(UINT count, UINT width, UINT height, DXGI_FORMAT format,
                                             UINT flags) override {
        return output_->resize(count, width, height, format, flags, nullptr);
    }

    HRESULT STDMETHODCALLTYPE ResizeTarget(const DXGI_MODE_DESC* parameters) override {
        return real_->ResizeTarget(parameters);
    }

    HRESULT STDMETHODCALLTYPE GetContainingOutput(IDXGIOutput** output) override {
        return real_->GetContainingOutput(output);
    }

    HRESULT STDMETHODCALLTYPE GetFrameStatistics(DXGI_FRAME_STATISTICS* statistics) override {
        return real_->GetFrameStatistics(statistics);
    }

    HRESULT STDMETHODCALLTYPE GetLastPresentCount(UINT* lastPresentCount) override {
        if (!lastPresentCount) return E_POINTER;
        *lastPresentCount = output_->count();
        return S_OK;
    }

    // IDXGISwapChain1
    HRESULT STDMETHODCALLTYPE GetDesc1(DXGI_SWAP_CHAIN_DESC1* desc) override {
        auto status = real_->GetDesc1(desc);
        if (SUCCEEDED(status)) desc->BufferCount = output_->size();
        return status;
    }

    HRESULT STDMETHODCALLTYPE GetFullscreenDesc(DXGI_SWAP_CHAIN_FULLSCREEN_DESC* desc) override {
        return real_->GetFullscreenDesc(desc);
    }

    HRESULT STDMETHODCALLTYPE GetHwnd(HWND* window) override { return real_->GetHwnd(window); }

    HRESULT STDMETHODCALLTYPE GetCoreWindow(REFIID riid, void** unknown) override {
        return real_->GetCoreWindow(riid, unknown);
    }

    HRESULT STDMETHODCALLTYPE Present1(UINT interval, UINT flags,
                                        const DXGI_PRESENT_PARAMETERS* parameters) override {
        // The XeSS proxy submits complete images. Partial-update history is not
        // part of the virtual chain contract.
        if (parameters && (parameters->DirtyRectsCount || parameters->pScrollRect || parameters->pScrollOffset))
            return DXGI_ERROR_UNSUPPORTED;
        return output_->capture(interval, flags);
    }

    BOOL STDMETHODCALLTYPE IsTemporaryMonoSupported() override { return real_->IsTemporaryMonoSupported(); }

    HRESULT STDMETHODCALLTYPE GetRestrictToOutput(IDXGIOutput** restrictToOutput) override {
        return real_->GetRestrictToOutput(restrictToOutput);
    }

    HRESULT STDMETHODCALLTYPE SetBackgroundColor(const DXGI_RGBA* color) override {
        return real_->SetBackgroundColor(color);
    }

    HRESULT STDMETHODCALLTYPE GetBackgroundColor(DXGI_RGBA* color) override { return real_->GetBackgroundColor(color); }

    HRESULT STDMETHODCALLTYPE SetRotation(DXGI_MODE_ROTATION rotation) override { return real_->SetRotation(rotation); }

    HRESULT STDMETHODCALLTYPE GetRotation(DXGI_MODE_ROTATION* rotation) override { return real_->GetRotation(rotation); }

    // IDXGISwapChain2
    HRESULT STDMETHODCALLTYPE SetSourceSize(UINT width, UINT height) override {
        return real_->SetSourceSize(width, height);
    }

    HRESULT STDMETHODCALLTYPE GetSourceSize(UINT* width, UINT* height) override {
        return real_->GetSourceSize(width, height);
    }

    HRESULT STDMETHODCALLTYPE SetMaximumFrameLatency(UINT maximumLatency) override {
        if (!maximumLatency || maximumLatency > DXGI_MAX_SWAP_CHAIN_BUFFERS) return E_INVALIDARG;
        requestedLatency_.store(maximumLatency, std::memory_order_relaxed);
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE GetMaximumFrameLatency(UINT* maximumLatency) override {
        if (!maximumLatency) return E_POINTER;
        *maximumLatency = requestedLatency_.load(std::memory_order_relaxed);
        return S_OK;
    }

    HANDLE STDMETHODCALLTYPE GetFrameLatencyWaitableObject() override { return output_->readyHandle(); }

    HRESULT STDMETHODCALLTYPE SetMatrixTransform(const DXGI_MATRIX_3X2_F* matrix) override {
        return real_->SetMatrixTransform(matrix);
    }

    HRESULT STDMETHODCALLTYPE GetMatrixTransform(DXGI_MATRIX_3X2_F* matrix) override {
        return real_->GetMatrixTransform(matrix);
    }

    // IDXGISwapChain3
    UINT STDMETHODCALLTYPE GetCurrentBackBufferIndex() override {
        return output_->index();
    }

    HRESULT STDMETHODCALLTYPE CheckColorSpaceSupport(DXGI_COLOR_SPACE_TYPE type, UINT* support) override {
        return real_->CheckColorSpaceSupport(type, support);
    }

    HRESULT STDMETHODCALLTYPE SetColorSpace1(DXGI_COLOR_SPACE_TYPE type) override { return real_->SetColorSpace1(type); }

    HRESULT STDMETHODCALLTYPE ResizeBuffers1(UINT bufferCount, UINT width, UINT height, DXGI_FORMAT format,
                                             UINT swapChainFlags, const UINT* creationNodeMask,
                                             IUnknown* const* presentQueue) override {
        return output_->resize(bufferCount, width, height, format, swapChainFlags, presentQueue);
    }

    // IDXGISwapChain4
    HRESULT STDMETHODCALLTYPE SetHDRMetaData(DXGI_HDR_METADATA_TYPE type, UINT size, void* metadata) override {
        return real_->SetHDRMetaData(type, size, metadata);
    }

private:
    static bool isSwapChainIid(REFIID riid) {
        return IsEqualGUID(riid, __uuidof(IUnknown)) || IsEqualGUID(riid, __uuidof(IDXGIObject))
            || IsEqualGUID(riid, __uuidof(IDXGIDeviceSubObject)) || IsEqualGUID(riid, __uuidof(IDXGISwapChain))
            || IsEqualGUID(riid, __uuidof(IDXGISwapChain1)) || IsEqualGUID(riid, __uuidof(IDXGISwapChain2))
            || IsEqualGUID(riid, __uuidof(IDXGISwapChain3)) || IsEqualGUID(riid, __uuidof(IDXGISwapChain4));
    }

    static bool isFactoryIid(REFIID riid) {
        return IsEqualGUID(riid, __uuidof(IDXGIFactory)) || IsEqualGUID(riid, __uuidof(IDXGIFactory1))
            || IsEqualGUID(riid, __uuidof(IDXGIFactory2)) || IsEqualGUID(riid, __uuidof(IDXGIFactory3))
            || IsEqualGUID(riid, __uuidof(IDXGIFactory4)) || IsEqualGUID(riid, __uuidof(IDXGIFactory5))
            || IsEqualGUID(riid, __uuidof(IDXGIFactory6)) || IsEqualGUID(riid, __uuidof(IDXGIFactory7));
    }

    ComPtr<IDXGISwapChain4> real_;
    std::shared_ptr<AsyncOutput> output_;
    PacingFactory* factory_ = nullptr;
    std::atomic<ULONG> references_{1};
    std::atomic<UINT> requestedLatency_{1};
};

class PacingFactory final : public IDXGIFactory7 {
public:
    // The initial reference transfers to the creator (the Session).
    PacingFactory(ComPtr<IDXGIFactory4> real, std::shared_ptr<AsyncOutput> output)
        : output_(std::move(output)) {
        ComPtr<IDXGIFactory7> full;
        if (FAILED(real.As(&full)))
            throw std::runtime_error("Opening the DXGI factory for presentation interposition");
        real_ = std::move(full);
    }

    PacingFactory(const PacingFactory&) = delete;
    PacingFactory& operator=(const PacingFactory&) = delete;

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppvObject) override {
        if (!ppvObject) return E_POINTER;
        *ppvObject = nullptr;
        if (isFactoryIid(riid) || IsEqualGUID(riid, __uuidof(IUnknown)) || IsEqualGUID(riid, __uuidof(IDXGIObject))) {
            *ppvObject = static_cast<IDXGIFactory7*>(this);
            AddRef();
            return S_OK;
        }
        return E_NOINTERFACE;
    }

    ULONG STDMETHODCALLTYPE AddRef() override { return references_.fetch_add(1, std::memory_order_relaxed) + 1; }

    ULONG STDMETHODCALLTYPE Release() override {
        const ULONG previous = references_.fetch_sub(1, std::memory_order_relaxed);
        if (previous == 1) delete this;
        return previous - 1;
    }

    // IDXGIObject
    HRESULT STDMETHODCALLTYPE SetPrivateData(REFGUID name, UINT dataSize, const void* data) override {
        return real_->SetPrivateData(name, dataSize, data);
    }

    HRESULT STDMETHODCALLTYPE SetPrivateDataInterface(REFGUID name, const IUnknown* unknown) override {
        return real_->SetPrivateDataInterface(name, unknown);
    }

    HRESULT STDMETHODCALLTYPE GetPrivateData(REFGUID name, UINT* dataSize, void* data) override {
        return real_->GetPrivateData(name, dataSize, data);
    }

    HRESULT STDMETHODCALLTYPE GetParent(REFIID riid, void** ppvParent) override {
        return real_->GetParent(riid, ppvParent);
    }

    // IDXGIFactory
    HRESULT STDMETHODCALLTYPE EnumAdapters(UINT adapter, IDXGIAdapter** ppAdapter) override {
        return real_->EnumAdapters(adapter, ppAdapter);
    }

    HRESULT STDMETHODCALLTYPE MakeWindowAssociation(HWND window, UINT flags) override {
        return real_->MakeWindowAssociation(window, flags);
    }

    HRESULT STDMETHODCALLTYPE GetWindowAssociation(HWND* window) override {
        return real_->GetWindowAssociation(window);
    }

    HRESULT STDMETHODCALLTYPE CreateSwapChain(IUnknown*, DXGI_SWAP_CHAIN_DESC*, IDXGISwapChain**) override {
        return DXGI_ERROR_UNSUPPORTED;
    }

    HRESULT STDMETHODCALLTYPE CreateSoftwareAdapter(HMODULE module, IDXGIAdapter** ppAdapter) override {
        return real_->CreateSoftwareAdapter(module, ppAdapter);
    }

    // IDXGIFactory1
    HRESULT STDMETHODCALLTYPE EnumAdapters1(UINT adapter, IDXGIAdapter1** ppAdapter) override {
        return real_->EnumAdapters1(adapter, ppAdapter);
    }

    BOOL STDMETHODCALLTYPE IsCurrent() override { return real_->IsCurrent(); }

    // IDXGIFactory2
    BOOL STDMETHODCALLTYPE IsWindowedStereoEnabled() override { return real_->IsWindowedStereoEnabled(); }

    HRESULT STDMETHODCALLTYPE CreateSwapChainForHwnd(IUnknown* device, HWND window,
                                                      const DXGI_SWAP_CHAIN_DESC1* desc,
                                                      const DXGI_SWAP_CHAIN_FULLSCREEN_DESC* fullscreenDesc,
                                                      IDXGIOutput* restrictToOutput,
                                                      IDXGISwapChain1** result) override {
        if (!device || !desc || !result) return E_POINTER;
        *result = nullptr;
        ComPtr<IDXGISwapChain4> chain;
        const auto status = output_->create(real_.Get(), device, window, *desc, fullscreenDesc, restrictToOutput, chain);
        if (FAILED(status)) return status;
        *result = new (std::nothrow) PacingSwapChain(std::move(chain), output_, this);
        return *result ? S_OK : E_OUTOFMEMORY;
    }

    HRESULT STDMETHODCALLTYPE CreateSwapChainForCoreWindow(IUnknown*, IUnknown*, const DXGI_SWAP_CHAIN_DESC1*,
                                                           IDXGIOutput*, IDXGISwapChain1**) override {
        return DXGI_ERROR_UNSUPPORTED;
    }

    HRESULT STDMETHODCALLTYPE GetSharedResourceAdapterLuid(HANDLE resource, LUID* luid) override {
        return real_->GetSharedResourceAdapterLuid(resource, luid);
    }

    HRESULT STDMETHODCALLTYPE RegisterStereoStatusWindow(HWND window, UINT message, DWORD* cookie) override {
        return real_->RegisterStereoStatusWindow(window, message, cookie);
    }

    HRESULT STDMETHODCALLTYPE RegisterStereoStatusEvent(HANDLE event, DWORD* cookie) override {
        return real_->RegisterStereoStatusEvent(event, cookie);
    }

    void STDMETHODCALLTYPE UnregisterStereoStatus(DWORD cookie) override {
        real_->UnregisterStereoStatus(cookie);
    }

    HRESULT STDMETHODCALLTYPE RegisterOcclusionStatusWindow(HWND window, UINT message, DWORD* cookie) override {
        return real_->RegisterOcclusionStatusWindow(window, message, cookie);
    }

    HRESULT STDMETHODCALLTYPE RegisterOcclusionStatusEvent(HANDLE event, DWORD* cookie) override {
        return real_->RegisterOcclusionStatusEvent(event, cookie);
    }

    void STDMETHODCALLTYPE UnregisterOcclusionStatus(DWORD cookie) override {
        real_->UnregisterOcclusionStatus(cookie);
    }

    HRESULT STDMETHODCALLTYPE CreateSwapChainForComposition(IUnknown*, const DXGI_SWAP_CHAIN_DESC1*,
                                                            IDXGIOutput*, IDXGISwapChain1**) override {
        return DXGI_ERROR_UNSUPPORTED;
    }

    // IDXGIFactory3
    UINT STDMETHODCALLTYPE GetCreationFlags() override { return real_->GetCreationFlags(); }

    // IDXGIFactory4
    HRESULT STDMETHODCALLTYPE EnumAdapterByLuid(LUID adapterLuid, REFIID riid, void** ppvAdapter) override {
        return real_->EnumAdapterByLuid(adapterLuid, riid, ppvAdapter);
    }

    HRESULT STDMETHODCALLTYPE EnumWarpAdapter(REFIID riid, void** ppvAdapter) override {
        return real_->EnumWarpAdapter(riid, ppvAdapter);
    }

    // IDXGIFactory5
    HRESULT STDMETHODCALLTYPE CheckFeatureSupport(DXGI_FEATURE feature, void* featureSupportData,
                                                  UINT featureSupportDataSize) override {
        return real_->CheckFeatureSupport(feature, featureSupportData, featureSupportDataSize);
    }

    // IDXGIFactory6
    HRESULT STDMETHODCALLTYPE EnumAdapterByGpuPreference(UINT adapter, DXGI_GPU_PREFERENCE gpuPreference, REFIID riid,
                                                         void** ppvAdapter) override {
        return real_->EnumAdapterByGpuPreference(adapter, gpuPreference, riid, ppvAdapter);
    }

    // IDXGIFactory7
    HRESULT STDMETHODCALLTYPE RegisterAdaptersChangedEvent(HANDLE event, DWORD* cookie) override {
        return real_->RegisterAdaptersChangedEvent(event, cookie);
    }

    HRESULT STDMETHODCALLTYPE UnregisterAdaptersChangedEvent(DWORD cookie) override {
        return real_->UnregisterAdaptersChangedEvent(cookie);
    }

private:
    static bool isFactoryIid(REFIID riid) {
        return IsEqualGUID(riid, __uuidof(IDXGIFactory)) || IsEqualGUID(riid, __uuidof(IDXGIFactory1))
            || IsEqualGUID(riid, __uuidof(IDXGIFactory2)) || IsEqualGUID(riid, __uuidof(IDXGIFactory3))
            || IsEqualGUID(riid, __uuidof(IDXGIFactory4)) || IsEqualGUID(riid, __uuidof(IDXGIFactory5))
            || IsEqualGUID(riid, __uuidof(IDXGIFactory6)) || IsEqualGUID(riid, __uuidof(IDXGIFactory7));
    }

    ComPtr<IDXGIFactory7> real_;
    std::shared_ptr<AsyncOutput> output_;
    std::atomic<ULONG> references_{1};
};

// PacingSwapChain members that need the complete PacingFactory type.

inline PacingSwapChain::PacingSwapChain(ComPtr<IDXGISwapChain4>&& real, std::shared_ptr<AsyncOutput> output,
                                        PacingFactory* factory)
    : real_(std::move(real)), output_(std::move(output)), factory_(factory) {
    if (factory_) factory_->AddRef();
}

inline PacingSwapChain::~PacingSwapChain() {
    if (factory_) factory_->Release();
}

inline HRESULT STDMETHODCALLTYPE PacingSwapChain::QueryInterface(REFIID riid, void** ppvObject) {
    if (!ppvObject) return E_POINTER;
    *ppvObject = nullptr;
    if (isSwapChainIid(riid)) {
        *ppvObject = static_cast<IDXGISwapChain4*>(this);
        AddRef();
        return S_OK;
    }
    return E_NOINTERFACE;
}

inline HRESULT STDMETHODCALLTYPE PacingSwapChain::GetParent(REFIID riid, void** ppvParent) {
    // Keep GetParent(IDXGIFactory*) on the interposed factory so a later swap
    // chain creation cannot slip past the wrapper.
    if (factory_ && (isFactoryIid(riid) || IsEqualGUID(riid, __uuidof(IUnknown))
            || IsEqualGUID(riid, __uuidof(IDXGIObject)))) return factory_->QueryInterface(riid, ppvParent);
    return real_->GetParent(riid, ppvParent);
}
}
