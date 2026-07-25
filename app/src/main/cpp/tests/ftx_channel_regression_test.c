#include "../ft8Encoder.h"
#include "../ft8/constants.h"
#include "../ftx_core/include/ftx_encoder.h"
#include "../wsjtx3/wsjtx3_bridge.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum {
    kSampleRate = 12000,
    kFt8Samples = 180000,
    kFt4Samples = 72576,
    kReferenceOffset = 6000
};

typedef struct {
    int found;
    float frequency;
    float dt;
} expected_result_t;

static uint32_t next_random(uint32_t *state) {
    *state = *state * UINT32_C(1664525) + UINT32_C(1013904223);
    return *state;
}

static void add_awgn(float *samples, int sample_count, float standard_deviation, uint32_t seed) {
    int index;
    uint32_t state = seed;
    for (index = 0; index < sample_count; index += 2) {
        const double uniform1 = ((double) next_random(&state) + 1.0) / 4294967297.0;
        const double uniform2 = ((double) next_random(&state) + 1.0) / 4294967297.0;
        const double radius = sqrt(-2.0 * log(uniform1));
        const double angle = 6.2831853071795864769 * uniform2;
        samples[index] += standard_deviation * (float) (radius * cos(angle));
        if (index + 1 < sample_count) {
            samples[index + 1] += standard_deviation * (float) (radius * sin(angle));
        }
    }
}

static int add_signal(ftx_mode_t mode,
                      const char *message,
                      float frequency,
                      int offset_samples,
                      float amplitude,
                      float *slot,
                      int slot_samples) {
    const int tone_count = ftx_get_tone_count(mode);
    const float symbol_period = mode == FTX_MODE_FT4 ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
    const float symbol_bt = mode == FTX_MODE_FT4 ? 1.0f : FT8_SYMBOL_BT;
    const int wave_samples = (int) (0.5f + tone_count * symbol_period * kSampleRate);
    uint8_t payload[FTX_PAYLOAD_BYTES] = {0};
    uint8_t *tones = NULL;
    float *wave = NULL;
    int ok = 0;
    int index;

    if (message == NULL || slot == NULL || offset_samples < 0
            || wave_samples <= 0 || offset_samples > slot_samples - wave_samples
            || ftx_pack_message(message, payload) < 0) {
        return 0;
    }
    tones = (uint8_t *) calloc((size_t) tone_count, sizeof(uint8_t));
    wave = (float *) calloc((size_t) wave_samples, sizeof(float));
    if (tones == NULL || wave == NULL
            || ftx_encode_tones(mode, payload, tones, tone_count) != tone_count
            || synth_gfsk(tones, tone_count, frequency, symbol_bt, symbol_period,
                          kSampleRate, wave) != wave_samples) {
        goto cleanup;
    }
    for (index = 0; index < wave_samples; ++index) {
        slot[offset_samples + index] += amplitude * wave[index];
    }
    ok = 1;

cleanup:
    free(wave);
    free(tones);
    return ok;
}

static int decode_and_validate(ftx_mode_t mode,
                               const float *slot,
                               int slot_samples,
                               const char *const *expected_messages,
                               const float *expected_frequencies,
                               int expected_count,
                               int require_no_other_results,
                               expected_result_t *out_results) {
    const int bridge_mode = mode == FTX_MODE_FT4 ? 1 : 0;
    int handle = wsjtx3_bridge_create(bridge_mode, kSampleRate, slot_samples, 0);
    int result_count;
    int index;
    int valid = 1;
    if (handle <= 0) {
        return 0;
    }
    memset(out_results, 0, (size_t) expected_count * sizeof(*out_results));
    wsjtx3_bridge_set_options(handle, 3, 3, 2, 2, 0, 1, 200);
    wsjtx3_bridge_set_qso_frequencies(handle, 1000, 1000);
    result_count = wsjtx3_bridge_process_float(handle, slot, slot_samples);
    for (index = 0; index < result_count; ++index) {
        wsjtx3_bridge_decode_result_t result;
        int expected_index;
        int recognized = 0;
        memset(&result, 0, sizeof(result));
        if (!wsjtx3_bridge_get_result(handle, index, &result)) {
            valid = 0;
            continue;
        }
        for (expected_index = 0; expected_index < expected_count; ++expected_index) {
            if (strcmp(result.decoded, expected_messages[expected_index]) == 0) {
                out_results[expected_index].found = 1;
                out_results[expected_index].frequency = result.freq;
                out_results[expected_index].dt = result.dt;
                if (fabsf(result.freq - expected_frequencies[expected_index]) > 5.0f) {
                    valid = 0;
                }
                recognized = 1;
                break;
            }
        }
        if (require_no_other_results && !recognized) {
            valid = 0;
        }
    }
    for (index = 0; index < expected_count; ++index) {
        valid = valid && out_results[index].found;
    }
    if (expected_count == 0 && result_count != 0) {
        valid = 0;
    }
    wsjtx3_bridge_destroy(handle);
    return valid;
}

