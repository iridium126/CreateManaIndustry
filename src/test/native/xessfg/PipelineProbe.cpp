// The probe exercises the same queue and resource code as JNI on two physical adapters.
#include "XessFg.cpp"
#include <algorithm>
#include <chrono>
#include <string_view>
#include <thread>

namespace {
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

jlong adapter(UINT vendor, std::wstring_view model) {
    ComPtr<IDXGIFactory4> factory;
    check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)), "Creating probe DXGI factory");
    for (UINT i = 0;; ++i) {
        ComPtr<IDXGIAdapter1> adapter;
        if (factory->EnumAdapters1(i, &adapter) == DXGI_ERROR_NOT_FOUND) break;
        DXGI_ADAPTER_DESC1 desc{};
        check(adapter->GetDesc1(&desc), "Reading probe GPU");
        if (desc.VendorId == vendor && std::wstring_view(desc.Description).find(model) != std::wstring_view::npos)
            return static_cast<jlong>((UINT64(static_cast<DWORD>(desc.AdapterLuid.HighPart)) << 32) | desc.AdapterLuid.LowPart);
    }
    throw std::runtime_error("Required RTX or Arc adapter unavailable");
}

ComPtr<ID3D12Resource> hostBuffer(ID3D12Device* gpu, UINT64 length, D3D12_HEAP_TYPE type) {
    auto memory = defaultMemory();
    memory.Type = type;
    const auto desc = buffer(length);
    ComPtr<ID3D12Resource> result;
    check(gpu->CreateCommittedResource(&memory, D3D12_HEAP_FLAG_NONE, &desc,
          type == D3D12_HEAP_TYPE_UPLOAD ? D3D12_RESOURCE_STATE_GENERIC_READ : D3D12_RESOURCE_STATE_COPY_DEST,
          nullptr, IID_PPV_ARGS(&result)), "Allocating probe host buffer");
    return result;
}

bool waitForFence(ID3D12Fence* fence, UINT64 value) {
    Handle event;
    event.value = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    require(event.value, "Creating probe fence event");
    check(fence->SetEventOnCompletion(value, event.value), "Arming probe fence");
    return WaitForSingleObject(event.value, 5000) == WAIT_OBJECT_0;
}

struct QueueGate {
    ComPtr<ID3D12Fence> fence;
    ~QueueGate() { if (fence) fence->Signal(1); }

    void hold(Session& session) {
        check(session.generationGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence)),
              "Creating probe queue gate");
        check(session.presentQueue->Wait(fence.Get(), 1), "Holding the probe presentation queue");
    }
};

void verifyTransfer(Session& session, unsigned index, Group group, uint32_t tag) {
    auto& slot = session.slots[index];
    const auto bytes = session.groupBytes[group];
    const auto first = firstPlane(group);
    const auto token = transferToken(session.sequence, group);
    QueueGate presentation;
    if (session.cross && group == Geometry) presentation.hold(session);
    auto input = hostBuffer(session.renderGpu.Get(), bytes, D3D12_HEAP_TYPE_UPLOAD);
    void* output = nullptr;
    const D3D12_RANGE empty{0, 0};
    check(input->Map(0, &empty, &output), "Mapping probe input");
    auto pattern = static_cast<uint32_t*>(output);
    for (UINT64 i = 0; i < bytes / sizeof(uint32_t); ++i) pattern[i] = tag ^ static_cast<uint32_t>(i);
    input->Unmap(0, nullptr);

    auto sourceQueue = session.cross ? session.copyQueue.Get() : session.presentQueue.Get();
    Commands fill;
    fill.create(session.renderGpu.Get(), session.cross ? D3D12_COMMAND_LIST_TYPE_COPY : D3D12_COMMAND_LIST_TYPE_DIRECT);
    fill.begin()->CopyBufferRegion(slot.groups[group].glBuffer.Get(), 0, input.Get(), 0, bytes);
    fill.submit(sourceQueue);
    check(sourceQueue->Signal(session.glWritten.Get(), token), "Publishing synthetic OpenGL input");
    if (group == Geometry) session.submitGeometry(index, session.sequence);
    else session.submitColor(index, session.sequence, true);

    if (presentation.fence) {
        const bool copiedAhead = waitForFence(session.uploadReady.Get(), token);
        check(presentation.fence->Signal(1), "Unblocking the probe presentation queue");
        require(copiedAhead, "Arc COPY could not stage geometry ahead of the blocked DIRECT queue");
    }
    session.waitForTransfer(token);
    auto readback = hostBuffer(session.generationGpu.Get(), bytes, D3D12_HEAP_TYPE_READBACK);
    std::array<ComPtr<ID3D12Resource>, 2> copies;
    Commands read;
    read.create(session.generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_DIRECT);
    auto cmd = read.begin();
    const auto memory = defaultMemory();
    for (unsigned i = 0; i < copies.size(); ++i) {
        const auto plane = static_cast<Plane>(first + i);
        const auto desc = texture(Formats[plane], session.width, session.height);
        check(session.generationGpu->CreateCommittedResource(&memory, D3D12_HEAP_FLAG_NONE, &desc,
              D3D12_RESOURCE_STATE_COPY_SOURCE, nullptr, IID_PPV_ARGS(&copies[i])), "Allocating probe texture");
        session.copyPlane(cmd, session.sourceFor(slot, plane), copies[i].Get(), plane, D3D12_RESOURCE_STATE_COPY_SOURCE);
        D3D12_TEXTURE_COPY_LOCATION src{copies[i].Get(), D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX};
        D3D12_TEXTURE_COPY_LOCATION dst{readback.Get(), D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT};
        dst.PlacedFootprint = session.footprints[plane];
        cmd->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);
    }
    read.submit(session.presentQueue.Get());
    ComPtr<ID3D12Fence> readDone;
    check(session.generationGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&readDone)), "Creating probe readback fence");
    check(session.presentQueue->Signal(readDone.Get(), 1), "Signaling probe readback");
    require(waitForFence(readDone.Get(), 1), "Timed out reading the GPU transfer");
    const D3D12_RANGE range{0, bytes};
    check(readback->Map(0, &range, &output), "Mapping probe readback");
    const auto data = static_cast<const uint32_t*>(output);
    bool matches = true;
    for (unsigned i = 0; i < copies.size(); ++i) {
        const auto& footprint = session.footprints[first + i];
        for (unsigned y = 0; y < session.height; ++y) {
            const auto row = (footprint.Offset + UINT64(y) * footprint.Footprint.RowPitch) / sizeof(uint32_t);
            for (unsigned x = 0; x < session.width; ++x)
                matches &= data[row + x] == (tag ^ static_cast<uint32_t>(row + x));
        }
    }
    readback->Unmap(0, &empty);
    require(matches, "Frame group changed bits during transport or texture upload");
}

