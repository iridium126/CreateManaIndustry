// The probe exercises the same queue and resource code as JNI on two physical adapters.
#define CMI_XESSFG_PROBE_TRACE
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
    session.slots[0].fullColor = true;
    require(session.enqueue({0, 1, 1, true, true, false, {}, 0, 0}), "Submitting first pending frame");
    session.sequence = 2;
    session.activeSlot = 1;
    session.slots[1].colorSubmitted = true;
    session.slots[1].fullColor = true;
    PresentJob second{1, 2, 2, true, true, false, {}, 0, 0};
    require(!session.enqueue(second) && !session.enqueue(second), "Accepted two pending frames");
    require(session.waiting->sequence == 1 && session.waiting->interpolate && session.activeSlot == 1,
            "Overwrote or downgraded an older pending frame");
    require(!session.profileProbes && session.resultCount == 0,
            "An uncaptured frame recorded profiling data");
    session.resetAdmission();
    session.refreshTicks = 10;
    const auto enqueued = session.waiting->enqueuedAt;
    require(session.presentationPressure(enqueued + 9, 0) == 0
            && session.presentationPressure(enqueued + 10, 0) == WaitingStale,
            "A pending interpolation was not aged from its own enqueue");
    session.interpolatedFrames = 3;
    require(session.presentationPressure(enqueued + 50, enqueued) == 0,
            "Normal multi-frame pacing was counted as pressure");
    session.waiting->constants.resetHistory = 1;
    require(session.presentationPressure(enqueued + 100, 0) == 0, "A history reset triggered stale interpolation");
    session.waiting.reset();
    require(session.presentationPressure(enqueued + 100, 0) == 0,
            "The time since a removed job was counted as queue age");
    require(session.presentationPressure(enqueued + 100, enqueued) == PresentPressure,
            "An active interpolation blocked beyond its budget was ignored");
    require(session.enqueue(second) && session.waiting->interpolate, "Queue pressure downgraded the next frame");
}

bool waitForFence(ID3D12Fence* fence, UINT64 value, DWORD timeout = 5000);

void verifyRetirementProgress(ID3D12Device* gpu) {
    Session session;
    session.presentQueue = queue(gpu, D3D12_COMMAND_LIST_TYPE_DIRECT);
    check(gpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&session.completed)),
          "Creating retirement probe fence");
    ComPtr<ID3D12Fence> gate;
    check(gpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&gate)), "Creating retirement probe gate");
    check(session.presentQueue->Wait(gate.Get(), 1), "Holding retirement probe");
    session.retirePresentation(1, true);
    session.retirePresentation(2);
    const auto blocked = session.retiredPresentation();
    check(gate->Signal(1), "Releasing retirement probe");
    require(blocked == 0, "Submitting presentation work advanced recovery before the GPU retired it");
    require(waitForFence(session.completed.Get(), 2) && session.retiredPresentation() == 1,
            "A discarded job was counted as an accepted presentation");
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

bool waitForFence(ID3D12Fence* fence, UINT64 value, DWORD timeout) {
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
        const auto desc = session.inputTexture(plane);
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
        for (unsigned y = 0; y < footprint.Footprint.Height; ++y) {
            const auto row = (footprint.Offset + UINT64(y) * footprint.Footprint.RowPitch) / sizeof(uint32_t);
            for (unsigned x = 0; x < footprint.Footprint.Width; ++x)
                matches &= data[row + x] == (tag ^ static_cast<uint32_t>(row + x));
        }
    }
    readback->Unmap(0, &empty);
    require(matches, "Frame group changed bits during transport or texture upload");
}

enum class HudCoverage { Dense, OneTile, Fragmented, Empty };

