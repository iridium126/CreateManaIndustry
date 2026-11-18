#pragma once
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <string>
#include <vector>

namespace stormDxr {
using Microsoft::WRL::ComPtr;

struct Adapter {
    ComPtr<IDXGIAdapter1> dxgi;
    std::string id;
    std::wstring model;
    std::string pci;
    LUID luid;
    bool dxr;
};

void check(HRESULT result, const char* operation);
std::vector<Adapter> adapters(bool probe = true);
std::string adapterLuid(LUID luid);
ComPtr<ID3D12Device5> device(const Adapter& adapter);
}
