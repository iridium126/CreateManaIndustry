#pragma once

#include "PresentPacer.h"
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <evntprov.h>
#include <wrl/client.h>
#include <atomic>
#include <chrono>
#include <algorithm>
#include <cstdlib>
#include <mutex>
#include <stdexcept>
#include <thread>

namespace cmi::xessfg::pacing {
using Microsoft::WRL::ComPtr;

inline int64_t counter() {
    LARGE_INTEGER value{};
    QueryPerformanceCounter(&value);
    return value.QuadPart;
}

// Intel-PresentMon public PresentFrameType event, version 1. Emit it on the
// forwarding thread, where PresentMon associates it with the following Present.
class PresentFrameEvents {
public:
    PresentFrameEvents() { EventRegister(&Provider, nullptr, nullptr, &handle_); }
    ~PresentFrameEvents() { if (handle_) EventUnregister(handle_); }

    void mark(uint32_t id, bool generated, bool real) const {
        if (!handle_ || !EventEnabled(handle_, &OutputType)) return;
        const uint8_t type = generated ? 50u : real ? 1u : 0u;
        EVENT_DATA_DESCRIPTOR data[3];
        EventDataDescCreate(data, &id, sizeof(id));
        EventDataDescCreate(data + 1, &type, sizeof(type));
        EventDataDescCreate(data + 2, &id, sizeof(id));
        EventWrite(handle_, &OutputType, 3, data);
    }

private:
    static constexpr GUID Provider{0xecaa4712, 0x4644, 0x442f, {0xb9,0x4c,0xa3,0x2f,0x6c,0xf8,0xa4,0x99}};
    static constexpr EVENT_DESCRIPTOR OutputType{1, 1, 0, 4, 0, 1, 1};
    REGHANDLE handle_ = 0;
};

struct OutputEvent {
    uint64_t sequence;
    uint32_t frameId;
    unsigned ordinal, count;
    bool real;
    int64_t earliestSlot, targetSlot, submitted;
    DelayCancellation cancellation;
    HRESULT result;
};

class AsyncOutput {
    static constexpr unsigned RealBuffers = 3;
    static constexpr unsigned BufferGroups = 2;
    static constexpr unsigned Capacity = BufferGroups * MaxFrames + 1;
    static constexpr auto ProgressTimeout = std::chrono::milliseconds(500);
    struct Image {
        ComPtr<ID3D12Resource> texture;
        ComPtr<ID3D12CommandQueue> writer;
        ComPtr<ID3D12Fence> fence;
        uint64_t value = 0, copiedAt = 0;
        UINT interval = 0, flags = 0;
        bool held = false;
    };
    struct CopyCommands {
        ComPtr<ID3D12CommandAllocator> allocator;
        ComPtr<ID3D12GraphicsCommandList> list;
        uint64_t after = 0;
    };
    struct Event {
        HANDLE handle = nullptr;
        ~Event() { if (handle) CloseHandle(handle); }
    };

public:
    using Observe = void (*)(const OutputEvent&);

    AsyncOutput(unsigned multiplier, int64_t period, int64_t frequency, Observe observe)
        : multiplier_(multiplier), size_(BufferGroups * multiplier + 1), frequency_(frequency),
          period_(period), observe_(observe) {}

    ~AsyncOutput() { stop(); }

