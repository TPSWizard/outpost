// Which CPU cores should run the model.
//
// Phones mix fast and slow cores, and ggml synchronises its threads at every op, so one thread
// on a little core holds the whole model to that core's pace. This picks the fast cores from
// what the kernel exposes, best signal first:
//
//  1. cpu_capacity: the scheduler's relative throughput per core (max 1024), set from the device
//     tree's capacity-dmips-mhz times the top clock. It already folds in the microarchitecture, so
//     an A55 at 2.0 GHz next to an A76 at 2.2 GHz reads ~400 vs 1024 and is correctly left out.
//  2. MIDR part numbers plus cpufreq: on kernels without capacity, cores whose part is a known
//     efficiency design (A53/A55/A510/A520, Kryo Silver) are left out, then the clock rule below.
//  3. cpufreq alone: cores within 80% of the top clock count as fast. This is the weakest rule:
//     mid-range chips often clock their little cores within 80% of the big ones (Helio G99,
//     Snapdragon 695), which is why the two rules above come first.
//
// Header-only and free of JNI/llama.cpp so the host test (core_plan_test.cpp) can compile it alone.
#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace outpost {

struct CoreInfo {
    int cpu = 0;
    long capacity = 0;  // /sys/devices/system/cpu/cpuN/cpu_capacity, 0 if unknown
    long max_khz = 0;   // /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq, 0 if unknown
    uint32_t midr = 0;  // MIDR_EL1: implementer in bits 31:24, part number in bits 15:4; 0 if unknown
};

enum class CoreSource { None, Capacity, Part, Frequency };

struct CoreChoice {
    std::vector<int> fast;  // CPUs to run on, fastest first; empty means no useful distinction
    CoreSource source = CoreSource::None;
};

inline const char *core_source_name(CoreSource s) {
    switch (s) {
        case CoreSource::Capacity: return "cpu_capacity";
        case CoreSource::Part: return "core type";
        case CoreSource::Frequency: return "clock";
        default: return "none";
    }
}

/** Known efficiency ("little") cores by MIDR implementer and part number. */
inline bool is_little_part(uint32_t midr) {
    const uint32_t impl = (midr >> 24) & 0xFF;
    const uint32_t part = (midr >> 4) & 0xFFF;
    switch (impl) {
        case 0x41:  // Arm
            return part == 0xD02 /* A34 */ || part == 0xD03 /* A53 */ || part == 0xD04 /* A35 */ ||
                   part == 0xD05 /* A55 */ || part == 0xD46 /* A510 */ || part == 0xD80 /* A520 */;
        case 0x51:  // Qualcomm Kryo "Silver" clusters (A53/A55 derivatives)
            return part == 0x801 || part == 0x803 || part == 0x805;
        default:
            return false;
    }
}

namespace detail {

template <typename Key>
inline std::vector<int> sorted_cpus(const std::vector<CoreInfo> &cores, Key key) {
    std::vector<const CoreInfo *> p;
    for (const auto &c : cores) p.push_back(&c);
    std::stable_sort(p.begin(), p.end(), [&](const CoreInfo *a, const CoreInfo *b) { return key(*a) > key(*b); });
    std::vector<int> out;
    for (const auto *c : p) out.push_back(c->cpu);
    return out;
}

// Keeps a selection only if it separates the cores: at least one in, at least one out.
inline bool is_split(const std::vector<int> &fast, size_t total) { return !fast.empty() && fast.size() < total; }

} // namespace detail

/** Picks the fast cores. Returns an empty list when the cores can't be told apart. */
inline CoreChoice choose_fast_cores(const std::vector<CoreInfo> &cores) {
    CoreChoice out;
    const size_t n = cores.size();
    if (n < 2) return out;

    const bool have_capacity = std::all_of(cores.begin(), cores.end(), [](const CoreInfo &c) { return c.capacity > 0; });
    const bool have_freq = std::all_of(cores.begin(), cores.end(), [](const CoreInfo &c) { return c.max_khz > 0; });
    const bool have_midr = std::all_of(cores.begin(), cores.end(), [](const CoreInfo &c) { return c.midr != 0; });

    // 1. Scheduler capacity: keep cores with at least half the top capacity. Prime and performance
    //    clusters land at 60-100%, efficiency clusters at 20-45%.
    if (have_capacity) {
        const long top = std::max_element(cores.begin(), cores.end(), [](const CoreInfo &a, const CoreInfo &b) {
            return a.capacity < b.capacity;
        })->capacity;
        std::vector<int> fast;
        for (int cpu : detail::sorted_cpus(cores, [](const CoreInfo &c) { return c.capacity; })) {
            const auto &c = *std::find_if(cores.begin(), cores.end(), [&](const CoreInfo &x) { return x.cpu == cpu; });
            if (c.capacity * 2 >= top) fast.push_back(cpu);
        }
        if (detail::is_split(fast, n)) {
            out.fast = fast;
            out.source = CoreSource::Capacity;
            return out;
        }
        // Uniform capacity (or a kernel that reports 1024 for every core): try the other signals.
    }

    if (!have_freq) return out;
    const long top_khz = std::max_element(cores.begin(), cores.end(), [](const CoreInfo &a, const CoreInfo &b) {
        return a.max_khz < b.max_khz;
    })->max_khz;
    const std::vector<int> by_freq = detail::sorted_cpus(cores, [](const CoreInfo &c) { return c.max_khz; });
    auto info = [&](int cpu) -> const CoreInfo & {
        return *std::find_if(cores.begin(), cores.end(), [&](const CoreInfo &x) { return x.cpu == cpu; });
    };

    // 2. Core type: drop known efficiency designs, then apply the clock rule to what's left.
    if (have_midr) {
        std::vector<int> fast;
        for (int cpu : by_freq) {
            const auto &c = info(cpu);
            if (!is_little_part(c.midr) && c.max_khz * 10 >= top_khz * 8) fast.push_back(cpu);
        }
        if (detail::is_split(fast, n)) {
            out.fast = fast;
            out.source = CoreSource::Part;
            return out;
        }
    }

    // 3. Clock alone.
    std::vector<int> fast;
    for (int cpu : by_freq) if (info(cpu).max_khz * 10 >= top_khz * 8) fast.push_back(cpu);
    if (detail::is_split(fast, n)) {
        out.fast = fast;
        out.source = CoreSource::Frequency;
    }
    return out;
}

} // namespace outpost
