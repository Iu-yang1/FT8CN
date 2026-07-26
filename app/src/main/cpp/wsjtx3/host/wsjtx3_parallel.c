#include "wsjtx3_parallel.h"

#include <limits.h>
#include <stdio.h>
#include <stdatomic.h>

#if defined(__ANDROID__) || defined(__linux__)
#include <unistd.h>
#elif defined(_WIN32)
#include <windows.h>
#endif

#define WSJTX3_MAX_TRACKED_CPUS 64
#define WSJTX3_MAX_FT8_SYNC_THREADS 2

#if defined(__ANDROID__) || defined(__linux__)
static int read_positive_value(const char* path, long long* value) {
    FILE* stream;
    long long parsed;

    if (path == NULL || value == NULL) {
        return 0;
    }
    stream = fopen(path, "r");
    if (stream == NULL) {
        return 0;
    }
    if (fscanf(stream, "%lld", &parsed) != 1 || parsed <= 0) {
        fclose(stream);
        return 0;
    }
    fclose(stream);
    *value = parsed;
    return 1;
}
#endif

static int get_online_processor_count(void) {
#if defined(__ANDROID__) || defined(__linux__)
    const long count = sysconf(_SC_NPROCESSORS_ONLN);
    return count > 0 && count <= INT_MAX ? (int) count : 1;
#elif defined(_WIN32)
    SYSTEM_INFO info;
    GetSystemInfo(&info);
    return info.dwNumberOfProcessors > 0 ? (int) info.dwNumberOfProcessors : 1;
#else
    return 1;
#endif
}

static int read_cpu_capacities(long long* capacities, int capacity_count) {
#if defined(__ANDROID__) || defined(__linux__)
    int valid = 0;

    for (int cpu = 0; cpu < capacity_count; ++cpu) {
        char path[128];
        long long value = 0;
        const int written = snprintf(path, sizeof(path),
                                     "/sys/devices/system/cpu/cpu%d/cpu_capacity", cpu);
        if (written > 0 && (size_t) written < sizeof(path) &&
                read_positive_value(path, &value)) {
            capacities[cpu] = value;
            ++valid;
        }
    }
    if (valid > 0) {
        return valid;
    }

    for (int cpu = 0; cpu < capacity_count; ++cpu) {
        char path[160];
        long long value = 0;
        const int written = snprintf(
                path, sizeof(path),
                "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
        if (written > 0 && (size_t) written < sizeof(path) &&
                read_positive_value(path, &value)) {
            capacities[cpu] = value;
            ++valid;
        }
    }
    return valid;
#else
    (void) capacities;
    (void) capacity_count;
    return 0;
#endif
}

int wsjtx3_select_ft8_sync_threads(const long long* capacities,
                                   int capacity_count,
                                   int online_processors) {
    long long maximum = 0;
    int performance_cores = 0;
    int usable_processors;

    if (online_processors < 1) {
        online_processors = 1;
    }
    usable_processors = online_processors - 1;
    if (usable_processors < 1) {
        return 1;
    }

    if (capacities != NULL && capacity_count > 0) {
        for (int i = 0; i < capacity_count; ++i) {
            if (capacities[i] > maximum) {
                maximum = capacities[i];
            }
        }
        if (maximum > 0) {
            for (int i = 0; i < capacity_count; ++i) {
                /* 80% 可将超大核和中大核归入同一个性能簇。 */
                if (capacities[i] > 0 &&
                        capacities[i] >= maximum - maximum / 5) {
                    ++performance_cores;
                }
            }
            if (performance_cores < usable_processors) {
                usable_processors = performance_cores;
            }
        }
    }

    if (usable_processors > WSJTX3_MAX_FT8_SYNC_THREADS) {
        usable_processors = WSJTX3_MAX_FT8_SYNC_THREADS;
    }
    return usable_processors > 0 ? usable_processors : 1;
}

int wsjtx3_ft8_sync_thread_count(void) {
    static atomic_int cached_threads = ATOMIC_VAR_INIT(0);
    long long capacities[WSJTX3_MAX_TRACKED_CPUS] = {0};
    int online_processors;
    int capacity_count;
    int selected_threads;
    int expected = 0;

    selected_threads = atomic_load_explicit(&cached_threads, memory_order_acquire);
    if (selected_threads > 0) {
        return selected_threads;
    }
    online_processors = get_online_processor_count();
    capacity_count = online_processors;
    if (capacity_count > WSJTX3_MAX_TRACKED_CPUS) {
        capacity_count = WSJTX3_MAX_TRACKED_CPUS;
    }
    (void) read_cpu_capacities(capacities, capacity_count);
    selected_threads = wsjtx3_select_ft8_sync_threads(
            capacities, capacity_count, online_processors);
    (void) atomic_compare_exchange_strong_explicit(
            &cached_threads, &expected, selected_threads,
            memory_order_release, memory_order_relaxed);
    return atomic_load_explicit(&cached_threads, memory_order_acquire);
}