    HRESULT create(IDXGIFactory2* factory, IUnknown* device, HWND window, const DXGI_SWAP_CHAIN_DESC1& description,
                   const DXGI_SWAP_CHAIN_FULLSCREEN_DESC* fullscreen, IDXGIOutput* restriction,
                   ComPtr<IDXGISwapChain4>& chain) {
        if (real_)
            return DXGI_ERROR_UNSUPPORTED;
        ComPtr<ID3D12CommandQueue> writer;
        HRESULT status = device->QueryInterface(IID_PPV_ARGS(&writer));
        if (FAILED(status)) return status;
        status = writer->GetDevice(IID_PPV_ARGS(&gpu_));
        if (FAILED(status)) return status;
        D3D12_COMMAND_QUEUE_DESC queue{};
        status = gpu_->CreateCommandQueue(&queue, IID_PPV_ARGS(&flipQueue_));
        if (FAILED(status)) return status;

        auto realDesc = description;
        realDesc.BufferCount = RealBuffers;
        realDesc.Flags |= DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT;
        ComPtr<IDXGISwapChain1> created;
        status = factory->CreateSwapChainForHwnd(flipQueue_.Get(), window, &realDesc, fullscreen, restriction, &created);
        if (FAILED(status)) return status;
        status = created.As(&real_);
        if (FAILED(status)) return status;
        status = real_->SetMaximumFrameLatency(2);
        if (FAILED(status)) return status;
        flipReady_.handle = real_->GetFrameLatencyWaitableObject();
        if (!flipReady_.handle) return E_FAIL;

        window_ = window;
        virtualReady_.handle = CreateEventW(nullptr, TRUE, TRUE, nullptr);
        stop_.handle = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        queued_.handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        reservationReady_.handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        timer_.handle = CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION,
                                               TIMER_MODIFY_STATE | SYNCHRONIZE);
        if (!timer_.handle) timer_.handle = CreateWaitableTimerW(nullptr, FALSE, nullptr);
        if (!virtualReady_.handle || !stop_.handle || !queued_.handle || !reservationReady_.handle
                || !timer_.handle) return E_FAIL;
        initialWriter_ = writer;
        status = rebuildImages();
        if (FAILED(status)) return status;
        chain = real_;
        return S_OK;
    }

    HRESULT resize(UINT count, UINT width, UINT height, DXGI_FORMAT format, UINT flags, IUnknown* const* writers) {
        if (count > DXGI_MAX_SWAP_CHAIN_BUFFERS) return E_INVALIDARG;
        if (running_.load(std::memory_order_acquire)) {
            fail(DXGI_ERROR_INVALID_CALL);
            return DXGI_ERROR_INVALID_CALL;
        }
        for (unsigned i = 0; writers && i < (count ? count : size_); ++i) {
            if (!writers[i]) continue;
            ComPtr<ID3D12CommandQueue> writer;
            const auto status = writers[i]->QueryInterface(IID_PPV_ARGS(&writer));
            if (FAILED(status)) return status;
            if (i < size_) mappedWriters_[i] = writer;
            if (!i) resizeWriter_ = writer;
        }
        for (auto& buffer : back_) buffer.Reset();
        const auto status = real_->ResizeBuffers(RealBuffers, width, height, format,
                                                 flags | DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT);
        if (FAILED(status)) return status;
        return rebuildImages();
    }