void verifySaturation(Session& session) {
    QueueGate gate;
    gate.hold(session);
    std::array<int, SlotCount> indices{};
    const auto first = session.sequence + 1;
    for (unsigned i = 0; i < SlotCount; ++i) {
        indices[i] = session.acquire(first + i, 0);
        require(indices[i] >= 0, "Expected a free full-input slot");
        session.discard(indices[i], first + i);
    }
    std::sort(indices.begin(), indices.end());
    for (unsigned i = 0; i < SlotCount; ++i) require(indices[i] == i, "Reused a slot before its fence");
    const auto fifth = first + SlotCount;
    const bool full = session.slotsInUse() == SlotCount && session.acquire(fifth, 0) == -1;
    std::thread release([&] {
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        gate.fence->Signal(1);
    });
    int index = -1;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    try {
        while (index < 0 && std::chrono::steady_clock::now() < deadline) {
            session.waitForSlot();
            MSG msg{};
            while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) DispatchMessageW(&msg);
            index = session.acquire(fifth, 0);
        }
    } catch (...) {
        release.join();
        throw;
    }
    release.join();
    require(full && index >= 0, "Four-slot contention failed to wake and resume in order");
    session.discard(index, fifth);
}
}

int main() {
    HWND window = CreateWindowExW(0, L"STATIC", L"XeSS-FG pipeline probe", WS_OVERLAPPEDWINDOW,
                                 0, 0, 1400, 800, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    if (!window) return 1;
    int result = 0;
    try {
        const auto render = adapter(0x10de, L"RTX 4070 Laptop");
        const auto arc = adapter(0x8086, L"A750");
        {
            Session session;
            session.initialize(window, render, arc, 1283, 719);
            verifySaturation(session);
            const auto frame = session.sequence + 1;
            const int index = session.acquire(frame, 101);
            require(index >= 0, "Acquiring frame for plane integrity test");
            verifyTransfer(session, index, Geometry, 0x3f00ff00);
            verifyTransfer(session, index, Color, 0xff45c837);
            session.discard(index, frame);

            const int partial = session.acquire(frame + 1, 0);
            require(partial >= 0, "Acquiring frame with geometry only");
            verifyTransfer(session, partial, Geometry, 0x3f803abc);
            session.discard(partial, frame + 1);
        }
        require(SetWindowPos(window, nullptr, 0, 0, 900, 700, SWP_NOZORDER | SWP_NOMOVE), "Resizing probe window");
        {
            Session resized;
            resized.initialize(window, arc, arc, 803, 611);
            const int index = resized.acquire(1, 0);
            require(index >= 0, "Acquiring same-adapter frame after resize");
            verifyTransfer(resized, index, Geometry, 0x3f340125);
            verifyTransfer(resized, index, Color, 0xff123472);
            resized.discard(index, 1);
            ComPtr<ID3D12Device5> device;
            check(resized.generationGpu.As(&device), "Opening device-removal test interface");
            device->RemoveDevice();
            bool failed = false;
            try { resized.acquire(2, 0); } catch (const std::runtime_error&) { failed = true; }
            require(failed, "Device removal was not surfaced");
        }
        std::puts("PASS: four slots, independent staging, exact plane contents, partial frame, resize, same-adapter, device removal");
    } catch (const std::exception& error) {
        std::fprintf(stderr, "%s\n", error.what());
        result = 1;
    }
    DestroyWindow(window);
    return result;
}
