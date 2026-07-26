#include "../wsjtx3/host/wsjtx3_parallel.h"

#include <stdio.h>

static int expect_threads(const char* name,
                          const long long* capacities,
                          int capacity_count,
                          int online_processors,
                          int expected) {
    const int actual = wsjtx3_select_ft8_sync_threads(
            capacities, capacity_count, online_processors);
    if (actual != expected) {
        fprintf(stderr, "parallel policy %s: expected=%d actual=%d\n",
                name, expected, actual);
        return 1;
    }
    return 0;
}

int ftx_run_wsjtx3_parallel_policy_selftests(void) {
    static const long long phone_topology[] = {
            450, 450, 450, 450, 871, 871, 871, 1024
    };
    static const long long one_big_core[] = {450, 450, 450, 1024};
    static const long long homogeneous_dual_core[] = {1000, 1000};
    static const long long unknown_topology[] = {0, 0, 0, 0, 0, 0, 0, 0};
    int failures = 0;

    failures += expect_threads("phone", phone_topology, 8, 8, 2);
    failures += expect_threads("one-big", one_big_core, 4, 4, 1);
    failures += expect_threads("dual-core-reserve", homogeneous_dual_core, 2, 2, 1);
    failures += expect_threads("unknown-eight", unknown_topology, 8, 8, 2);
    failures += expect_threads("single-core", NULL, 0, 1, 1);
    if (failures == 0) {
        puts("WSJT-X parallel policy self-tests: PASS");
    }
    return failures == 0 ? 0 : -1;
}
