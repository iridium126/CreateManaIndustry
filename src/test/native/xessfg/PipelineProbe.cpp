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

void queueDiscard(Session& session, unsigned slot, UINT64 sequence) {
    while (!session.enqueue({slot, 0, sequence, false, false, true, {}, 0, 0}))
        Session::waitWithMessages(session.queueSpace.value);
}

void verifyPendingCapacity() {
    Session session;
    session.workReady.value = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    session.queueSpace.value = CreateEventW(nullptr, TRUE, TRUE, nullptr);
    require(session.workReady.value && session.queueSpace.value, "Creating test queue events");
    session.sequence = 1;
    session.activeSlot = 0;
    session.slots[0].colorSubmitted = true;
    require(session.enqueue({0, 0, 1, false, false, true, {}, 0, 0}), "Submitting first pending frame");
    session.sequence = 2;
    session.activeSlot = 1;
    require(!session.enqueue({1, 0, 2, false, false, true, {}, 0, 0}), "Accepted two pending frames");
    require(session.waiting->sequence == 1 && session.activeSlot == 1, "Overwrote an older pending frame");
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

bool waitForFence(ID3D12Fence* fence, UINT64 value, DWORD timeout = 5000) {
    Handle event;
    event.value = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    require(event.value, "Creating probe fence event");
    check(fence->SetEventOnCompletion(value, event.value), "Arming probe fence");
    return WaitForSingleObject(event.value, timeout) == WAIT_OBJECT_0;
}

struct QueueGate {
    ComPtr<ID3D12Fence> fence;
    ~QueueGate() { if (fence) fence->Signal(1); }

    void hold(Session& session, ID3D12CommandQueue* queue) {
        check(session.generationGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence)),
              "Creating probe queue gate");
        check(queue->Wait(fence.Get(), 1), "Holding a probe GPU queue");
    }
};

void readTexture(Session& session, ID3D12GraphicsCommandList* cmd, ID3D12Resource* image,
                 ID3D12Resource* readback, Plane plane, D3D12_RESOURCE_STATES state) {
    if (state != D3D12_RESOURCE_STATE_COPY_SOURCE) transition(cmd, image, state, D3D12_RESOURCE_STATE_COPY_SOURCE);
    D3D12_TEXTURE_COPY_LOCATION src{image, D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX};
    D3D12_TEXTURE_COPY_LOCATION dst{readback, D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT};
    dst.PlacedFootprint = session.footprints[plane];
    cmd->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);
    if (state != D3D12_RESOURCE_STATE_COPY_SOURCE) transition(cmd, image, D3D12_RESOURCE_STATE_COPY_SOURCE, state);
}

void verifyTransfer(Session& session, unsigned index, Group group, uint32_t tag) {
    auto& slot = session.slots[index];
    const auto bytes = session.groupBytes[group];
    const auto first = firstPlane(group);
    const auto token = transferToken(session.sequence, group);
    QueueGate presentation;
    if (session.cross && group == Geometry) presentation.hold(session, session.presentQueue.Get());
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
    if (group == Geometry) session.waitForTransfer(token);
    else require(waitForFence(session.prepared.Get(), session.sequence), "Waiting for actual prepared inputs");
    auto readback = hostBuffer(session.generationGpu.Get(), bytes, D3D12_HEAP_TYPE_READBACK);
    std::array<ComPtr<ID3D12Resource>, 2> copies;
    Commands read;
    read.create(session.generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_COMPUTE);
    auto cmd = read.begin();
    const auto memory = defaultMemory();
    for (unsigned i = 0; i < copies.size(); ++i) {
        const auto plane = static_cast<Plane>(first + i);
        if (group == Color || session.directGeometry) {
            auto& image = session.inputsFor(session.sequence).planes[plane];
            readTexture(session, cmd, image.Get(), readback.Get(), plane, session.inputState(plane));
            continue;
        }
        const auto desc = texture(Formats[plane], session.width, session.height);
        check(session.generationGpu->CreateCommittedResource(&memory, D3D12_HEAP_FLAG_NONE, &desc,
              D3D12_RESOURCE_STATE_COPY_SOURCE, nullptr, IID_PPV_ARGS(&copies[i])), "Allocating probe texture");
        session.copyPlane(cmd, session.sourceFor(slot, plane), copies[i].Get(), plane, D3D12_RESOURCE_STATE_COPY_SOURCE);
        readTexture(session, cmd, copies[i].Get(), readback.Get(), plane, D3D12_RESOURCE_STATE_COPY_SOURCE);
    }
    read.submit(session.prepareQueue.Get());
    ComPtr<ID3D12Fence> readDone;
    check(session.generationGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&readDone)), "Creating probe readback fence");
    check(session.prepareQueue->Signal(readDone.Get(), 1), "Signaling probe readback");
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