void verifyPackedColor(Session& session, unsigned index, HudCoverage coverage = HudCoverage::Dense,
                       bool delayedPublication = false) {
    auto& slot = session.slots[index];
    auto input = hostBuffer(session.renderGpu.Get(), session.groupBytes[Color], D3D12_HEAP_TYPE_UPLOAD);
    const D3D12_RANGE empty{0, 0};
    void* mapped = nullptr;
    check(input->Map(0, &empty, &mapped), "Mapping packed color input");
    std::memset(mapped, 0, static_cast<size_t>(session.groupBytes[Color]));
    auto changed = [coverage, across = session.colorTilesAcross()](unsigned x, unsigned y) {
        if (coverage == HudCoverage::Empty) return false;
        if (coverage == HudCoverage::OneTile && (x >= ColorTileWidth || y >= ColorTileHeight)) return false;
        if (coverage == HudCoverage::Fragmented) {
            const auto tile = (y / ColorTileHeight) * across + x / ColorTileWidth;
            if (tile != 158 && (tile >= 128 || tile % 2 != 0)) return false;
        }
        return ((x / 4) + y) % 3 == 0;
    };
    auto rgb = [&](unsigned x, unsigned y, unsigned plane) {
        if (session.sharedInputs && !changed(x, y)) plane = Hudless;
        return (x * 37 + y * 11 + plane * 1048579) & 0xffffff;
    };
    auto luma = [](unsigned x, unsigned y, unsigned plane) {
        return (x * 13 + y * 7 + plane * 31) & 255;
    };
    ComPtr<ID3D12Resource> base;
    void* baseMapped = nullptr;
    if (session.sharedInputs) {
        base = hostBuffer(session.renderGpu.Get(), session.linearColorBytes, D3D12_HEAP_TYPE_UPLOAD);
        check(base->Map(0, &empty, &baseMapped), "Mapping HUD-less input");
        std::memset(baseMapped, 0, static_cast<size_t>(session.linearColorBytes));
    }
    for (unsigned plane = 0; plane < 2; ++plane) {
        auto* bytes = static_cast<uint8_t*>(mapped) + plane * session.colorPlaneBytes;
        if (baseMapped && plane == Hudless) bytes = static_cast<uint8_t*>(baseMapped);
        for (unsigned y = 0; y < session.height; ++y) {
            auto* row = bytes + UINT64(y) * session.colorStride;
            for (unsigned x = 0; x < session.width; ++x) {
                if (session.transport == Rgb24) {
                    // Stale final data outside the mask must be ignored even if the row was copied.
                    const auto value = session.sharedInputs && plane == Final && !changed(x, y)
                        ? 0x13579bu : rgb(x, y, plane);
                    auto* pixel = row + x * 3;
                    if (session.sharedInputs && plane == Final) {
                        const auto tile = UINT64(y / ColorTileHeight) * session.colorTilesAcross() + x / ColorTileWidth;
                        pixel = bytes + tile * ColorTileBytes
                            + (y % ColorTileHeight * ColorTileWidth + x % ColorTileWidth) * 3;
                    }
                    for (unsigned channel = 0; channel < 3; ++channel)
                        pixel[channel] = static_cast<uint8_t>(value >> (8 * channel));
                } else {
                    row[x * 2] = static_cast<uint8_t>(luma(x, y, plane));
                    row[x * 2 + 1] = 128;
                    row[(x & ~1u) * 2 + 3] = 128;
                }
            }
        }
    }
    if (session.sharedInputs) {
        auto* masks = reinterpret_cast<uint32_t*>(static_cast<uint8_t*>(mapped) + session.colorPlaneBytes);
        const unsigned stride = (session.width + 127) / 128;
        for (unsigned y = 0; y < session.height; ++y)
            for (unsigned x = 0; x < session.width; x += 4)
                if (changed(x, y)) masks[y * stride + x / 128] |= 1u << ((x / 4) % 32);
    }
    input->Unmap(0, nullptr);
    Commands hudFill;
    if (base) {
        base->Unmap(0, nullptr);
        hudFill.create(session.renderGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
        hudFill.begin()->CopyBufferRegion(slot.hudBase.glBuffer.Get(), 0, base.Get(), 0, session.linearColorBytes);
        hudFill.submit(session.hudQueue.Get());
        check(session.hudQueue->Signal(session.hudWritten.Get(), session.sequence), "Publishing synthetic HUD-less");
        session.submitHudless(index, session.sequence);
    }
    Commands fill;
    fill.create(session.renderGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
    fill.begin()->CopyBufferRegion(slot.groups[Color].glBuffer.Get(), 0, input.Get(), 0, session.groupBytes[Color]);
    fill.submit(session.copyQueue.Get());
    const auto token = transferToken(session.sequence, Color);
    if (delayedPublication) {
        ComPtr<ID3D12Fence> filled, basePrepared;
        check(session.renderGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&filled)),
              "Creating delayed color upload fence");
        check(session.generationGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&basePrepared)),
              "Creating early Arc color preparation fence");
        check(session.copyQueue->Signal(filled.Get(), 1), "Finishing the simulated producer upload");
        check(session.prepareQueue->Signal(basePrepared.Get(), 1), "Finishing early Arc color preparation");
        require(waitForFence(filled.Get(), 1), "Simulated producer upload did not retire");
        require(waitForFence(basePrepared.Get(), 1) && session.glWritten->GetCompletedValue() < token
                && session.prepared->GetCompletedValue() < session.sequence,
                "Arc HUD-less preparation depended on final color or published the frame early");
        session.submitColor(index, session.sequence, true);
        std::this_thread::sleep_for(std::chrono::milliseconds(24));
        const bool maskUnpublished = session.copyBaseDone->GetCompletedValue() < token;
        check(session.glWritten->Signal(token), "Publishing delayed OpenGL color");
        require(maskUnpublished, "HUD mask reached CPU before producer publication");
    } else {
        check(session.copyQueue->Signal(session.glWritten.Get(), token), "Publishing packed color");
        session.submitColor(index, session.sequence, true);
    }
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

void markSyntheticFrame(Session& session, uint32_t frame) {
    std::lock_guard guard(session.xellMutex);
    const auto result = xellSleep(session.ll, frame);
    if (result != XELL_RESULT_ERROR_INVALID_ARGUMENT) checkXeLL(result, "Probe xellSleep", frame);
    recordMarker(session.ll, frame, XELL_SIMULATION_START);
    recordMarker(session.ll, frame, XELL_SIMULATION_END);
    recordMarker(session.ll, frame, XELL_RENDERSUBMIT_START);
}

enum class ProbePresent { Discard, Interpolate, RealOnly };

