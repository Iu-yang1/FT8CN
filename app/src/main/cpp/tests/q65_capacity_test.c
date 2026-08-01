#include "../common/q65_wave_size.h"
#include "../wsjtx3/wsjtx3_bridge.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>

static int expected_base_nsps(int period) {
    switch (period) {
        case 15: return 1800;
        case 30: return 3600;
        case 60: return 7200;
        case 120: return 16000;
        case 300: return 41472;
        default: return 0;
    }
}

static int run_capacity_matrix(void) {
    const int periods[] = {15, 30, 60, 120, 300};
    const int sample_rates[] = {12000, 24000, 48000};
    int ok = 1;
    for (int submode = 0; submode <= 5; ++submode) {
        size_t previous_for_period = 0;
        for (size_t period_index = 0;
             period_index < sizeof(periods) / sizeof(periods[0]);
             ++period_index) {
            const int period = periods[period_index];
            previous_for_period = 0;
            for (size_t rate_index = 0;
                 rate_index < sizeof(sample_rates) / sizeof(sample_rates[0]);
                 ++rate_index) {
                const int sample_rate = sample_rates[rate_index];
                size_t required = 0;
                const size_t expected = (size_t) expected_base_nsps(period)
                                        * 85U * (size_t) (sample_rate / 12000);
                ok = ok && ftx_q65_required_samples(period, sample_rate, &required)
                     && required == expected;
                if (rate_index > 0) {
                    ok = ok && required == previous_for_period * 2U;
                }
                previous_for_period = required;
                const double duration = (double) required / (double) sample_rate;
                const double expected_duration = (double) expected_base_nsps(period)
                                                 * 85.0 / 12000.0;
                ok = ok && fabs(duration - expected_duration) < 1.0e-9;
            }
        }
    }
    return ok;
}

static int run_generation_spot_check(void) {
    size_t required = 0;
    if (!ftx_q65_required_samples(60, 12000, &required)) {
        return 0;
    }
    float *wave = (float *) malloc(required * sizeof(float));
    if (wave == NULL) {
        return 0;
    }
    int ok = 1;
    for (int submode = 0; submode <= 5; ++submode) {
        const int generated = wsjtx3_bridge_generate_q65_wave(
                "CQ BG5JSU OL87", submode, 60, 12000, 1000.0f,
                wave, (int) required);
        ok = ok && generated == (int) required;
    }
    ok = ok && wsjtx3_bridge_generate_q65_wave(
            "CQ BG5JSU OL87", 0, 60, 12000, 1000.0f,
            wave, (int) required - 1) == 0;
    free(wave);
    return ok;
}

int ftx_run_q65_capacity_selftests(void) {
    const int matrix_ok = run_capacity_matrix();
    const int generation_ok = run_generation_spot_check();
    size_t unused = 0;
    const int invalid_ok = !ftx_q65_required_samples(10, 12000, &unused)
                           && !ftx_q65_required_samples(60, 44100, &unused);
    printf("[%s] Q65 A-F period/rate capacity matrix\n", matrix_ok ? "PASS" : "FAIL");
    printf("[%s] Q65 A-F generation capacity spot check\n",
           generation_ok ? "PASS" : "FAIL");
    printf("[%s] Q65 invalid capacity rejection\n", invalid_ok ? "PASS" : "FAIL");
    return matrix_ok && generation_ok && invalid_ok ? 0 : -1;
}
