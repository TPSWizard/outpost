// Host test for core_plan.h: synthetic phone layouts, checked against the cores we want the model on.
//   g++ -std=c++17 -Wall -Wextra -o core_plan_test core_plan_test.cpp && ./core_plan_test
#include "core_plan.h"

#include <cstdio>
#include <cstdlib>
#include <string>

using namespace outpost;

static int failures = 0;

static uint32_t arm(uint32_t part) { return (0x41u << 24) | (part << 4); }
static uint32_t kryo(uint32_t part) { return (0x51u << 24) | (part << 4); }

// n cores with the given capacity, top clock (MHz) and MIDR, numbered from `first`.
static void cluster(std::vector<CoreInfo> &v, int n, long capacity, long mhz, uint32_t midr) {
    for (int i = 0; i < n; i++) v.push_back(CoreInfo{(int) v.size(), capacity, mhz * 1000, midr});
}

static void expect(const char *name, const std::vector<CoreInfo> &cores, CoreSource source, std::vector<int> fast) {
    CoreChoice c = choose_fast_cores(cores);
    std::vector<int> got = c.fast;
    std::sort(got.begin(), got.end());
    std::sort(fast.begin(), fast.end());
    bool ok = got == fast && c.source == source;
    auto show = [](const std::vector<int> &v) { std::string s; for (int x : v) s += std::to_string(x) + ","; return s.empty() ? "-" : s; };
    printf("%s %-42s by %-12s cores %s\n", ok ? "ok  " : "FAIL", name, core_source_name(c.source), show(got).c_str());
    if (!ok) { printf("     expected by %s cores %s\n", core_source_name(source), show(fast).c_str()); failures++; }
}

int main() {
    const uint32_t A55 = arm(0xD05), A76 = arm(0xD0B), A78 = arm(0xD41), X1 = arm(0xD44), X3 = arm(0xD4E),
                   A715 = arm(0xD4D), A510 = arm(0xD46), A53 = arm(0xD03), A73 = arm(0xD09);

    // Helio G99: 2x A76 @ 2.2 + 6x A55 @ 2.0. The clock rule alone keeps everything (91%).
    { std::vector<CoreInfo> v; cluster(v, 6, 420, 2000, A55); cluster(v, 2, 1024, 2200, A76);
      expect("Helio G99 (capacity)", v, CoreSource::Capacity, {6, 7}); }
    { std::vector<CoreInfo> v; cluster(v, 6, 0, 2000, A55); cluster(v, 2, 0, 2200, A76);
      expect("Helio G99 (no capacity, MIDR)", v, CoreSource::Part, {6, 7}); }
    { std::vector<CoreInfo> v; cluster(v, 6, 0, 2000, 0); cluster(v, 2, 0, 2200, 0);
      expect("Helio G99 (clock only: known miss)", v, CoreSource::None, {}); }

    // Snapdragon 695: 2x A78 @ 2.2 + 6x A55 @ 1.8 (82% of top clock).
    { std::vector<CoreInfo> v; cluster(v, 6, 0, 1800, kryo(0x805)); cluster(v, 2, 0, 2200, A78);
      expect("Snapdragon 695 (Kryo Silver MIDR)", v, CoreSource::Part, {6, 7}); }

    // Snapdragon 8 Gen 2: 1x X3 @ 3.2 + 4x A715/A710 @ 2.8 + 3x A510 @ 2.0.
    { std::vector<CoreInfo> v; cluster(v, 3, 210, 2000, A510); cluster(v, 4, 650, 2800, A715); cluster(v, 1, 1024, 3200, X3);
      expect("Snapdragon 8 Gen 2 (capacity)", v, CoreSource::Capacity, {3, 4, 5, 6, 7}); }
    { std::vector<CoreInfo> v; cluster(v, 3, 0, 2000, A510); cluster(v, 4, 0, 2800, A715); cluster(v, 1, 0, 3200, X3);
      expect("Snapdragon 8 Gen 2 (MIDR)", v, CoreSource::Part, {3, 4, 5, 6, 7}); }

    // Pixel 6 (Tensor): 2x X1 @ 2.8 + 2x A76 @ 2.25 + 4x A55 @ 1.8.
    { std::vector<CoreInfo> v; cluster(v, 4, 220, 1800, A55); cluster(v, 2, 660, 2250, A76); cluster(v, 2, 1024, 2800, X1);
      expect("Pixel 6 (capacity)", v, CoreSource::Capacity, {4, 5, 6, 7}); }

    // Snapdragon 845: 4x Kryo 385 Gold @ 2.8 + 4x Kryo 385 Silver @ 1.8, no capacity, no MIDR (old kernel).
    { std::vector<CoreInfo> v; cluster(v, 4, 0, 1766, 0); cluster(v, 4, 0, 2803, 0);
      expect("Snapdragon 845 (clock only)", v, CoreSource::Frequency, {4, 5, 6, 7}); }

    // Kernel that reports capacity 1024 for every core: fall through to the other signals.
    { std::vector<CoreInfo> v; cluster(v, 6, 1024, 2000, A55); cluster(v, 2, 1024, 2200, A76);
      expect("Uniform capacity, MIDR present", v, CoreSource::Part, {6, 7}); }

    // Snapdragon 450: 8x A53, two clock clusters, same part. Nothing to drop by type; clock rule applies.
    { std::vector<CoreInfo> v; cluster(v, 4, 0, 1400, A53); cluster(v, 4, 0, 1800, A53);
      expect("8x A53 at two clocks", v, CoreSource::Frequency, {4, 5, 6, 7}); }

    // Everything the same: no distinction.
    { std::vector<CoreInfo> v; cluster(v, 8, 1024, 2400, A73);
      expect("Uniform 8-core", v, CoreSource::None, {}); }
    { std::vector<CoreInfo> v; cluster(v, 4, 0, 0, 0);
      expect("Emulator, no sysfs", v, CoreSource::None, {}); }
    { std::vector<CoreInfo> v; cluster(v, 1, 1024, 3000, X1);
      expect("Single core", v, CoreSource::None, {}); }

    // Fastest cores come first in the list, so a lone prime pairs with the next-best core.
    { std::vector<CoreInfo> v; cluster(v, 4, 300, 1800, A55); cluster(v, 3, 700, 2400, A78); cluster(v, 1, 1024, 3000, X1);
      CoreChoice c = choose_fast_cores(v);
      bool ok = !c.fast.empty() && c.fast.front() == 7;
      printf("%s %-42s first=%d\n", ok ? "ok  " : "FAIL", "Prime core listed first", c.fast.empty() ? -1 : c.fast.front());
      if (!ok) failures++; }

    printf("%d failure(s)\n", failures);
    return failures ? EXIT_FAILURE : EXIT_SUCCESS;
}