void verifySharedGeometry(Session& session, bool reconstruct, HudCoverage coverage, bool overrides = false,
                          ProbePresent present = ProbePresent::Discard, bool delayedPublication = false) {
    const auto sequence = session.sequence + 1;
    const int index = session.acquire(sequence, static_cast<int64_t>(sequence));
    require(index >= 0, "Acquiring shared-input frame");
    auto& slot = session.slots[index];
    const auto& motion = session.footprints[Motion];
    const auto bytes = reconstruct && !overrides ? motion.Offset : session.groupBytes[Geometry];
    auto input = hostBuffer(session.renderGpu.Get(), bytes, D3D12_HEAP_TYPE_UPLOAD);
    const D3D12_RANGE empty{0, 0};
    void* mapped = nullptr;
    check(input->Map(0, &empty, &mapped), "Mapping shared geometry");
    const auto format = session.footprints[Depth].Footprint;
    require(format.RowPitch == motion.Footprint.RowPitch, "Depth and motion footprints differed");
    constexpr uint32_t sky = 0x3f800000, ground = 0x3e800000;
    constexpr uint32_t skyMotion = 0xbc004000, groundMotion = 0x3c00c000;
    constexpr uint32_t packedMotion = 0xbda03a01;
    for (unsigned y = 0; y < session.geometryHeight; ++y) {
        auto* row = static_cast<uint8_t*>(mapped) + UINT64(y) * format.RowPitch;
        for (unsigned x = 0; x < session.geometryWidth; ++x) {
            reinterpret_cast<uint32_t*>(row)[x] = x < 37 ? sky : ground;
            if (!reconstruct || overrides) {
                reinterpret_cast<uint32_t*>(row + motion.Offset)[x] =
                    !reconstruct || x == 20 ? packedMotion : 0x7e007e00u;
            }
        }
    }
    input->Unmap(0, nullptr);
    Commands fill;
    fill.create(session.renderGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
    fill.begin()->CopyBufferRegion(slot.groups[Geometry].glBuffer.Get(), 0, input.Get(), 0, bytes);
    fill.submit(session.copyQueue.Get());
    check(session.copyQueue->Signal(session.glWritten.Get(), transferToken(sequence, Geometry)), "Publishing shared geometry");
    const float dx = 4.0f / session.geometryWidth;
    const float dy = 2.0f / session.geometryHeight;
    const Reprojection reprojection{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -dx, -dy, 0, 1,
                                    1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, dx, dy, 0, 1, 0.25f, -0.25f, float(session.geometryWidth), float(session.geometryHeight)};
    session.submitGeometry(index, sequence, reconstruct ? &reprojection : nullptr, overrides);
    require(slot.geometryBytes == bytes, "Incorrect cross-adapter geometry byte count");
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
    for (unsigned y = 0; y < session.geometryHeight; ++y) {
        auto* row = static_cast<const uint8_t*>(mapped) + UINT64(y) * format.RowPitch;
        for (unsigned x = 0; x < session.geometryWidth; ++x) {
            const auto receivedDepth = reinterpret_cast<const uint32_t*>(row)[x];
            const bool rebuilt = reconstruct && !(overrides && x == 20);
            const auto expected = rebuilt ? (x < 37 ? skyMotion : groundMotion) : packedMotion;
            const auto receivedMotion = reinterpret_cast<const uint32_t*>(row + motion.Offset)[x];
            // Permit one adjacent half-float value after the GPU projection and quantization.
            const bool motionMatches = rebuilt
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
    require(matches, "Shared geometry was incorrect before color publication");
    require(session.prepared->GetCompletedValue() < sequence, "Geometry made the entire frame ready without color");
    verifyPackedColor(session, index, coverage, delayedPublication);
    std::array<int64_t, GpuProfileFields * SlotCount> profile{};
    const auto rows = session.pollProfile(profile.data());
    // The fragmented mask has 65 dirty tiles. Keep the large gap before tile
    // 158 and copy one clean tile to fit the other runs into 64 COPY commands.
    const auto copiedTiles = coverage == HudCoverage::Dense ? session.colorTileCount()
        : coverage == HudCoverage::OneTile ? 1 : coverage == HudCoverage::Fragmented ? 66 : 0;
    const auto colorBytes = session.linearColorBytes + session.hudMaskBytes() + copiedTiles * ColorTileBytes;
    const auto denseBytes = session.linearColorBytes + session.hudMaskBytes() + session.colorTileCount() * ColorTileBytes;
    const auto reported = static_cast<UINT64>(profile[GpuProfileFields - 5]);
    const auto route = static_cast<HudCopyRoute>(profile[GpuProfileFields - 3]);
    require(rows == 1 && profile.front() == sequence && (reported == colorBytes || reported == denseBytes),
            "RGB24 profile disagreed with the tile or deadline fallback byte count");
    if (delayedPublication) {
        require(reported == colorBytes && route == HudCopyRoute::Sparse,
                "Delayed GL publication caused a sparse HUD to fall back to full color");
        const auto earlyEnd = profile[GpuProfileFields - 1];
        require(earlyEnd > 0 && earlyEnd < profile[7], "Arc seed did not finish before the final patch");
        std::puts("  Arc HUD-less preparation completed before final color; RGB24 stayed sparse after a 24 ms producer delay");
    }
    if (present == ProbePresent::Discard && colorBytes != denseBytes) {
        // Validate the range plan with a completed mask even if the timed worker chose its dense fallback.
        Commands recopy;
        recopy.create(session.renderGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
        const auto plan = session.copyChangedColor(recopy.begin(), slot.groups[Color], true);
        require(plan.route == HudCopyRoute::Sparse && plan.bytes == copiedTiles * ColorTileBytes,
                "A completed sparse mask did not select the expected tiles");
        recopy.submit(session.copyQueue.Get());
        ComPtr<ID3D12Fence> doneCopy;
        check(session.renderGpu->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&doneCopy)), "Creating tile-plan fence");
        check(session.copyQueue->Signal(doneCopy.Get(), 1), "Retiring tile-plan check");
        require(waitForFence(doneCopy.Get(), 1), "Tile-plan check did not retire");
        if (coverage == HudCoverage::Fragmented)
            std::puts("  65 separated HUD tiles fit in 64 COPY ranges with one clean tile and exact output");
    }
    if (present != ProbePresent::Discard) {
        const auto frame = static_cast<uint32_t>(sequence);
        markSyntheticFrame(session, frame);
        Constants constants{};
        for (unsigned i = 0; i < 16; i += 5) constants.view[i] = constants.projection[i] = 1;
        constants.scaleX = constants.scaleY = 1;
        constants.frameMs = 32;
        constants.resetHistory = sequence == 1;
        require(session.enqueue({static_cast<unsigned>(index), frame, sequence, present == ProbePresent::Interpolate, false, false,
                                 constants, static_cast<int64_t>(sequence), 0}), "Enqueuing synthetic proxy present");
    } else {
        queueDiscard(session, index, sequence);
    }
    const bool retired = waitForFence(session.completed.Get(), sequence);
    session.checkPresenter();
    require(retired, "Shared-input frame did not retire");
}

void verifyColorOnlyPresent(Session& session, bool vsync) {
    const auto sequence = session.sequence + 1;
    const int index = session.acquire(sequence, static_cast<int64_t>(sequence), true);
    require(index == GeometrySlots, "Real-only presentation did not use its dedicated transfer slot");
    const auto frame = static_cast<uint32_t>(sequence);
    markSyntheticFrame(session, frame);
    auto input = hostBuffer(session.renderGpu.Get(), session.linearColorBytes, D3D12_HEAP_TYPE_UPLOAD);
    const D3D12_RANGE empty{0, 0};
    void* mapped = nullptr;
    check(input->Map(0, &empty, &mapped), "Mapping real-only probe color");
    std::memset(mapped, 0x65, static_cast<size_t>(session.linearColorBytes));
    input->Unmap(0, nullptr);
    Commands copy;
    copy.create(session.renderGpu.Get(), D3D12_COMMAND_LIST_TYPE_COPY);
    copy.begin()->CopyBufferRegion(session.slots[index].groups[Color].glBuffer.Get(), 0, input.Get(), 0,
                                   session.linearColorBytes);
    copy.submit(session.copyQueue.Get());
    check(session.copyQueue->Signal(session.glWritten.Get(), transferToken(sequence, Color)), "Publishing real-only probe color");
    session.submitColor(index, sequence, false);
    require(session.enqueue({static_cast<unsigned>(index), frame, sequence, false, vsync, false, {},
                             static_cast<int64_t>(sequence), 0}), "Enqueuing real-only proxy present");
    const bool retired = waitForFence(session.completed.Get(), sequence);
    session.checkPresenter();
    require(retired, "Real-only presentation did not retire");
    std::array<int64_t, GpuProfileFields * SlotCount> profile{};
    require(session.pollProfile(profile.data()) == 1 && profile.front() == sequence, "Real-only profile did not retire");
}

void verifySaturation(Session& session) {
    QueueGate gate;
    gate.hold(session, session.prepareQueue.Get());
    std::array<int, GeometrySlots> indices{};
    const auto first = session.sequence + 1;
    for (unsigned i = 0; i < GeometrySlots; ++i) {
        indices[i] = session.acquire(first + i, 0);
        require(indices[i] >= 0, "Expected a free full-input slot");
        queueDiscard(session, indices[i], first + i);
    }
    std::sort(indices.begin(), indices.end());
    for (unsigned i = 0; i < GeometrySlots; ++i) require(indices[i] == i, "Reused a slot before its fence");
    const auto fifth = first + GeometrySlots;
    const bool full = session.slotsInUse() == GeometrySlots && session.acquire(fifth, 0) == -1;
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

void verifyProducerBackpressure(Session& session) {
    const auto first = session.sequence + 1;
    const auto secondColor = first + 2;
    const auto blocked = secondColor + 1;
    struct ReleaseProducer {
        Session& session;
        UINT64 token;
        ~ReleaseProducer() { session.glWritten->Signal(token); }
    } release{session, transferToken(secondColor, Color)};

    for (auto frame : {first, first + 1, secondColor}) {
        const int slot = session.acquire(frame, 0);
        require(slot >= 0, "Acquiring producer test frame");
        if (frame == first + 1) session.submitGeometry(slot, frame);
        else session.submitColor(slot, frame, false);
        queueDiscard(session, slot, frame);
    }
    require(session.slotsInUse() == 3 && session.acquire(blocked, 0) == ProducerBackpressure,
            "Third OpenGL color was admitted before either of the first two completed");
    require(session.acquire(blocked, 0, true) == ProducerBackpressure, "Real-only bypassed the two-color limit");
    session.waitForProducer(true);
    require(session.producerWaitToken == transferToken(first, Color), "Wait targeted a discarded frame or the newest color");
    check(session.glWritten->Signal(transferToken(first, Color)), "Completing the oldest OpenGL color");
    require(WaitForSingleObject(session.producerReady.value, 2000) == WAIT_OBJECT_0, "Producer event did not wake");
    const int slot = session.acquire(blocked, 0);
    require(slot >= 0, "Frame was not admitted after the oldest OpenGL color completed");
    queueDiscard(session, slot, blocked);
    check(session.glWritten->Signal(release.token), "Completing remaining OpenGL color work");
    require(waitForFence(session.prepared.Get(), blocked), "Producer test transfer slots did not retire");

    const auto full = blocked + 1;
    const int fullSlot = session.acquire(full, 0);
    require(fullSlot >= 0, "Acquiring a complete producer test frame");
    release.token = transferToken(full, Color);
    session.submitGeometry(fullSlot, full);
    check(session.hudWritten->Signal(full), "Publishing synthetic HUD-less base for admission");
    session.submitHudless(fullSlot, full);
    session.submitColor(fullSlot, full, true);
    queueDiscard(session, fullSlot, full);
    check(session.glWritten->Signal(transferToken(full, Geometry)), "Completing only OpenGL geometry");
    require(session.acquire(full + 1, 0) == ProducerBackpressure,
            "A new export was admitted before the previous complete frame's OpenGL color");
    for (int attempt = 0; attempt < 5; ++attempt) {
        session.waitForProducer(false);
        require(session.acquire(full + 1, 0) == ProducerBackpressure, "A producer wait slice bypassed the blocked fence");
    }
    require(session.producerWaitToken == release.token, "The deferred wait targeted the wrong color");
    const int realOnly = session.acquire(full + 1, 0, true);
    require(realOnly == GeometrySlots, "A real-only job could not bypass the most recent color fence");
    release.token = transferToken(full + 1, Color);
    session.submitColor(realOnly, full + 1, false);
    queueDiscard(session, realOnly, full + 1);
    require(session.acquire(full + 2, 0, true) == ProducerBackpressure,
            "A real-only job was admitted with two unpublished colors");
    check(session.glWritten->Signal(transferToken(full, Color)), "Completing the preceding OpenGL color");
    require(WaitForSingleObject(session.producerReady.value, 2000) == WAIT_OBJECT_0,
            "Deferred producer wait did not wake on the preceding color");
    const int nextSlot = session.acquire(full + 2, 0, true);
    require(nextSlot >= 0, "Deferred export failed to acquire its slot after color completion");
    queueDiscard(session, nextSlot, full + 2);
    check(session.glWritten->Signal(release.token), "Completing the real-only OpenGL color");
    require(waitForFence(session.prepared.Get(), full + 2), "Deferred producer test did not retire its final slots");
    std::puts("  A real-only export could use the reserved slot, with at most two unfinished OpenGL colors");
}

void verifyOutputBackpressure(HWND window, jlong arc, unsigned outputsPerGroup, unsigned groupsBeforePressure) {
    ComPtr<IDXGIFactory4> factory;
    check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)), "Creating probe output factory");
    Session session;
    session.generationGpu = device(factory.Get(), arc);
    const auto writer = queue(session.generationGpu.Get(), D3D12_COMMAND_LIST_TYPE_DIRECT);
    LARGE_INTEGER frequency{};
    QueryPerformanceFrequency(&frequency);
    cmi::xessfg::pacing::AsyncOutput output(2, frequency.QuadPart / 240, frequency.QuadPart, nullptr);
    DXGI_SWAP_CHAIN_DESC1 description{};
    description.Width = description.Height = 64;
    description.Format = Formats[Final];
    description.SampleDesc.Count = 1;
    description.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    description.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    ComPtr<IDXGISwapChain4> swap;
    check(output.create(factory.Get(), writer.Get(), window, description, nullptr, nullptr, swap), "Creating probe output");
    output.start(factory.Get());
    QueueGate gate;
    gate.hold(session, writer.Get());

    const bool interpolate = outputsPerGroup > 1;
    const auto reserve = [&](unsigned id, auto wait) {
        return output.reserve(id, id, interpolate, false, false, wait);
    };
    const auto capture = [&](unsigned id) {
        for (unsigned i = 0; i < outputsPerGroup; ++i)
            require(output.capture(1, 0) == S_OK, "Capturing a probe output image failed");
        output.confirm(id, outputsPerGroup);
    };
    for (unsigned id = 1; id <= groupsBeforePressure; ++id) {
        require(reserve(id, [](HANDLE) { return false; }) == S_OK, "Initial output reservation stalled");
        capture(id);
    }

    const auto next = groupsBeforePressure + 1;
    bool saturated = false;
    require(reserve(next, [&](HANDLE) { saturated = true; return false; }) == DXGI_STATUS_OCCLUDED
            && saturated && output.error() == S_OK, "Temporary output pressure poisoned the session");
    bool released = false;
    const auto status = reserve(next, [&](HANDLE ready) {
        if (!released) {
            check(gate.fence->Signal(1), "Releasing the output writer gate");
            released = true;
        }
        if (MsgWaitForMultipleObjectsEx(1, &ready, 4, QS_ALLINPUT, MWMO_INPUTAVAILABLE) == WAIT_FAILED)
            throw std::runtime_error("Waiting for probe output reservation");
        std::optional<int> quit;
        Session::pumpMessages(quit);
        return !quit;
    });
    require(status == S_OK && released && output.error() == S_OK, "Output capacity did not recover after GPU progress");
    capture(next);
    require(output.count() == next * outputsPerGroup, "Output reservation recovery lost or duplicated a frame");
}

