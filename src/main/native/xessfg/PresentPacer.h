#pragma once

#include <array>
#include <algorithm>
#include <cstdint>

namespace cmi::xessfg::pacing {
constexpr unsigned MaxFrames = 4;
constexpr unsigned MaxGroups = 3;
constexpr int64_t UnknownSlot = -1;

inline int64_t evenlySpacedRealSlot(int64_t previous, const std::array<int64_t, MaxFrames>& slots,
                                    unsigned generated, int64_t earliest) {
    if (!generated || generated >= MaxFrames || previous == UnknownSlot) return earliest;
    int64_t previousImage = previous;
    // Compare the squared errors for R and R+1 in units of 1/(generated+1) refresh.
    // Taking their difference avoids squaring a potentially long display gap.
    int64_t difference = 0;
    for (unsigned i = 0; i < generated; ++i) {
        if (slots[i] <= previousImage || slots[i] >= earliest) return earliest;
        previousImage = slots[i];
        const auto ordinal = static_cast<int64_t>(i + 1);
        const auto error = (generated + 1) * (slots[i] - previous) - ordinal * (earliest - previous);
        difference += ordinal * (ordinal - 2 * error);
    }
    return earliest + (difference < 0 ? 1 : 0);
}

inline int64_t realSubmissionTime(int64_t now, int64_t period, int64_t earliest, int64_t target) {
    const auto due = std::min(now + period, target);
    return due > now && due > earliest ? due : now;
}

enum class DelayCancellation : unsigned {
    None, NextImage, OutputCapacity, ClockChanged
};

inline DelayCancellation cancelRealDelay(bool stableClock, bool outputFull, bool nextReady) {
    if (!stableClock) return DelayCancellation::ClockChanged;
    if (outputFull) return DelayCancellation::OutputCapacity;
    return nextReady ? DelayCancellation::NextImage : DelayCancellation::None;
}

struct OutputGroup {
    uint64_t sequence = 0;
    uint32_t frameId = 0;
    unsigned captured = 0, submitted = 0, expected = 0;
    bool confirmed = false, profiled = false, interpolate = false, reset = false;
    std::array<unsigned, MaxFrames> images{};

    bool acceptsImage() const { return !confirmed || captured < expected; }
    bool completed() const { return confirmed && submitted == expected; }
    bool confirm(unsigned actual, unsigned multiplier) {
        if (confirmed || actual > multiplier || actual < captured) return false;
        confirmed = true;
        expected = actual;
        return true;
    }
    // The next captured image proves that this image is not the last.
    bool generated(unsigned ordinal) const { return ordinal + 1 < (confirmed ? expected : captured); }
    bool real(unsigned ordinal) const {
        return confirmed ? ordinal + 1 == expected : !interpolate && ordinal == 0;
    }
};
}
