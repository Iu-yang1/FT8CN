#include "experimental_modem.h"

#include <math.h>
#include <string.h>

static void reset_result(exp_symbol_result_t *out_result) {
    if (out_result == 0) {
        return;
    }
    memset(out_result, 0, sizeof(exp_symbol_result_t));
    out_result->best_index = -1;
}

static void sort4(float *v) {
    for (int i = 0; i < 4; ++i) {
        for (int j = i + 1; j < 4; ++j) {
            if (v[j] < v[i]) {
                float t = v[i];
                v[i] = v[j];
                v[j] = t;
            }
        }
    }
}

int exp_analyze_first_symbol(
        const float *samples,
        int sample_count,
        int sample_rate,
        int symbol_samples,
        const int *tone_hz,
        int tone_count,
        exp_symbol_result_t *out_result
) {
    reset_result(out_result);
    if (samples == 0 || tone_hz == 0 || out_result == 0) {
        return 0;
    }
    if (tone_count < 4 || sample_rate <= 0 || symbol_samples <= 0) {
        return 0;
    }
    if (sample_count < symbol_samples) {
        return 0;
    }

    // Non-coherent tone-energy estimate over one symbol window.
    for (int k = 0; k < 4; ++k) {
        double acc_i = 0.0;
        double acc_q = 0.0;
        double omega = 2.0 * M_PI * ((double) tone_hz[k] / (double) sample_rate);
        for (int n = 0; n < symbol_samples; ++n) {
            double phase = omega * (double) n;
            double x = (double) samples[n];
            acc_i += x * cos(phase);
            acc_q -= x * sin(phase);
        }
        out_result->energies[k] = (float) (acc_i * acc_i + acc_q * acc_q);
    }

    float best_energy = out_result->energies[0];
    out_result->best_index = 0;
    for (int k = 1; k < 4; ++k) {
        if (out_result->energies[k] > best_energy) {
            best_energy = out_result->energies[k];
            out_result->best_index = k;
        }
    }

    // Use the lower three bins as a simple noise-floor proxy.
    float sorted[4];
    memcpy(sorted, out_result->energies, sizeof(sorted));
    sort4(sorted);
    out_result->noise_floor = (sorted[0] + sorted[1] + sorted[2]) / 3.0f;

    return 1;
}