void verifyPopup(jlong render, jlong arc, bool requireDisplay, UINT multiplier) {
    constexpr int width = 803, height = 611;
    RECT workArea{};
    require(SystemParametersInfoW(SPI_GETWORKAREA, 0, &workArea, 0), "Reading the probe display work area");
    const LONG left = std::max(workArea.left, workArea.right - width);
    const LONG top = std::max(workArea.top, workArea.bottom - height);
    const auto owner = CreateWindowExW(WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW, L"STATIC", L"XeSS-FG popup owner",
        WS_POPUP | WS_VISIBLE, left, top, width, height, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    require(owner, "Creating popup owner");
    try {
        Session session;
        session.start(owner, render, arc, width, height, Rgb24, true, true, true,
                      (width + 1) / 2, (height + 1) / 2, true, multiplier);
        require(session.interpolatedFrames == multiplier - 1, "The proxy did not apply the requested multiplier");
        std::printf("  proxy initialized at %ux\n", multiplier);
        const auto wnd = session.surface;
        const auto styles = GetWindowLongPtrW(wnd, GWL_STYLE);
        const auto extended = GetWindowLongPtrW(wnd, GWL_EXSTYLE);
        require((styles & WS_POPUP) && !(styles & WS_CHILD) && !IsWindowEnabled(wnd)
                && (extended & WS_EX_NOACTIVATE) && !(extended & WS_EX_TOPMOST)
                && GetWindow(wnd, GW_OWNER) == owner && GetAncestor(wnd, GA_ROOT) == wnd
                && GetWindowThreadProcessId(wnd, nullptr) != GetCurrentThreadId(), "Popup style or input ownership mismatch");
        require(session.positionPopup(), "Showing a popup over its owner");
        require(SetWindowPos(owner, HWND_TOP, left - 8, top - 8, width, height, SWP_NOACTIVATE),
                "Moving the popup owner");
        RECT actual{}, expected{};
        require(session.positionPopup() && GetWindowRect(wnd, &actual) && clientOnScreen(owner, expected)
                && EqualRect(&actual, &expected), "Popup did not track its owner's client rectangle");
        UpdateWindow(owner);
        std::optional<int> quit;
        Session::pumpMessages(quit);
        if (requireDisplay) {
            // Give an external PresentMon session time to attach to this process.
            std::this_thread::sleep_for(std::chrono::seconds(2));
            Session::pumpMessages(quit);
        }
        for (int i = 0; i < 3; ++i) verifySharedGeometry(session, true, HudCoverage::OneTile, true, ProbePresent::Interpolate);
        verifyColorOnlyPresent(session, false);
        verifySharedGeometry(session, true, HudCoverage::OneTile, true, ProbePresent::RealOnly);
        for (int i = 0; i < 2; ++i) verifySharedGeometry(session, true, HudCoverage::OneTile, true, ProbePresent::Interpolate);
        verifyColorOnlyPresent(session, true);
        constexpr int ExpectedRows = 8;
        std::array<int64_t, ResultCapacity * PresentResultFields> results{};
        int rows = 0;
        for (int attempt = 0; rows < ExpectedRows && attempt < 50; ++attempt) {
            rows += session.pollPresents(results.data() + rows * PresentResultFields);
            session.checkPresenter();
            if (rows < ExpectedRows) std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        require(rows == ExpectedRows, "Missing synthetic proxy present results");
        bool generated = false;
        bool reset = true;
        for (int i = 0; i < rows; ++i) {
            const auto* row = results.data() + i * PresentResultFields;
            const bool colorOnly = i == 3 || i == 7;
            const bool lateBypass = i == 4;
            const bool interpolate = !colorOnly && !lateBypass;
            const bool sync = interpolate || i == 7;
            require(row[0] == i + 1 && row[3 + SyncInterval] == (sync ? 1 : 0),
                    "Proxy present IDs or sync interval changed");
            require(row[3 + PresentFlags] == (!sync && session.tearing ? DXGI_PRESENT_ALLOW_TEARING : 0),
                    "Proxy tearing flag did not follow the selected sync interval");
            std::printf("  proxy frame %lld: queued %lld, DXGI %lld, FG result %lld\n", row[0], row[1],
                        row[3 + DxgiResult], row[3 + FrameGenResult]);
            const bool outputOccluded = session.output->occludedThrough() >= static_cast<uint64_t>(i);
            require((row[2] != 0) == (interpolate && reset)
                    || (interpolate && row[2] && outputOccluded), "XeSS-FG proxy history reset changed");
            require(row[3 + DxgiResult] == S_OK || row[3 + DxgiResult] == DXGI_STATUS_OCCLUDED,
                    "Synthetic proxy present returned an unexpected DXGI result");
            if (!interpolate) require(row[1] <= 1, "Real-only frame generated an image");
            if (colorOnly) require(row[3 + GeometryBytes] == 0, "Color-only frame transferred geometry");
            if (lateBypass) require(row[3 + GeometryBytes] > 0 && row[3 + ColorBytes] == Unavailable,
                                    "Late bypass lost the shared-input transfer accounting");
            reset = !interpolate || row[1] == 0;
            require(row[1] <= multiplier, "Present reported more frames than the selected multiplier");
            generated |= row[1] == multiplier;
        }
        if (!generated) {
            require(!requireDisplay && (results[3 + DxgiResult] == DXGI_STATUS_OCCLUDED
                            || session.output->occludedThrough()),
                    "XeSS-FG did not queue a complete generated group at the requested multiplier");
            std::puts("Display coverage unavailable: DXGI marked the proxy window occluded");
        }
        if (requireDisplay) std::this_thread::sleep_for(std::chrono::milliseconds(250));
        require(SetWindowPos(owner, nullptr, 0, 0, width - 1, height, SWP_NOMOVE | SWP_NOACTIVATE | SWP_NOZORDER),
                "Resizing the popup owner");
        require(!session.positionPopup() && !IsWindowVisible(wnd), "Popup showed a stale size while its owner resized");
        std::puts("PASS: proxy surface, synthetic input and ordered presentation");
    } catch (...) {
        DestroyWindow(owner);
        throw;
    }
    DestroyWindow(owner);
}
}

void verifyOffline() {
    using cmi::xessfg::pacing::cancelRealDelay;
    using cmi::xessfg::pacing::DelayCancellation;
    using cmi::xessfg::pacing::evenlySpacedRealSlot;
    using cmi::xessfg::pacing::OutputGroup;
    using cmi::xessfg::pacing::realSubmissionTime;
    using cmi::xessfg::pacing::UnknownSlot;
    const auto first = evenlySpacedRealSlot(0, {3}, 1, 4);
    require(first == 5 && evenlySpacedRealSlot(first, {7}, 1, 8) == 9,
            "The one-refresh delay did not converge to 2:2 spacing");
    require(evenlySpacedRealSlot(0, {4}, 1, 5) == 6,
            "A 4:1 split exceeded the one-refresh limit");
    require(evenlySpacedRealSlot(0, {2, 4}, 2, 5) == 6
            && evenlySpacedRealSlot(0, {2, 4, 6}, 3, 7) == 8,
            "Multi-frame output did not use the same real-only adjustment");
    require(evenlySpacedRealSlot(0, {1, 3, 4}, 3, 5) == 5
            && evenlySpacedRealSlot(0, {2}, 1, 4) == 4
            && evenlySpacedRealSlot(UnknownSlot, {3}, 1, 4) == 4,
            "An existing or unverified real slot was unnecessarily delayed");
    require(realSubmissionTime(91, 10, 95, 105) == 101
            && realSubmissionTime(91, 10, 105, 115) == 91
            && realSubmissionTime(106, 10, 95, 105) == 106,
            "Real output waited past its one-refresh budget or for an unreachable slot");
    require(cancelRealDelay(true, false, true) == DelayCancellation::NextImage
            && cancelRealDelay(true, true, false) == DelayCancellation::OutputCapacity
            && cancelRealDelay(false, false, false) == DelayCancellation::ClockChanged
            && cancelRealDelay(true, false, false) == DelayCancellation::None,
            "A real-frame timer could hold up another output or outlive the refresh clock");
    for (unsigned multiplier = 2; multiplier <= 4; ++multiplier) {
        OutputGroup group{};
        group.interpolate = true;
        group.captured = 1;
        require(!group.real(0) && group.acceptsImage(), "Unconfirmed output was treated as a real frame");
        group.submitted = 1;
        require(!group.completed() && group.confirm(multiplier, multiplier),
                "An early output could not be forwarded before SDK confirmation");
        group.captured = group.submitted = multiplier;
        require(group.completed() && !group.acceptsImage() && group.real(multiplier - 1)
                && group.generated(multiplier - 2), "Confirmed output lost its expected order");
    }
    OutputGroup shortGroup{};
    shortGroup.interpolate = true;
    shortGroup.captured = 1;
    require(!shortGroup.confirm(0, 4) && shortGroup.confirm(1, 4) && shortGroup.real(0),
            "Reduced SDK output was not classified as one real frame");
    OutputGroup hiddenGroup{};
    require(hiddenGroup.confirm(0, 4) && hiddenGroup.completed() && !hiddenGroup.acceptsImage(),
            "An occluded group left pending output");

    verifyPendingCapacity();
    require(interpolationCount(2, 1) == 1 && interpolationCount(3, 3) == 2 && interpolationCount(4, 3) == 3,
            "Multiplier did not map to the configured number of generated frames");
    bool unavailable = false;
    try { interpolationCount(4, 1); }
    catch (const UnsupportedMultiplier&) { unavailable = true; }
    require(unavailable, "Unsupported multiplier was accepted");

    unsigned calls = 0;
    require(retryPresent([&] { return ++calls < 3 ? DXGI_ERROR_WAS_STILL_DRAWING : S_OK; }, [] { return true; })
            == S_OK && calls == 3, "Transient backpressure did not retry the same Present");
    calls = 0;
    require(retryPresent([&] { ++calls; return DXGI_ERROR_WAS_STILL_DRAWING; }, [] { return true; })
            == DXGI_ERROR_WAS_STILL_DRAWING && calls == 3,
            "Persistent Present backpressure was not handed back to the fallback path");
    require(retryPresent([] { return DXGI_ERROR_WAS_STILL_DRAWING; }, [] { return false; })
            == DXGI_STATUS_OCCLUDED, "A stopping presenter did not leave its retry wait");
    require(retryPresent([] { return DXGI_ERROR_DEVICE_REMOVED; }, [] { return false; })
            == DXGI_ERROR_DEVICE_REMOVED, "Device removal was masked as a transient condition");
    require(ColorDecoder::compile(false) && ColorDecoder::compile(true), "Input shaders did not compile");
    require(ColorTileBytes == 3072, "RGB24 tile layout changed");
    std::puts("PASS: queue ordering, multiplier selection, bounded Present retry and D3D12 input shaders");
}

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    std::setvbuf(stderr, nullptr, _IONBF, 0);
    if (argc == 2 && std::strcmp(argv[1], "--offline") == 0) {
        try {
            verifyOffline();
            return 0;
        } catch (const std::exception& error) {
            std::fprintf(stderr, "%s\n", error.what());
            return 1;
        }
    }
    const std::string_view mode = argc > 1 ? argv[1] : "";
    const bool requireDisplay = mode == "--display";
    const bool verifySdk = requireDisplay || mode == "--sdk";
    UINT multiplier = 2;
    if (verifySdk && argc == 3) {
        const std::string_view selection = argv[2];
        if (selection != "2" && selection != "3" && selection != "4") {
            std::fputs("Expected a XeSS-FG multiplier of 2, 3, or 4\n", stderr);
            return 1;
        }
        multiplier = static_cast<UINT>(selection.front() - '0');
    }
    std::puts("Creating the probe host window");
    HWND window = CreateWindowExW(0, L"STATIC", L"XeSS-FG pipeline probe", WS_OVERLAPPEDWINDOW,
                                 0, 0, 1400, 800, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    if (!window) return 1;
    int result = 0;
    try {
        verifyPendingCapacity();
        const auto render = adapter(0x10de, L"RTX 4070 Laptop");
        const auto arc = adapter(0x8086, L"A750");
        std::printf("Dual-adapter probe: render LUID %llx, Arc LUID %llx\n", render, arc);
        if (verifySdk) {
            std::puts("Checking XeSS-FG proxy and presentation surface");
            verifyPopup(render, arc, requireDisplay, multiplier);
            DestroyWindow(window);
            return 0;
        }
        std::puts("Checking output reservation recovery after texture and group queue pressure");
        verifyOutputBackpressure(window, arc, 2, 2);
        verifyOutputBackpressure(window, arc, 1, cmi::xessfg::pacing::MaxGroups);
        {
            std::puts("Checking RGBA8 transfer and saturation");
            Session session;
            session.start(window, render, arc, 1283, 719, Rgba8, false, false, false, 0, 0, false);
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
            std::printf("Checking packed transport %u\n", static_cast<unsigned>(mode));
            Session packed;
            std::puts("  initializing session");
            packed.start(window, render, arc, 803, 611, mode, false, true, false, 0, 0, false);
            require(!packed.slots[0].groups[Geometry].local, "Direct geometry retained a staging buffer");
            require(packed.colorStride < packed.footprints[Final].Footprint.RowPitch, "Packed stride did not shrink");
            const int index = packed.acquire(1, 0);
            require(index >= 0, "Acquiring packed frame");
            std::puts("  checking geometry copy");
            verifyTransfer(packed, index, Geometry, 0x3f340125);
            std::puts("  checking packed color");
            verifyPackedColor(packed, index);
            queueDiscard(packed, index, 1);
            require(waitForFence(packed.completed.Get(), 1), "Packed frame did not retire");
            std::puts("  checking input ring reuse");
            verifyInputReuse(packed);
            const auto partialFrame = packed.sequence + 1;
            const int partial = packed.acquire(partialFrame, 0);
            require(partial >= 0, "Acquiring direct geometry without color");
            verifyTransfer(packed, partial, Geometry, 0x3f803abc);
            queueDiscard(packed, partial, partialFrame);
            require(waitForFence(packed.prepared.Get(), partialFrame), "Direct geometry did not retire without color");
            std::puts("  closing packed session");
        }
        {
            std::puts("Checking Arc shared geometry and HUD tiles");
            Session shared;
            shared.start(window, render, arc, 1921, 161, Rgb24, false, true, true, 0, 0, false);
            require(shared.sharedInputs && !shared.uploadQueue, "Arc shared-input path was not active");
            verifyRetirementProgress(shared.generationGpu.Get());
            verifySharedGeometry(shared, true, HudCoverage::Dense);
            verifySharedGeometry(shared, false, HudCoverage::OneTile, false, ProbePresent::Discard, true);
            verifySharedGeometry(shared, true, HudCoverage::Empty);
            verifySharedGeometry(shared, true, HudCoverage::OneTile, true);
            verifySharedGeometry(shared, true, HudCoverage::Fragmented);
            verifyInputReuse(shared);
            const auto frame = shared.sequence + 1;
            const int slot = shared.acquire(frame, 0);
            require(slot >= 0, "Acquiring shared geometry without color");
            check(shared.copyQueue->Signal(shared.glWritten.Get(), transferToken(frame, Geometry)), "Publishing partial shared input");
            shared.submitGeometry(slot, frame);
            queueDiscard(shared, slot, frame);
            require(waitForFence(shared.prepared.Get(), frame), "Partial shared input did not retire");
        }
        {
            std::puts("Checking producer admission");
            Session paced;
            paced.start(window, render, arc, 129, 73, Rgb24, false, true, true, 0, 0, false);
            require(paced.sharedInputs, "Producer test requires shared inputs");
            verifyProducerBackpressure(paced);
        }
        for (bool sharedInputs : {false, true}) {
            std::printf("Checking producer device removal (shared %d)\n", sharedInputs);
            ComPtr<ID3D12Fence> staged;
            UINT64 done;
            {
                Session removed;
                removed.start(window, render, arc, 803, 611, Rgb24, false, true, sharedInputs, 0, 0, false);
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
            std::puts("Checking resize and Arc device removal");
            Session resized;
            resized.start(window, arc, arc, 803, 611, Rgba8, false, false, false, 0, 0, false);
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
