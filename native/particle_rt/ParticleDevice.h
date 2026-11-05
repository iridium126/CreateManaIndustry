#pragma once
#include "Common.h"

inline std::string embeddedDevice(int id) {
    HMODULE module{};
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(&embeddedDevice), &module))
        throw std::runtime_error("RT DLL unavailable");
    auto resource = FindResourceW(module, MAKEINTRESOURCEW(id), MAKEINTRESOURCEW(10));
    auto data = resource ? LoadResource(module, resource) : nullptr;
    auto bytes = data ? LockResource(data) : nullptr;
    if (!bytes) throw std::runtime_error("Embedded RT device program missing");
    return std::string(static_cast<const char*>(bytes), SizeofResource(module, resource));
}