    void start(IDXGIFactory1* factory) {
        if (!real_) throw std::runtime_error("XeSS-FG did not create a wrapped output swap chain");
        monitorFactory_ = factory;
        checked(gpu_->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&copied_)));
        for (auto& copy : copies_) {
            checked(gpu_->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&copy.allocator)));
            checked(gpu_->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, copy.allocator.Get(), nullptr,
                                          IID_PPV_ARGS(&copy.list)));
            checked(copy.list->Close());
        }
        copyDone_.handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (!copyDone_.handle) throw std::runtime_error("Creating XeSS-FG output copy event");
        for (unsigned i = 0; i < size_; ++i) {
            writerDone_[i].handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
            if (!writerDone_[i].handle) throw std::runtime_error("Creating XeSS-FG writer event");
        }
        running_.store(true, std::memory_order_release);
        worker_ = std::thread([this] {
            SetThreadDescription(GetCurrentThread(), L"CMI XeSS-FG DXGI Output");
            SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_ABOVE_NORMAL);
            try { run(); } catch (...) { fail(E_FAIL); }
            drainCopy();
        });
        clock_ = std::thread([this] {
            SetThreadDescription(GetCurrentThread(), L"CMI XeSS-FG Refresh Clock");
            SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_ABOVE_NORMAL);
            sampleClock();
        });
    }

    void requestStop() {
        stopping_.store(true, std::memory_order_release);
        if (stop_.handle) SetEvent(stop_.handle);
        if (virtualReady_.handle) SetEvent(virtualReady_.handle);
        if (reservationReady_.handle) SetEvent(reservationReady_.handle);
    }

    void stop() {
        requestStop();
        if (worker_.joinable()) worker_.join();
        if (clock_.joinable()) clock_.join();
    }

    HRESULT error() const { return error_.load(std::memory_order_acquire); }
    unsigned size() const { return size_; }
    unsigned index() const { return index_.load(std::memory_order_acquire); }
    unsigned count() const { return captured_.load(std::memory_order_acquire); }
    uintptr_t address() const { return reinterpret_cast<uintptr_t>(real_.Get()); }
    uint64_t occludedThrough() const { return occluded_.load(std::memory_order_acquire); }
    HANDLE readyHandle() const {
        HANDLE copy = nullptr;
        DuplicateHandle(GetCurrentProcess(), virtualReady_.handle, GetCurrentProcess(), &copy, 0, FALSE,
                        DUPLICATE_SAME_ACCESS);
        return copy;
    }
    HRESULT buffer(UINT index, REFIID type, void** result) const {
        if (index >= size_) return DXGI_ERROR_INVALID_CALL;
        return images_[index].texture->QueryInterface(type, result);
    }

    template<class Wait>
    HRESULT reserve(uint64_t sequence, uint32_t frameId, bool interpolate, bool reset, bool profiled, Wait wait) {
        const auto deadline = progressDeadline();
        for (;;) {
            const auto status = tryReserve(sequence, frameId, interpolate, reset, profiled);
            if (status != DXGI_ERROR_WAS_STILL_DRAWING) return status;
            if (stopped()) return error() == S_OK ? DXGI_STATUS_OCCLUDED : error();
            if (counter() >= deadline) {
                progressTimedOut();
                return error();
            }
            if (!wait(reservationReady_.handle)) {
                std::lock_guard lock(mutex_);
                reservationPending_ = false;
                return DXGI_STATUS_OCCLUDED;
            }
        }
    }

    HRESULT capture(UINT interval, UINT flags) {
        if (flags & DXGI_PRESENT_TEST) return stopped() ? DXGI_STATUS_OCCLUDED : S_OK;
        std::lock_guard lock(mutex_);
        if (stopped()) return DXGI_STATUS_OCCLUDED;
        for (unsigned i = 0; i < groupCount_; ++i) {
            auto& group = groups_[(groupHead_ + i) % MaxGroups];
            if (!group.acceptsImage()) continue;
            if (group.captured >= multiplier_) break;
            const auto index = this->index();
            auto& image = images_[index];
            if (image.held) break;
            const auto status = image.writer->Signal(image.fence.Get(), ++image.value);
            if (FAILED(status)) return fail(status);
            image.copiedAt = 0;
            image.interval = interval;
            image.flags = flags;
            image.held = true;
            group.images[group.captured++] = index;
            index_.store((index + 1) % size_, std::memory_order_release);
            captured_.fetch_add(1, std::memory_order_release);
            SetEvent(queued_.handle);
            return S_OK;
        }
        return fail(DXGI_ERROR_INVALID_CALL);
    }

    void confirm(uint64_t sequence, unsigned actual) {
        std::lock_guard lock(mutex_);
        if (stopped()) return;
        if (!groupCount_) {
            fail(DXGI_ERROR_INVALID_CALL);
            return;
        }
        auto& group = groups_[(groupHead_ + groupCount_ - 1) % MaxGroups];
        if (group.sequence != sequence || !group.confirm(actual, multiplier_)) {
            fail(DXGI_ERROR_INVALID_CALL);
            return;
        }
        SetEvent(queued_.handle);
    }

    void abandon(HRESULT reason) { fail(reason); }