void verifyPackedColor(Session& session, unsigned index) {
    auto& slot = session.slots[index];
    auto input = hostBuffer(session.renderGpu.Get(), session.groupBytes[Color], D3D12_HEAP_TYPE_UPLOAD);
    const D3D12_RANGE empty{0, 0};
    void* mapped = nullptr;
    check(input->Map(0, &empty, &mapped), "Mapping packed color input");
    std::memset(mapped, 0, static_cast<size_t>(session.groupBytes[Color]));
    auto changed = [](unsigned x, unsigned y) { return ((x / 4) + y) % 3 == 0; };
    auto rgb = [&](unsigned x, unsigned y, unsigned plane) {
        if (session.sharedInputs && !changed(x, y)) plane = 0;
        return (x * 37 + y * 11 + plane * 1048579) & 0xffffff;
    };
    auto luma = [](unsigned x, unsigned y, unsigned plane) {
        return (x * 13 + y * 7 + plane * 31) & 255;
    };
    for (unsigned plane = 0; plane < 2; ++plane) {
        auto* bytes = static_cast<uint8_t*>(mapped) + plane * session.colorPlaneBytes;
        for (unsigned y = 0; y < session.height; ++y) {
            auto* row = bytes + UINT64(y) * session.colorStride;
            for (unsigned x = 0; x < session.width; ++x) {
                if (session.transport == Rgb24) {
                    const auto value = rgb(x, y, plane);
                    for (unsigned channel = 0; channel < 3; ++channel)
                        row[x * 3 + channel] = static_cast<uint8_t>(value >> (8 * channel));
                } else {
                    row[x * 2] = static_cast<uint8_t>(luma(x, y, plane));
                    row[x * 2 + 1] = 128;
                    row[(x & ~1u) * 2 + 3] = 128;
                }
            }
        }
    }
    if (session.sharedInputs) {
        auto* masks = reinterpret_cast<uint32_t*>(static_cast<uint8_t*>(mapped) + 2 * session.colorPlaneBytes);
        const unsigned stride = (session.width + 127) / 128;
        for (unsigned y = 0; y < session.height; ++y)
            for (unsigned x = 0; x < session.width; x += 4)
                if (changed(x, y)) masks[y * stride + x / 128] |= 1u << ((x / 4) % 32);
    }
    input->Unmap(0, nullptr);
    Commands fill;
    fill.create(session.renderGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
    fill.begin()->CopyBufferRegion(slot.groups[Color].glBuffer.Get(), 0, input.Get(), 0, session.groupBytes[Color]);
    fill.submit(session.copyQueue.Get());
    const auto token = transferToken(session.sequence, Color);
    check(session.copyQueue->Signal(session.glWritten.Get(), token), "Publishing packed color");
    session.submitColor(index, session.sequence, true);
    require(waitForFence(session.prepared.Get(), session.sequence), "Waiting for prepared packed color");
    const auto& hudless = session.footprints[Hudless];
    const auto bytes = hudless.Offset + UINT64(hudless.Footprint.RowPitch) * session.height;
    auto readback = hostBuffer(session.generationGpu.Get(), bytes, D3D12_HEAP_TYPE_READBACK);
    Commands read;
    read.create(session.generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_COMPUTE);
    auto cmd = read.begin();
    auto& planes = session.inputsFor(session.sequence).planes;
    readTexture(session, cmd, planes[Final].Get(), readback.Get(), Final, D3D12_RESOURCE_STATE_COPY_SOURCE);
    readTexture(session, cmd, planes[Hudless].Get(), readback.Get(), Hudless, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
    read.submit(session.prepareQueue.Get());
    ComPtr<ID3D12Fence> done;
    check(session.generationGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&done)), "Creating packed readback fence");
    check(session.prepareQueue->Signal(done.Get(), 1), "Completing packed readback");
    require(waitForFence(done.Get(), 1), "Timed out reading unpacked color");
    const D3D12_RANGE range{0, bytes};
    check(readback->Map(0, &range, &mapped), "Mapping unpacked color");
    bool matches = true;
    for (unsigned plane = 0; plane < 2; ++plane) {
        const auto footprint = session.footprints[plane];
        for (unsigned y = 0; y < session.height; ++y) {
            const auto* row = reinterpret_cast<const uint32_t*>(static_cast<const uint8_t*>(mapped)
                + footprint.Offset + UINT64(y) * footprint.Footprint.RowPitch);
            for (unsigned x = 0; x < session.width; ++x) {
                const auto value = session.transport == Rgb24 ? rgb(x, y, plane) : luma(x, y, plane) * 0x010101;
                matches &= row[x] == (value | 0xff000000);
            }
        }
    }
    readback->Unmap(0, &empty);
    require(matches, "Packed color reconstruction differed at final or HUD-less plane");
}

