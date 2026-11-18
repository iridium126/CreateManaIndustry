#include "StormDxrAdapter.h"
#include <winternl.h>
#include <d3dkmthk.h>
#include <bcrypt.h>
#include <array>
#include <cstdio>
#include <stdexcept>

namespace stormDxr {
void check(HRESULT result, const char* operation) {
    if (SUCCEEDED(result)) return;
    char code[12];
    std::snprintf(code, sizeof(code), "0x%08lx", static_cast<unsigned long>(result));
    throw std::runtime_error(std::string(operation) + " failed (" + code + ")");
}

namespace {
struct PhysicalAdapter {
    D3DKMT_OPENADAPTERFROMLUID handle{};
    explicit PhysicalAdapter(LUID luid) {
        handle.AdapterLuid = luid;
        if (D3DKMTOpenAdapterFromLuid(&handle) < 0) handle.hAdapter = 0;
    }
    ~PhysicalAdapter() {
        if (handle.hAdapter) {
            D3DKMT_CLOSEADAPTER close{handle.hAdapter};
            D3DKMTCloseAdapter(&close);
        }
    }

    bool query(KMTQUERYADAPTERINFOTYPE type, void* data, UINT bytes) const {
        if (!handle.hAdapter) return false;
        D3DKMT_QUERYADAPTERINFO request{handle.hAdapter, type, data, bytes};
        return D3DKMTQueryAdapterInfo(&request) >= 0;
    }
};

std::string stableId(const PhysicalAdapter& physical) {
    std::array<wchar_t, 1024> path{};
    UINT length = static_cast<UINT>(path.size());
    D3DKMT_QUERY_PHYSICAL_ADAPTER_PNP_KEY key{0, D3DKMT_PNP_KEY_HARDWARE, path.data(), &length};
    if (!physical.query(KMTQAITYPE_PHYSICALADAPTERPNPKEY, &key, sizeof(key)) ||
            length <= 1 || length > path.size() || path[length - 1] != L'\0') return {};
    // PnP instance paths are case-insensitive. The hardware key identifies
    // the physical instance, including two boards of the same PCI model.
    CharLowerBuffW(path.data(), length - 1);
    std::array<UCHAR, 32> hash{};
    const auto bytes = static_cast<ULONG>((length - 1) * sizeof(wchar_t));
    BCRYPT_ALG_HANDLE algorithm{};
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0)
        throw std::runtime_error("Cannot initialize Storm adapter ID hash");
    const auto status = BCryptHash(algorithm, nullptr, 0, reinterpret_cast<PUCHAR>(path.data()), bytes,
            hash.data(), static_cast<ULONG>(hash.size()));
    BCryptCloseAlgorithmProvider(algorithm, 0);
    if (status < 0) throw std::runtime_error("Cannot hash Storm adapter ID");
    constexpr char hex[] = "0123456789abcdef";
    std::string id = "PNP-";
    for (auto byte : hash) {
        id += hex[byte >> 4];
        id += hex[byte & 15];
    }
    return id;
}

std::string pciAddress(const PhysicalAdapter& physical) {
    D3DKMT_ADAPTERADDRESS address{};
    if (!physical.query(KMTQAITYPE_ADAPTERADDRESS, &address, sizeof(address))) return {};
    char text[32];
    std::snprintf(text, sizeof(text), "%02x:%02x.%x", address.BusNumber, address.DeviceNumber, address.FunctionNumber);
    return text;
}
}

ComPtr<ID3D12Device5> device(const Adapter& adapter) {
    ComPtr<ID3D12Device5> result;
    check(D3D12CreateDevice(adapter.dxgi.Get(), D3D_FEATURE_LEVEL_12_0, IID_PPV_ARGS(&result)),
            "Creating the D3D12 Storm device");
    D3D12_FEATURE_DATA_D3D12_OPTIONS5 options{};
    check(result->CheckFeatureSupport(D3D12_FEATURE_D3D12_OPTIONS5, &options, sizeof(options)),
            "Querying DXR support");
    D3D12_FEATURE_DATA_SHADER_MODEL shader{D3D_SHADER_MODEL_6_5};
    check(result->CheckFeatureSupport(D3D12_FEATURE_SHADER_MODEL, &shader, sizeof(shader)),
            "Querying D3D12 shader model");
    if (options.RaytracingTier < D3D12_RAYTRACING_TIER_1_1 || shader.HighestShaderModel < D3D_SHADER_MODEL_6_5)
        throw std::runtime_error("Storm requires DXR 1.1 and shader model 6.5");
    return result;
}

std::string adapterLuid(LUID luid) {
    char text[17];
    std::snprintf(text, sizeof(text), "%08x%08x", static_cast<unsigned>(luid.HighPart),
            static_cast<unsigned>(luid.LowPart));
    return text;
}

std::vector<Adapter> adapters(bool probe) {
    ComPtr<IDXGIFactory4> factory;
    check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)), "Creating the Storm DXGI factory");
    std::vector<Adapter> result;
    for (UINT index = 0;; ++index) {
        ComPtr<IDXGIAdapter1> dxgi;
        const auto status = factory->EnumAdapters1(index, &dxgi);
        if (status == DXGI_ERROR_NOT_FOUND) break;
        check(status, "Enumerating Storm adapters");
        DXGI_ADAPTER_DESC1 desc{};
        check(dxgi->GetDesc1(&desc), "Reading the Storm adapter descriptor");
        if ((desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) != 0) continue;
        Adapter adapter{dxgi, {}, desc.Description, {}, desc.AdapterLuid, false};
        if (probe) {
            try {
                device(adapter);
                adapter.dxr = true;
            } catch (const std::runtime_error&) {
                // An adapter may still be available through OptiX.
            }
        }
        const PhysicalAdapter physical(adapter.luid);
        adapter.id = stableId(physical);
        if (adapter.id.empty()) continue;
        adapter.pci = pciAddress(physical);
        result.push_back(std::move(adapter));
    }
    return result;
}
}
