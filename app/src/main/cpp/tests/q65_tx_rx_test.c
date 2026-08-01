#include "../common/q65_wave_size.h"
#include "../wsjtx3/wsjtx3_bridge.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

static int contains_expected_message(int handle, int count) {
    for (int index = 0; index < count; ++index) {
        wsjtx3_bridge_decode_result_t result;
        memset(&result, 0, sizeof(result));
        if (wsjtx3_bridge_get_result(handle, index, &result)
            && strcmp(result.decoded, "CQ BG5JSU OL87") == 0) {
            return 1;
        }
    }
    return 0;
}

static float next_uniform_noise(uint32_t *state) {
    *state = *state * 1664525U + 1013904223U;
    return ((float) ((*state >> 8) & 0x00ffffffU) / 8388607.5f) - 1.0f;
}

static int run_weak_averaging_case(const float *clean_slot,
                                   int slot_samples,
                                   float signal_scale) {
    float *first_frame = (float *) malloc((size_t) slot_samples * sizeof(float));
    float *second_frame = (float *) malloc((size_t) slot_samples * sizeof(float));
    uint32_t first_state = 0x13579bdfU;
    uint32_t second_state = 0x2468ace1U;
    int first_found = 0;
    int second_found = 0;
    int handle = 0;

    if (first_frame == NULL || second_frame == NULL) {
        free(first_frame);
        free(second_frame);
        return 0;
    }
    for (int index = 0; index < slot_samples; ++index) {
        first_frame[index] = signal_scale * clean_slot[index]
                             + next_uniform_noise(&first_state);
        second_frame[index] = signal_scale * clean_slot[index]
                              + next_uniform_noise(&second_state);
    }

    handle = wsjtx3_bridge_create(2, 12000, slot_samples, 0);
    if (handle > 0) {
        wsjtx3_bridge_set_q65_params(handle, 0, 60);
        wsjtx3_bridge_set_options(handle, 1, 1, 1, 2, 0, 0, 50);
        wsjtx3_bridge_set_qso_frequencies(handle, 1000, 1000);
        wsjtx3_bridge_reset(handle, 0, slot_samples);
        first_found = contains_expected_message(
                handle, wsjtx3_bridge_process_float(handle, first_frame, slot_samples));
        wsjtx3_bridge_reset(handle, 120000, slot_samples);
        second_found = contains_expected_message(
                handle, wsjtx3_bridge_process_float(handle, second_frame, slot_samples));
        wsjtx3_bridge_destroy(handle);
    }
    free(first_frame);
    free(second_frame);
    printf("Q65 weak averaging probe scale=%.4f first=%d second=%d\n",
           signal_scale, first_found, second_found);
    return !first_found && second_found;
}

int ftx_run_q65_tx_rx_selftests(void) {
    const int slot_samples = 60 * 12000;
    size_t wave_samples = 0;
    float *slot = NULL;
    int handle = 0;
    int found = 0;

    if (!ftx_q65_required_samples(60, 12000, &wave_samples)
        || wave_samples > (size_t) slot_samples) {
        return -1;
    }
    slot = (float *) calloc((size_t) slot_samples, sizeof(float));
    if (slot == NULL) {
        return -1;
    }
    if (wsjtx3_bridge_generate_q65_wave(
            "CQ BG5JSU OL87", 0, 60, 12000, 1000.0f,
            slot, (int) wave_samples) != (int) wave_samples) {
        free(slot);
        return -1;
    }

    handle = wsjtx3_bridge_create(2, 12000, slot_samples, 0);
    if (handle > 0) {
        wsjtx3_bridge_set_q65_params(handle, 0, 60);
        wsjtx3_bridge_set_options(handle, 1, 1, 1, 2, 0, 0, 50);
        wsjtx3_bridge_set_qso_frequencies(handle, 1000, 1000);
        const int count = wsjtx3_bridge_process_float(handle, slot, slot_samples);
        found = contains_expected_message(handle, count);
        wsjtx3_bridge_destroy(handle);
    }
    int weak_averaging_found = 0;
    const float weak_scales[] = {0.020f, 0.030f, 0.040f, 0.055f, 0.075f};
    for (size_t index = 0;
         found && !weak_averaging_found
         && index < sizeof(weak_scales) / sizeof(weak_scales[0]);
         ++index) {
        weak_averaging_found = run_weak_averaging_case(
                slot, slot_samples, weak_scales[index]);
    }
    free(slot);
    printf("[%s] Q65A/60 clean TX to RX loopback\n", found ? "PASS" : "FAIL");
    printf("[%s] Q65A/60 two-frame weak averaging decode\n",
           weak_averaging_found ? "PASS" : "FAIL");
    return found && weak_averaging_found ? 0 : -1;
}