static int run_offset_cfo_awgn_case(ftx_mode_t mode) {
    const int slot_samples = mode == FTX_MODE_FT4 ? kFt4Samples : kFt8Samples;
    const char *message = mode == FTX_MODE_FT4
            ? "JA6RJK BG5JSU RR73"
            : "CQ BG5JSU OL87";
    const char *expected[] = {message};
    const float expected_frequency[] = {1003.0f};
    const int offset = kReferenceOffset + (mode == FTX_MODE_FT4 ? 480 : 960);
    const float expected_dt = (float) (offset - kReferenceOffset) / kSampleRate;
    expected_result_t result[1];
    float *slot = (float *) calloc((size_t) slot_samples, sizeof(float));
    int ok;
    if (slot == NULL) {
        return 0;
    }
    ok = add_signal(mode, message, expected_frequency[0], offset, 0.35f,
                    slot, slot_samples);
    if (ok) {
        add_awgn(slot, slot_samples, 0.10f, mode == FTX_MODE_FT4
                ? UINT32_C(0x44444444) : UINT32_C(0x88888888));
        ok = decode_and_validate(mode, slot, slot_samples, expected,
                                 expected_frequency, 1, 1, result);
    }
    if (ok && fabsf(result[0].dt - expected_dt) > 0.20f) {
        ok = 0;
    }
    free(slot);
    return ok;
}

static int run_awgn_sweep(void) {
    static const float noise_levels[] = {0.03f, 0.10f, 0.22f};
    const char *expected[] = {"CQ BG5JSU OL87"};
    const float frequency[] = {1000.0f};
    int all_valid = 1;
    size_t level;
    for (level = 0; level < sizeof(noise_levels) / sizeof(noise_levels[0]); ++level) {
        expected_result_t result[1];
        float *slot = (float *) calloc(kFt8Samples, sizeof(float));
        int valid = slot != NULL
                && add_signal(FTX_MODE_FT8, expected[0], frequency[0], kReferenceOffset,
                              0.35f, slot, kFt8Samples);
        if (valid) {
            add_awgn(slot, kFt8Samples, noise_levels[level],
                     UINT32_C(0x51000000) + (uint32_t) level);
            valid = decode_and_validate(FTX_MODE_FT8, slot, kFt8Samples,
                                        expected, frequency, 1, 1, result);
        }
        all_valid = all_valid && valid;
        free(slot);
    }
    return all_valid;
}

static int run_crowded_case(void) {
    const char *expected[] = {"CQ BG5JSU OL87", "JA6RJK BG5JSU RR73"};
    const float frequencies[] = {920.0f, 1220.0f};
    expected_result_t results[2];
    float *slot = (float *) calloc(kFt8Samples, sizeof(float));
    int ok = slot != NULL
            && add_signal(FTX_MODE_FT8, expected[0], frequencies[0], kReferenceOffset,
                          0.32f, slot, kFt8Samples)
            && add_signal(FTX_MODE_FT8, expected[1], frequencies[1], kReferenceOffset + 240,
                          0.28f, slot, kFt8Samples);
    if (ok) {
        add_awgn(slot, kFt8Samples, 0.06f, UINT32_C(0xc0ffee01));
        ok = decode_and_validate(FTX_MODE_FT8, slot, kFt8Samples,
                                 expected, frequencies, 2, 1, results);
    }
    free(slot);
    return ok;
}

static int run_noise_only_case(ftx_mode_t mode) {
    const int slot_samples = mode == FTX_MODE_FT4 ? kFt4Samples : kFt8Samples;
    expected_result_t unused[1];
    float *slot = (float *) calloc((size_t) slot_samples, sizeof(float));
    int ok = slot != NULL;
    if (ok) {
        add_awgn(slot, slot_samples, 0.35f, mode == FTX_MODE_FT4
                ? UINT32_C(0x4badf00d) : UINT32_C(0x8badf00d));
        ok = decode_and_validate(mode, slot, slot_samples, NULL, NULL, 0, 1, unused);
    }
    free(slot);
    return ok;
}

int ftx_run_channel_regression_selftests(void) {
    const int ft8_offset_ok = run_offset_cfo_awgn_case(FTX_MODE_FT8);
    const int ft4_offset_ok = run_offset_cfo_awgn_case(FTX_MODE_FT4);
    const int awgn_ok = run_awgn_sweep();
    const int crowded_ok = run_crowded_case();
    const int noise_ok = run_noise_only_case(FTX_MODE_FT8)
            && run_noise_only_case(FTX_MODE_FT4);
    printf("[%s] FT8 CFO/DT/AWGN regression\n", ft8_offset_ok ? "PASS" : "FAIL");
    printf("[%s] FT4 CFO/DT/AWGN regression\n", ft4_offset_ok ? "PASS" : "FAIL");
    printf("[%s] FT8 deterministic AWGN sweep\n", awgn_ok ? "PASS" : "FAIL");
    printf("[%s] FT8 crowded overlapping-slot decode\n", crowded_ok ? "PASS" : "FAIL");
    printf("[%s] FT8/FT4 pure-noise false-decode guard\n", noise_ok ? "PASS" : "FAIL");
    return ft8_offset_ok && ft4_offset_ok && awgn_ok && crowded_ok && noise_ok ? 0 : -1;
}
