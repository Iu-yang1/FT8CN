#include "../ft8Encoder.h"
#include "../common/resampler.h"
#include "../ft8/constants.h"
#include "../ftx_core/include/ftx_encoder.h"
#include "../wsjtx3/wsjtx3_bridge.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum {
    kSampleRate = 12000,
    kFt8SlotSamples = 180000,
    kFt4SlotSamples = 72576,
    kSignalOffsetSamples = 6000
};

static int run_synthetic_case(ftx_mode_t mode,
                              const char *expected_text,
                              int source_sample_rate) {
    const int tone_count = ftx_get_tone_count(mode);
    const int slot_samples = mode == FTX_MODE_FT4 ? kFt4SlotSamples : kFt8SlotSamples;
    const int rate_factor = source_sample_rate / kSampleRate;
    const int source_slot_samples = slot_samples * rate_factor;
    const int source_signal_offset = kSignalOffsetSamples * rate_factor;
    const float symbol_period = mode == FTX_MODE_FT4 ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
    const float symbol_bt = mode == FTX_MODE_FT4 ? 1.0f : FT8_SYMBOL_BT;
    const int wave_samples = (int) (0.5f + tone_count * symbol_period * source_sample_rate);
    uint8_t payload[FTX_PAYLOAD_BYTES] = {0};
    uint8_t *tones = NULL;
    float *slot = NULL;
    float *resampled = NULL;
    const float *decoder_slot = NULL;
    int handle = 0;
    int found = 0;
    int result_count;

    if ((source_sample_rate != 12000
         && source_sample_rate != 24000
         && source_sample_rate != 48000)
        || tone_count <= 0 || wave_samples <= 0
        || source_signal_offset > source_slot_samples - wave_samples ||
        ftx_pack_message(expected_text, payload) < 0) {
        return 0;
    }

    tones = (uint8_t *) calloc((size_t) tone_count, sizeof(uint8_t));
    slot = (float *) calloc((size_t) source_slot_samples, sizeof(float));
    if (tones == NULL || slot == NULL) {
        goto cleanup;
    }
    if (ftx_encode_tones(mode, payload, tones, tone_count) != tone_count ||
        synth_gfsk(tones,
                   tone_count,
                   1000.0f,
                   symbol_bt,
                   symbol_period,
                   source_sample_rate,
                   slot + source_signal_offset) != wave_samples) {
        goto cleanup;
    }

    decoder_slot = slot;
    if (source_sample_rate != kSampleRate) {
        size_t written = 0;
        resampled = (float *) calloc((size_t) slot_samples, sizeof(float));
        if (resampled == NULL
            || ftx_resample_float_mono(slot,
                                       (size_t) source_slot_samples,
                                       source_sample_rate,
                                       kSampleRate,
                                       resampled,
                                       (size_t) slot_samples,
                                       &written) != FTX_RESAMPLE_OK
            || written != (size_t) slot_samples) {
            goto cleanup;
        }
        decoder_slot = resampled;
    }

    handle = wsjtx3_bridge_create(mode == FTX_MODE_FT4 ? 1 : 0,
                                  kSampleRate,
                                  slot_samples,
                                  0);
    if (handle <= 0) {
        goto cleanup;
    }
    wsjtx3_bridge_set_options(handle, 2, 2, 1, 1, 0, 0, 50);
    wsjtx3_bridge_set_qso_frequencies(handle, 1000, 1000);
    result_count = wsjtx3_bridge_process_float(handle, decoder_slot, slot_samples);
    for (int index = 0; index < result_count; ++index) {
        wsjtx3_bridge_decode_result_t result;
        memset(&result, 0, sizeof(result));
        if (wsjtx3_bridge_get_result(handle, index, &result) &&
            strcmp(result.decoded, expected_text) == 0) {
            found = 1;
            break;
        }
    }

cleanup:
    if (handle > 0) {
        wsjtx3_bridge_destroy(handle);
    }
    free(resampled);
    free(slot);
    free(tones);
    return found;
}

int ftx_run_synthetic_decode_selftests(void) {
    int ft8_ok = 1;
    int ft4_ok = 1;
    const int sample_rates[] = {12000, 24000, 48000};
    for (size_t index = 0; index < sizeof(sample_rates) / sizeof(sample_rates[0]); ++index) {
        const int sample_rate = sample_rates[index];
        const int current_ft8 = run_synthetic_case(
                FTX_MODE_FT8, "CQ BG5JSU OL87", sample_rate);
        const int current_ft4 = run_synthetic_case(
                FTX_MODE_FT4, "JA6RJK BG5JSU RR73", sample_rate);
        printf("[%s] FT8 synthetic decode at %d Hz\n",
               current_ft8 ? "PASS" : "FAIL", sample_rate);
        printf("[%s] FT4 synthetic decode at %d Hz\n",
               current_ft4 ? "PASS" : "FAIL", sample_rate);
        ft8_ok = ft8_ok && current_ft8;
        ft4_ok = ft4_ok && current_ft4;
    }
    return ft8_ok && ft4_ok ? 0 : -1;
}