void verifySharedGeometry(Session& session, bool reconstruct) {
    const auto sequence = session.sequence + 1;
    const int index = session.acquire(sequence, 0);
    require(index >= 0, "Acquiring shared-input frame");
    auto& slot = session.slots[index];
    const auto& motion = session.footprints[Motion];
    const auto bytes = reconstruct ? motion.Offset : session.groupBytes[Geometry];
    auto input = hostBuffer(session.renderGpu.Get(), bytes, D3D12_HEAP_TYPE_UPLOAD);
    const D3D12_RANGE empty{0, 0};
    void* mapped = nullptr;
    check(input->Map(0, &empty, &mapped), "Mapping shared geometry");
    const auto format = session.footprints[Depth].Footprint;
    require(format.RowPitch == motion.Footprint.RowPitch, "Depth and motion footprints differed");
    constexpr uint32_t sky = 0x3f800000, ground = 0x3e800000;
    constexpr uint32_t skyMotion = 0xbc004000, groundMotion = 0x3c00c000;
    constexpr uint32_t packedMotion = 0xbda03a01;
    for (unsigned y = 0; y < session.height; ++y) {
        auto* row = static_cast<uint8_t*>(mapped) + UINT64(y) * format.RowPitch;
        for (unsigned x = 0; x < session.width; ++x) {
            reinterpret_cast<uint32_t*>(row)[x] = x < 37 ? sky : ground;
            if (!reconstruct) reinterpret_cast<uint32_t*>(row + motion.Offset)[x] = packedMotion;
        }
    }
    input->Unmap(0, nullptr);
    Commands fill;
    fill.create(session.renderGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
    fill.begin()->CopyBufferRegion(slot.groups[Geometry].glBuffer.Get(), 0, input.Get(), 0, bytes);
    fill.submit(session.copyQueue.Get());
    check(session.copyQueue->Signal(session.glWritten.Get(), transferToken(sequence, Geometry)), "Publishing shared geometry");
    const float dx = 4.0f / session.width;
    const float dy = 2.0f / session.height;
    const Reprojection reprojection{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -dx, -dy, 0, 1,
                                    1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, dx, dy, 0, 1, 0.25f, -0.25f};
    session.submitGeometry(index, sequence, reconstruct ? &reprojection : nullptr);
    require(slot.geometryBytes == bytes, "Incorrect cross-adapter geometry byte count");
    verifyPackedColor(session, index);

    const auto total = session.groupBytes[Geometry];
    auto readback = hostBuffer(session.generationGpu.Get(), total, D3D12_HEAP_TYPE_READBACK);
    Commands read;
    read.create(session.generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_COMPUTE);
    auto* cmd = read.begin();
    auto& planes = session.inputsFor(sequence).planes;
    for (auto plane : {Depth, Motion})
        readTexture(session, cmd, planes[plane].Get(), readback.Get(), plane, session.inputState(plane));
    read.submit(session.prepareQueue.Get());
    ComPtr<ID3D12Fence> done;
    check(session.generationGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&done)), "Creating geometry readback fence");
    check(session.prepareQueue->Signal(done.Get(), 1), "Completing geometry readback");
    require(waitForFence(done.Get(), 1), "Timed out reading shared geometry");
    const D3D12_RANGE range{0, total};
    check(readback->Map(0, &range, &mapped), "Mapping prepared geometry");
    bool matches = true;
    for (unsigned y = 0; y < session.height; ++y) {
        auto* row = static_cast<const uint8_t*>(mapped) + UINT64(y) * format.RowPitch;
        for (unsigned x = 0; x < session.width; ++x) {
            const auto receivedDepth = reinterpret_cast<const uint32_t*>(row)[x];
            const auto expected = reconstruct ? (x < 36 ? skyMotion : groundMotion) : packedMotion;
            const auto receivedMotion = reinterpret_cast<const uint32_t*>(row + motion.Offset)[x];
            // Permit one adjacent half-float value after the GPU projection and quantization.
            const bool motionMatches = reconstruct
                ? std::abs(int(receivedMotion & 0xffff) - int(expected & 0xffff)) <= 1
                    && std::abs(int(receivedMotion >> 16) - int(expected >> 16)) <= 1
                : receivedMotion == expected;
            if (matches && (receivedDepth != (x < 37 ? sky : ground) || !motionMatches)) {
                std::fprintf(stderr, "Shared geometry %s at (%u,%u), depth %08x, motion %08x expected %08x\n",
                             reconstruct ? "reprojection" : "packed", x, y, receivedDepth, receivedMotion, expected);
                matches = false;
            }
        }
    }
    readback->Unmap(0, &empty);
    require(matches, "Shared geometry did not preserve depth, dilated motion or reprojection direction");
    queueDiscard(session, index, sequence);
    require(waitForFence(session.completed.Get(), sequence), "Shared-input frame did not retire");
}