private:
    static void checked(HRESULT result) {
        if (FAILED(result)) throw std::runtime_error("XeSS-FG output D3D12 operation failed");
    }
    bool stopped() const { return stopping_.load(std::memory_order_acquire); }
    HRESULT fail(HRESULT result) {
        HRESULT previous = S_OK;
        error_.compare_exchange_strong(previous, result, std::memory_order_release, std::memory_order_relaxed);
        requestStop();
        return result;
    }
    HRESULT tryReserve(uint64_t sequence, uint32_t frameId, bool interpolate, bool reset, bool profiled) {
        std::lock_guard lock(mutex_);
        if (stopped()) return error() == S_OK ? DXGI_STATUS_OCCLUDED : error();
        const auto status = groupCount_ == MaxGroups ? DXGI_ERROR_WAS_STILL_DRAWING
            : reserveImages(interpolate ? multiplier_ : 1);
        if (status == DXGI_ERROR_WAS_STILL_DRAWING) {
            if (!reservationPending_) {
                reservationPending_ = true;
                SetEvent(queued_.handle);
            }
            return status;
        }
        reservationPending_ = false;
        if (FAILED(status)) return fail(status);
        auto& group = groups_[(groupHead_ + groupCount_++) % MaxGroups];
        group = {};
        group.sequence = sequence;
        group.frameId = frameId;
        group.interpolate = interpolate;
        group.reset = reset;
        group.profiled = profiled;
        SetEvent(queued_.handle);
        return S_OK;
    }
    HRESULT reserveImages(unsigned requested) {
        unsigned promised = requested;
        for (unsigned i = 0; i < groupCount_; ++i) {
            const auto& group = groups_[(groupHead_ + i) % MaxGroups];
            if (!group.confirmed) return DXGI_ERROR_INVALID_CALL;
            promised += group.expected - group.captured;
        }
        if (promised >= size_) return DXGI_ERROR_WAS_STILL_DRAWING;
        const auto copied = copied_->GetCompletedValue();
        if (copied == UINT64_MAX) return DXGI_ERROR_DEVICE_REMOVED;
        for (unsigned i = 0; i <= promised; ++i) {
            auto& image = images_[(index() + i) % size_];
            if (!image.held) continue;
            if (image.copiedAt && image.copiedAt <= copied) {
                image.held = false;
                continue;
            }
            if (image.copiedAt && image.copiedAt != armedReservationCopy_) {
                const auto status = copied_->SetEventOnCompletion(image.copiedAt, reservationReady_.handle);
                if (FAILED(status)) return status;
                armedReservationCopy_ = image.copiedAt;
            }
            return DXGI_ERROR_WAS_STILL_DRAWING;
        }
        return S_OK;
    }

    int64_t progressDeadline() const { return counter() + frequency_ * ProgressTimeout.count() / 1000; }
    void progressTimedOut() {
        const auto removed = gpu_->GetDeviceRemovedReason();
        fail(FAILED(removed) ? removed : DXGI_ERROR_WAIT_TIMEOUT);
    }
    HRESULT rebuildImages() {
        for (unsigned i = 0; i < RealBuffers; ++i) {
            back_[i].Reset();
            const auto status = real_->GetBuffer(i, IID_PPV_ARGS(&back_[i]));
            if (FAILED(status)) return status;
        }
        auto desc = back_[0]->GetDesc();
        D3D12_HEAP_PROPERTIES heap{};
        heap.Type = D3D12_HEAP_TYPE_DEFAULT;
        heap.CreationNodeMask = heap.VisibleNodeMask = 1;
        for (unsigned i = 0; i < size_; ++i) {
            auto& image = images_[i];
            image = {};
            image.writer = mappedWriters_[i] ? mappedWriters_[i] : resizeWriter_ ? resizeWriter_ : initialWriter_;
            auto status = gpu_->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &desc,
                D3D12_RESOURCE_STATE_COMMON, nullptr, IID_PPV_ARGS(&image.texture));
            if (FAILED(status)) return status;
            image.texture->SetName(L"CMI XeSS-FG virtual output");
            status = gpu_->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&image.fence));
            if (FAILED(status)) return status;
        }
        return S_OK;
    }
    ComPtr<IDXGIOutput> findOutput(HMONITOR monitor) const {
        for (UINT adapterIndex = 0;; ++adapterIndex) {
            ComPtr<IDXGIAdapter1> adapter;
            if (FAILED(monitorFactory_->EnumAdapters1(adapterIndex, &adapter))) return {};
            for (UINT outputIndex = 0;; ++outputIndex) {
                ComPtr<IDXGIOutput> output;
                if (FAILED(adapter->EnumOutputs(outputIndex, &output))) break;
                DXGI_OUTPUT_DESC description{};
                if (SUCCEEDED(output->GetDesc(&description)) && description.Monitor == monitor) return output;
            }
        }
    }
    void sampleClock() {
        HMONITOR monitor = nullptr;
        ComPtr<IDXGIOutput> output;
        while (!stopped()) {
            const auto current = MonitorFromWindow(window_, MONITOR_DEFAULTTONULL);
            if (monitor != current) {
                monitor = current;
                output.Reset();
                std::lock_guard lock(clockMutex_);
                anchor_ = sample_ = 0;
                stableSamples_ = 0;
                ++epoch_;
                SetEvent(queued_.handle);
                MONITORINFOEXW info{};
                info.cbSize = sizeof(info);
                DEVMODEW mode{};
                mode.dmSize = sizeof(mode);
                if (monitor && GetMonitorInfoW(monitor, &info)
                        && EnumDisplaySettingsW(info.szDevice, ENUM_CURRENT_SETTINGS, &mode)
                        && mode.dmDisplayFrequency > 1)
                    period_ = frequency_ / mode.dmDisplayFrequency;
            }
            if (!output && monitor) output = findOutput(monitor);
            if (output && output->WaitForVBlank() == S_OK) {
                const auto now = counter();
                std::lock_guard lock(clockMutex_);
                const auto spacing = now - sample_;
                if (!sample_ || std::abs(spacing - period_) > period_ / 4) {
                    if (anchor_) {
                        ++epoch_;
                        SetEvent(queued_.handle);
                    }
                    anchor_ = 0;
                    stableSamples_ = 0;
                } else if (anchor_) {
                    const auto position = now - anchor_;
                    const auto slot = (position + period_ / 2) / period_;
                    anchor_ += (position - slot * period_) / 8;
                } else if (++stableSamples_ >= 3) {
                    anchor_ = now;
                    ++epoch_;
                }
                sample_ = now;
            } else {
                output.Reset();
                WaitForSingleObject(stop_.handle, 16);
            }
        }
    }
    struct Clock {
        int64_t anchor, period;
        unsigned epoch;
        int64_t lead() const { return 3 * period / 4; }
        int64_t slot(int64_t time) const { return (std::max<int64_t>(0, time - anchor) + period - 1) / period; }
        int64_t submit(int64_t slot) const { return anchor + slot * period - lead(); }
    };
    Clock clock() {
        std::lock_guard lock(clockMutex_);
        return {sample_ && counter() - sample_ < frequency_ / 4 ? anchor_ : 0, period_, epoch_};
    }
    bool armTimer(int64_t time) {
        const auto delay = time - counter();
        if (delay <= 0 || stopped()) return false;
        LARGE_INTEGER due{};
        due.QuadPart = -std::max<int64_t>(1, delay * 10'000'000 / frequency_);
        if (!SetWaitableTimer(timer_.handle, &due, 0, nullptr, nullptr, FALSE)) {
            fail(E_FAIL);
            return false;
        }
        return true;
    }
    void waitUntil(int64_t time) {
        if (!armTimer(time)) return;
        const HANDLE events[]{stop_.handle, timer_.handle};
        WaitForMultipleObjects(2, events, FALSE, 120);
    }
    bool fenceReady(ID3D12Fence* fence, uint64_t value) {
        const auto done = fence->GetCompletedValue();
        if (done == UINT64_MAX) fail(DXGI_ERROR_DEVICE_REMOVED);
        return done != UINT64_MAX && done >= value;
    }
    bool waitFence(ID3D12Fence* fence, uint64_t value, int64_t deadline, HANDLE complete) {
        if (stopped()) return false;
        if (fenceReady(fence, value)) return true;
        if (!armTimer(deadline)) return false;
        ResetEvent(complete);
        checked(fence->SetEventOnCompletion(value, complete));
        const HANDLE events[]{stop_.handle, complete, timer_.handle};
        while (WaitForMultipleObjects(3, events, FALSE, INFINITE) == WAIT_OBJECT_0 + 1) {
            if (fenceReady(fence, value)) return true;
        }
        return !stopped() && fenceReady(fence, value);
    }
    static void barrier(ID3D12GraphicsCommandList* list, ID3D12Resource* image,
                        D3D12_RESOURCE_STATES before, D3D12_RESOURCE_STATES after) {
        D3D12_RESOURCE_BARRIER barrier{};
        barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
        barrier.Transition = {image, D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES, before, after};
        list->ResourceBarrier(1, &barrier);
    }
    uint64_t copy(unsigned imageIndex, unsigned buffer, int64_t deadline) {
        auto& copy = copies_[buffer];
        if (!waitFence(copied_.Get(), copy.after, deadline, copyDone_.handle)) return 0;
        checked(copy.allocator->Reset());
        checked(copy.list->Reset(copy.allocator.Get(), nullptr));
        auto* from = images_[imageIndex].texture.Get();
        auto* to = back_[buffer].Get();
        barrier(copy.list.Get(), from, D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_COPY_SOURCE);
        barrier(copy.list.Get(), to, D3D12_RESOURCE_STATE_PRESENT, D3D12_RESOURCE_STATE_COPY_DEST);
        copy.list->CopyResource(to, from);
        barrier(copy.list.Get(), from, D3D12_RESOURCE_STATE_COPY_SOURCE, D3D12_RESOURCE_STATE_COMMON);
        barrier(copy.list.Get(), to, D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_PRESENT);
        checked(copy.list->Close());
        ID3D12CommandList* lists[]{copy.list.Get()};
        flipQueue_->ExecuteCommandLists(1, lists);
        checked(flipQueue_->Signal(copied_.Get(), ++copyValue_));
        return copy.after = copyValue_;
    }

    bool acquireFlip(int64_t deadline) {
        if (flipPermit_) return true;
        const HANDLE events[]{stop_.handle, flipReady_.handle};
        const auto remaining = std::max<int64_t>(0, deadline - counter());
        const auto timeout = static_cast<DWORD>((remaining * 1000 + frequency_ - 1) / frequency_);
        flipPermit_ = WaitForMultipleObjects(2, events, FALSE, timeout) == WAIT_OBJECT_0 + 1;
        return flipPermit_;
    }

    DelayCancellation waitForRealTarget(const Clock& timing, int64_t target) {
        HANDLE watched = nullptr;
        for (;;) {
            if (stopped()) return DelayCancellation::ClockChanged;
            const auto current = clock();
            const auto stableClock = current.anchor && current.epoch == timing.epoch;
            {
                std::lock_guard lock(mutex_);
                const auto cancel = cancelRealDelay(stableClock, reservationPending_ || groupCount_ == MaxGroups, false);
                if (cancel != DelayCancellation::None) return cancel;
                if (groupCount_ > 1) {
                    const auto& next = groups_[(groupHead_ + 1) % MaxGroups];
                    if (next.captured) {
                        auto& image = images_[next.images[0]];
                        if (fenceReady(image.fence.Get(), image.value)) return cancelRealDelay(true, false, true);
                        const auto ready = writerDone_[next.images[0]].handle;
                        if (watched != ready) {
                            ResetEvent(ready);
                            checked(image.fence->SetEventOnCompletion(image.value, ready));
                            watched = ready;
                        }
                    }
                }
            }
            if (!armTimer(target)) return DelayCancellation::None;
            const HANDLE events[]{stop_.handle, timer_.handle, queued_.handle, watched};
            const auto result = WaitForMultipleObjects(watched ? 4 : 3, events, FALSE, INFINITE);
            if (result == WAIT_OBJECT_0) return DelayCancellation::ClockChanged;
            if (result == WAIT_FAILED) {
                fail(E_FAIL);
                return DelayCancellation::ClockChanged;
            }
        }
    }

    struct Phase {
        int64_t previousReal = UnknownSlot, last = UnknownSlot;
        std::array<int64_t, MaxFrames> slots{};
        unsigned epoch = 0;
        bool valid = false;

        void reset(const Clock& clock) {
            previousReal = last = UnknownSlot;
            slots.fill(UnknownSlot);
            epoch = clock.epoch;
            valid = clock.anchor != 0;
        }
    };

    void output(const OutputGroup& group, Phase& phase) {
        const auto ordinal = group.submitted;
        const auto imageIndex = group.images[ordinal];
        auto& image = images_[imageIndex];
        const auto deadline = progressDeadline();
        if (!waitFence(image.fence.Get(), image.value, deadline, writerDone_[imageIndex].handle)
                || !acquireFlip(deadline)) {
            if (!stopped()) progressTimedOut();
            return;
        }

        // The writer fence can retire before the SDK reports the final count.
        // Consult the latest information without waiting for that report.
        OutputGroup current;
        {
            std::lock_guard lock(mutex_);
            current = groups_[groupHead_];
        }
        const bool real = current.real(ordinal);
        const bool generated = current.generated(ordinal);
        const auto interval = image.interval;
        const auto flags = image.flags;
        const auto buffer = real_->GetCurrentBackBufferIndex();
        const auto after = copy(imageIndex, buffer, deadline);
        if (!after) {
            if (!stopped()) progressTimedOut();
            return;
        }
        {
            std::lock_guard lock(mutex_);
            image.copiedAt = after;
            if (reservationPending_) SetEvent(reservationReady_.handle);
        }

        const auto timing = clock();
        if (phase.epoch != timing.epoch || phase.valid != (timing.anchor != 0))
            phase.reset(timing);
        const auto now = counter();
        const auto earliest = timing.anchor && interval
            ? std::max(phase.last + 1, timing.slot(now + timing.lead())) : UnknownSlot;
        auto target = earliest;
        auto cancellation = DelayCancellation::None;
        if (earliest != UnknownSlot && real && current.expected > 1 && !current.reset) {
            target = evenlySpacedRealSlot(phase.previousReal, phase.slots, ordinal, earliest);
            if (target != earliest) {
                const auto due = realSubmissionTime(now, timing.period, timing.submit(earliest), timing.submit(target));
                if (due > now)
                    cancellation = waitForRealTarget(timing, due);
                else
                    target = earliest;
            }
        }

        HRESULT status = DXGI_STATUS_OCCLUDED;
        int64_t submitted = 0;
        bool retried = false;
        while (!stopped()) {
            frameEvents_.mark(current.frameId, generated, real);
            submitted = counter();
            status = real_->Present(interval, flags | DXGI_PRESENT_DO_NOT_WAIT);
            if (status != DXGI_ERROR_WAS_STILL_DRAWING || submitted >= deadline) break;
            retried = true;
            const auto retry = submitted + frequency_ / 1000;
            waitUntil(std::min(deadline, retry));
        }
        if (observe_ && current.profiled)
            observe_({current.sequence, current.frameId, ordinal + 1, current.expected, real,
                      earliest, target, submitted, cancellation, status});
        if (status != S_OK && status != DXGI_STATUS_OCCLUDED) {
            if (!stopped()) fail(status);
            return;
        }
        if (stopped()) return;
        if (status == S_OK) {
            flipPermit_ = false;
        } else {
            occluded_.store(current.sequence, std::memory_order_release);
        }
        auto slot = earliest;
        if (earliest != UnknownSlot) {
            slot = std::max(earliest, timing.slot(submitted + timing.lead()));
            if (target != earliest && cancellation == DelayCancellation::None) slot = std::max(slot, target);
        }
        if (status != S_OK || retried || cancellation == DelayCancellation::ClockChanged)
            phase.reset(clock());
        else
            phase.slots[ordinal] = phase.last = slot;
        {
            std::lock_guard lock(mutex_);
            ++groups_[groupHead_].submitted;
        }
    }

    void run() {
        Phase phase;
        phase.reset(clock());
        while (!stopped()) {
            OutputGroup group;
            {
                std::lock_guard lock(mutex_);
                if (groupCount_) {
                    const auto& front = groups_[groupHead_];
                    group = front;
                    if (group.completed()) {
                        groupHead_ = (groupHead_ + 1) % MaxGroups;
                        --groupCount_;
                        if (reservationPending_) SetEvent(reservationReady_.handle);
                    }
                }
            }
            if (group.completed()) {
                phase.previousReal = group.expected ? phase.slots[group.expected - 1] : UnknownSlot;
                phase.slots.fill(UnknownSlot);
            } else if (group.submitted < group.captured) {
                output(group, phase);
            } else {
                const HANDLE events[]{stop_.handle, queued_.handle};
                WaitForMultipleObjects(2, events, FALSE, INFINITE);
            }
        }
    }

    void drainCopy() {
        if (copyValue_ && SUCCEEDED(gpu_->GetDeviceRemovedReason())) {
            Event complete{CreateEventW(nullptr, FALSE, FALSE, nullptr)};
            if (complete.handle && SUCCEEDED(copied_->SetEventOnCompletion(copyValue_, complete.handle)))
                WaitForSingleObject(complete.handle, INFINITE);
        }
    }

    const unsigned multiplier_, size_;
    const int64_t frequency_;
    int64_t period_;
    const Observe observe_;
    HWND window_ = nullptr;
    ComPtr<IDXGIFactory1> monitorFactory_;
    ComPtr<IDXGISwapChain4> real_;
    ComPtr<ID3D12Device> gpu_;
    ComPtr<ID3D12CommandQueue> flipQueue_, initialWriter_, resizeWriter_;
    std::array<ComPtr<ID3D12CommandQueue>, Capacity> mappedWriters_{};
    std::array<ComPtr<ID3D12Resource>, RealBuffers> back_{};
    std::array<Image, Capacity> images_{};
    ComPtr<ID3D12Fence> copied_;
    std::array<CopyCommands, RealBuffers> copies_{};
    uint64_t copyValue_ = 0;
    bool flipPermit_ = false;
    PresentFrameEvents frameEvents_;
    Event virtualReady_, stop_, queued_, timer_, copyDone_, flipReady_, reservationReady_;
    std::array<Event, Capacity> writerDone_{};
    std::thread worker_, clock_;
    std::atomic<bool> running_{false}, stopping_{false};
    std::atomic<HRESULT> error_{S_OK};
    std::atomic<unsigned> index_{0}, captured_{0};
    std::atomic<uint64_t> occluded_{0};
    std::mutex mutex_, clockMutex_;
    bool reservationPending_ = false;
    uint64_t armedReservationCopy_ = 0;
    std::array<OutputGroup, MaxGroups> groups_{};
    unsigned groupHead_ = 0, groupCount_ = 0, epoch_ = 0, stableSamples_ = 0;
    int64_t anchor_ = 0, sample_ = 0;
};
}
