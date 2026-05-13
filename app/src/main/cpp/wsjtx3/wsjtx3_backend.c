#include "wsjtx3_backend.h"

#include <stdlib.h>
#include <string.h>

#ifndef FT8CN_ENABLE_WSJTX3_BACKEND
#define FT8CN_ENABLE_WSJTX3_BACKEND 0
#endif

typedef struct {
    int64_t utc_time;
    int sample_rate;
    int num_samples;
    int ldpc_iterations;
    bool is_ft8;
    wsjtx_decoder_options_t options;
    ap_hints_t ap_hints;
} wsjtx3_backend_state_t;

static wsjtx3_backend_state_t *get_state(decoder_t *decoder) {
    return (decoder == NULL) ? NULL : (wsjtx3_backend_state_t *) decoder->backend_state;
}

bool wsjtx3_backend_init_decoder(decoder_t *decoder,
                                 int64_t utcTime,
                                 int sample_rate,
                                 int num_samples,
                                 bool is_ft8) {
    if (decoder == NULL) {
        return false;
    }

#if FT8CN_ENABLE_WSJTX3_BACKEND
    wsjtx3_backend_state_t *state = (wsjtx3_backend_state_t *) calloc(1, sizeof(*state));
    if (state == NULL) {
        return false;
    }

    state->utc_time = utcTime;
    state->sample_rate = sample_rate;
    state->num_samples = num_samples;
    state->ldpc_iterations = fast_kLDPC_iterations;
    state->is_ft8 = is_ft8;
    decoder->backend_state = state;

    /*
     * 官方 WSJT-X 3.0 源码已经 vendoring 到仓库，但 Android Fortran bridge
     * 还没有真正接上，所以这里暂时返回 false，让上层决定是否回退。
     */
    free(state);
    decoder->backend_state = NULL;
    return false;
#else
    (void) utcTime;
    (void) sample_rate;
    (void) num_samples;
    (void) is_ft8;
    return false;
#endif
}

void wsjtx3_backend_free_decoder(decoder_t *decoder) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL) {
        return;
    }

    free(state);
    decoder->backend_state = NULL;
}

void wsjtx3_backend_monitor_press(decoder_t *decoder, const float *signal, int sample_count) {
    (void) decoder;
    (void) signal;
    (void) sample_count;
}

int wsjtx3_backend_find_sync(decoder_t *decoder) {
    (void) decoder;
    return 0;
}

ft8_message wsjtx3_backend_analyze(decoder_t *decoder, int idx) {
    (void) decoder;
    (void) idx;

    ft8_message empty_message;
    memset(&empty_message, 0, sizeof(empty_message));
    return empty_message;
}

void wsjtx3_backend_reset(decoder_t *decoder, long utcTime, int num_samples) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL) {
        return;
    }

    state->utc_time = utcTime;
    state->num_samples = num_samples;
}

void wsjtx3_backend_get_a91(decoder_t *decoder, uint8_t out[FTX_LDPC_K_BYTES]) {
    (void) decoder;
    if (out != NULL) {
        memset(out, 0, FTX_LDPC_K_BYTES);
    }
}

void wsjtx3_backend_set_ldpc_iterations(decoder_t *decoder, int iterations) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL) {
        return;
    }

    if (iterations < 1) {
        iterations = 1;
    }
    state->ldpc_iterations = iterations;
}

void wsjtx3_backend_set_ap_hints(decoder_t *decoder, const ap_hints_t *ap_hints) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL) {
        return;
    }

    if (ap_hints == NULL) {
        memset(&state->ap_hints, 0, sizeof(state->ap_hints));
        return;
    }
    memcpy(&state->ap_hints, ap_hints, sizeof(state->ap_hints));
}

void wsjtx3_backend_set_options(decoder_t *decoder, const wsjtx_decoder_options_t *options) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL) {
        return;
    }

    if (options == NULL) {
        memset(&state->options, 0, sizeof(state->options));
        return;
    }
    memcpy(&state->options, options, sizeof(state->options));
}

bool wsjtx3_backend_owns_session_flow(decoder_t *decoder) {
    (void) decoder;
    return true;
}

void wsjtx3_backend_subtract_signal(decoder_t *decoder,
                                    const uint8_t *payload,
                                    int sample_rate,
                                    float frequency,
                                    float time_sec,
                                    int mode) {
    (void) decoder;
    (void) payload;
    (void) sample_rate;
    (void) frequency;
    (void) time_sec;
    (void) mode;
}