void verifySaturation(Session& session) {
    QueueGate gate;
    gate.hold(session, session.prepareQueue.Get());
    std::array<int, SlotCount> indices{};
    const auto first = session.sequence + 1;
    for (unsigned i = 0; i < SlotCount; ++i) {
        indices[i] = session.acquire(first + i, 0);
        require(indices[i] >= 0, "Expected a free full-input slot");
        queueDiscard(session, indices[i], first + i);
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
    queueDiscard(session, index, fifth);
    require(waitForFence(session.prepared.Get(), fifth), "Asynchronous discards did not retire transfers in order");
}

void verifyInputReuse(Session& session) {
    QueueGate presenter;
    presenter.hold(session, session.presentQueue.Get());
    const auto first = session.sequence + 1;
    int index = session.acquire(first, 0);
    require(index >= 0, "Acquiring first frame for the input ring");
    check(session.copyQueue->Signal(session.glWritten.Get(), transferToken(first, Color)), "Publishing first test color");
    session.submitColor(index, first, false);
    require(waitForFence(session.prepared.Get(), first), "Arc preparation waited for Present");
    queueDiscard(session, index, first);

    const auto wrapped = first + InputCount;
    index = session.acquire(wrapped, 0);
    require(index >= 0, "Acquiring a transfer slot while an SDK input is busy");
    if (session.directGeometry) {
        const auto token = transferToken(wrapped, Geometry);
        check(session.copyQueue->Signal(session.glWritten.Get(), token), "Publishing wrapped geometry");
        session.submitGeometry(index, wrapped);
        require(waitForFence(session.transferOut.Get(), token), "Wrapped geometry did not leave the producer");
        require(!waitForFence(session.uploadReady.Get(), token, 30), "Overwrote SDK geometry before presentation retired it");
    }
    check(session.copyQueue->Signal(session.glWritten.Get(), transferToken(wrapped, Color)), "Publishing next test color");
    session.submitColor(index, wrapped, false);
    if (!session.directGeometry)
        require(waitForFence(session.sharedInputs ? session.transferIn.Get() : session.uploadReady.Get(),
                             transferToken(wrapped, Color)), "Second frame did not reach Arc");
    require(!waitForFence(session.prepared.Get(), wrapped, 30), "Overwrote SDK textures before the presentation fence");
    check(presenter.fence->Signal(1), "Releasing presentation fence test");
    require(waitForFence(session.prepared.Get(), wrapped), "Prepared input did not wake on presentation completion");
    queueDiscard(session, index, wrapped);
}

void verifyPopup(jlong render, jlong arc) {
    constexpr int width = 803, height = 611;
    const auto owner = CreateWindowExW(WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW, L"STATIC", L"XeSS-FG popup owner",
        WS_POPUP | WS_VISIBLE, -20000, -20000, width, height, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    require(owner, "Creating off-screen popup owner");
    try {
        Session session;
        session.start(owner, render, arc, width, height, Rgb24, true);
        const auto wnd = session.surface;
        const auto styles = GetWindowLongPtrW(wnd, GWL_STYLE);
        const auto extended = GetWindowLongPtrW(wnd, GWL_EXSTYLE);
        require((styles & WS_POPUP) && !(styles & WS_CHILD) && !IsWindowEnabled(wnd)
                && (extended & WS_EX_NOACTIVATE) && !(extended & WS_EX_TOPMOST)
                && GetWindow(wnd, GW_OWNER) == owner && GetAncestor(wnd, GA_ROOT) == wnd
                && GetWindowThreadProcessId(wnd, nullptr) != GetCurrentThreadId(), "Popup style or input ownership mismatch");
        require(session.positionPopup(), "Showing a popup over its owner");
        require(SetWindowPos(owner, nullptr, -19980, -19970, width, height, SWP_NOACTIVATE | SWP_NOZORDER),
                "Moving the popup owner");
        RECT actual{}, expected{};
        require(session.positionPopup() && GetWindowRect(wnd, &actual) && clientOnScreen(owner, expected)
                && EqualRect(&actual, &expected), "Popup did not track its owner's client rectangle");
        require(SetWindowPos(owner, nullptr, 0, 0, width - 1, height, SWP_NOMOVE | SWP_NOACTIVATE | SWP_NOZORDER),
                "Resizing the popup owner");
        require(!session.positionPopup() && !IsWindowVisible(wnd), "Popup showed a stale size while its owner resized");
    } catch (...) {
        DestroyWindow(owner);
        throw;
    }
    DestroyWindow(owner);
}
}

int main() {
    HWND window = CreateWindowExW(0, L"STATIC", L"XeSS-FG pipeline probe", WS_OVERLAPPEDWINDOW,
                                 0, 0, 1400, 800, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    if (!window) return 1;
    int result = 0;
    try {
        verifyPendingCapacity();
        const auto render = adapter(0x10de, L"RTX 4070 Laptop");
        const auto arc = adapter(0x8086, L"A750");
        verifyPopup(render, arc);
        {
            Session session;
            session.start(window, render, arc, 1283, 719, Rgba8);
            require(GetWindowThreadProcessId(session.surface, nullptr) != GetCurrentThreadId()
                    && !IsWindowEnabled(session.surface), "Proxy window ownership or input routing is incorrect");
            verifySaturation(session);
            const auto frame = session.sequence + 1;
            const int index = session.acquire(frame, 101);
            require(index >= 0, "Acquiring frame for plane integrity test");
            verifyTransfer(session, index, Geometry, 0x3f00ff00);
            {
                QueueGate presenter;
                presenter.hold(session, session.presentQueue.Get());
                verifyTransfer(session, index, Color, 0xff45c837);
                queueDiscard(session, index, frame);
                require(session.slotsInUse() == 0 && session.completed->GetCompletedValue() < frame,
                        "Transfer slot remained tied to the blocked presentation queue");
            }

            const int partial = session.acquire(frame + 1, 0);
            require(partial >= 0, "Acquiring frame with geometry only");
            verifyTransfer(session, partial, Geometry, 0x3f803abc);
            queueDiscard(session, partial, frame + 1);
            require(waitForFence(session.prepared.Get(), frame + 1), "Partial geometry frame did not retire");
        }
        require(SetWindowPos(window, nullptr, 0, 0, 900, 700, SWP_NOZORDER | SWP_NOMOVE), "Resizing probe window");
        for (auto mode : {Rgb24, Ycbcr422}) {
            Session packed;
            packed.start(window, render, arc, 803, 611, mode, false, true);
            require(!packed.slots[0].groups[Geometry].local, "Direct geometry retained a staging buffer");
            require(packed.colorStride < packed.footprints[Final].Footprint.RowPitch, "Packed stride did not shrink");
            const int index = packed.acquire(1, 0);
            require(index >= 0, "Acquiring packed frame");
            verifyTransfer(packed, index, Geometry, 0x3f340125);
            verifyPackedColor(packed, index);
            queueDiscard(packed, index, 1);
            require(waitForFence(packed.completed.Get(), 1), "Packed frame did not retire");
            verifyInputReuse(packed);
            const auto partialFrame = packed.sequence + 1;
            const int partial = packed.acquire(partialFrame, 0);
            require(partial >= 0, "Acquiring direct geometry without color");
            verifyTransfer(packed, partial, Geometry, 0x3f803abc);
            queueDiscard(packed, partial, partialFrame);
            require(waitForFence(packed.prepared.Get(), partialFrame), "Direct geometry did not retire without color");
        }
        {
            Session shared;
            shared.start(window, render, arc, 129, 73, Rgb24, false, true, true);
            require(shared.sharedInputs && !shared.uploadQueue, "Arc shared-input path was not active");
            verifySharedGeometry(shared, true);
            verifySharedGeometry(shared, false);
            verifyInputReuse(shared);
            const auto frame = shared.sequence + 1;
            const int slot = shared.acquire(frame, 0);
            require(slot >= 0, "Acquiring shared geometry without color");
            check(shared.copyQueue->Signal(shared.glWritten.Get(), transferToken(frame, Geometry)), "Publishing partial shared input");
            shared.submitGeometry(slot, frame);
            queueDiscard(shared, slot, frame);
            require(waitForFence(shared.prepared.Get(), frame), "Partial shared input did not retire");
        }
        for (bool sharedInputs : {false, true}) {
            ComPtr<ID3D12Fence> staged;
            UINT64 done;
            {
                Session removed;
                removed.start(window, render, arc, 803, 611, Rgb24, false, true, sharedInputs);
                const int index = removed.acquire(1, 0);
                require(index >= 0, "Acquiring frame for producer removal");
                // No GL signal follows this submission. Arc must still release the blocked work.
                removed.submitColor(index, 1, false);
                staged = sharedInputs ? removed.prepared : removed.uploadReady;
                done = sharedInputs ? 1 : transferToken(1, Color);
                ComPtr<ID3D12Device5> device;
                check(removed.renderGpu.As(&device), "Opening producer-removal test interface");
                device->RemoveDevice();
                require(FAILED(removed.renderGpu->GetDeviceRemovedReason()), "Producer removal was not surfaced");
            }
            require(staged->GetCompletedValue() >= done && staged->GetCompletedValue() != UINT64_MAX,
                    "Arc input queue did not drain after producer removal");
        }
        {
            Session resized;
            resized.start(window, arc, arc, 803, 611, Rgba8);
            const int index = resized.acquire(1, 0);
            require(index >= 0, "Acquiring same-adapter frame after resize");
            verifyTransfer(resized, index, Geometry, 0x3f340125);
            verifyTransfer(resized, index, Color, 0xff123472);
            queueDiscard(resized, index, 1);
            require(waitForFence(resized.completed.Get(), 1), "Resized frame did not retire");
            ComPtr<ID3D12Device5> device;
            check(resized.generationGpu.As(&device), "Opening device-removal test interface");
            device->RemoveDevice();
            bool failed = false;
            try { resized.acquire(2, 0); } catch (const std::runtime_error&) { failed = true; }
            require(failed, "Device removal was not surfaced");
        }
        std::puts("PASS: Arc preparation and retirement, four slots, packed colors, depth reprojection, partial frame, resize, device removal");
    } catch (const std::exception& error) {
        std::fprintf(stderr, "%s\n", error.what());
        result = 1;
    }
    DestroyWindow(window);
    return result;
}
