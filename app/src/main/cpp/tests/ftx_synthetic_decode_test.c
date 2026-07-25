#include "../ft8Encoder.h"
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

static int run_synthetic_case(ftx_mode_t mode, const char *expected_text) {
    const int tone_count = ftx_get_tone_count(mode);
    const int slot_samples = mode == FTX_MODE_FT4 ? kFt4SlotSamples : kFt8SlotSamples;
    const float symbol_period = mode == FTX_MODE_FT4 ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
    const float symbol_bt = mode == FTX_MODE_FT4 ? 1.0f : FT8_SYMBOL_BT;
    const int wave_samples = (int) (0.5f + tone_count * symbol_period * kSampleRate);
    uint8_t payload[FTX_PAYLOAD_BYTES] = {0};
    uint8_t *tones = NULL;
    float *slot = NULL;
    int handle = 0;
    int found = 0;
    int result_count;

    if (tone_count <= 0 || wave_samples <= 0 ||
        kSignalOffsetSamples > slot_samples - wave_samples ||
        ftx_pack_message(expected_text, payload) < 0) {
        return 0;
    }

    tones = (uint8_t *) calloc((size_t) tone_count, sizeof(uint8_t));
    slot = (float *) calloc((size_t) slot_samples, sizeof(float));
    if (tones == NULL || slot == NULL) {
        goto cleanup;
    }
    if (ftx_encode_tones(mode, payload, tones, tone_count) != tone_count ||
        synth_gfsk(tones,
                   tone_count,
                   1000.0f,
                   symbol_bt,
                   symbol_period,
                   kSampleRate,
                   slot + kSignalOffsetSamples) != wave_samples) {
        goto cleanup;
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
    result_count = wsjtx3_bridge_process_float(handle, slot, slot_samples);
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
    free(slot);
    free(tones);
    return found;
}

int ftx_run_synthetic_decode_selftests(void) {
    const int ft8_ok = run_synthetic_case(FTX_MODE_FT8, "CQ BG5JSU OL87");
    const int ft4_ok = run_synthetic_case(FTX_MODE_FT4, "JA6RJK BG5JSU RR73");

    printf("[%s] FT8 synthetic waveform decode\n", ft8_ok ? "PASS" : "FAIL");
    printf("[%s] FT4 synthetic waveform decode\n", ft4_ok ? "PASS" : "FAIL");
    return ft8_ok && ft4_ok ? 0 : -1;
}
