#include "q65_wave_size.h"

#include <math.h>
#include <stdint.h>

enum {
    kQ65SymbolCount = 85,
    kQ65ReferenceRate = 12000
};

static int q65_base_nsps_12k(int tr_period_seconds) {
    switch (tr_period_seconds) {
        case 15:
            return 1800;
        case 30:
            return 3600;
        case 60:
            return 7200;
        case 120:
            return 16000;
        case 300:
            return 41472;
        default:
            return 0;
    }
}

int ftx_q65_required_samples(int tr_period_seconds,
                             int sample_rate,
                             size_t *required_samples) {
    const int base_nsps = q65_base_nsps_12k(tr_period_seconds);
    if (required_samples == NULL || base_nsps <= 0
        || (sample_rate != 12000 && sample_rate != 24000 && sample_rate != 48000)) {
        return 0;
    }
    const size_t rate_factor = (size_t) sample_rate / kQ65ReferenceRate;
    const size_t scaled_nsps = (size_t) base_nsps * rate_factor;
    if (scaled_nsps > SIZE_MAX / kQ65SymbolCount) {
        return 0;
    }
    *required_samples = scaled_nsps * kQ65SymbolCount;
    return *required_samples > 0;
}

int64_t ftx_q65_required_samples_c(int tr_period_seconds, int sample_rate) {
    size_t required_samples = 0;
    if (!ftx_q65_required_samples(tr_period_seconds, sample_rate, &required_samples)
        || required_samples > INT64_MAX) {
        return -1;
    }
    return (int64_t) required_samples;
}

int ftx_is_finite_double_c(double value) {
    return isfinite(value) ? 1 : 0;
}
