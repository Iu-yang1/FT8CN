#include "wsjtx3_backend.h"

#include "wsjtx3_bridge.h"
#include "../ft8/constants.h"
#include "../ft8/crc.h"
#include "../ft8/pack.h"
#include "../ft8/text.h"
#include "../ft8/unpack.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#if defined(_WIN32)
#include <windows.h>
#else
#include <pthread.h>
#endif

#if defined(ANDROID)
#include <android/log.h>
#define WSJTX3_LOG_TAG "WSJTX3Backend"
#define WSJTX3_LOGI(...) __android_log_print(ANDROID_LOG_INFO, WSJTX3_LOG_TAG, __VA_ARGS__)
#define WSJTX3_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, WSJTX3_LOG_TAG, __VA_ARGS__)
#else
#define WSJTX3_LOGI(...) ((void) 0)
#define WSJTX3_LOGE(...) ((void) 0)
#endif

#ifndef FT8CN_ENABLE_WSJTX3_BACKEND
#define FT8CN_ENABLE_WSJTX3_BACKEND 0
#endif

enum {
    kWsjtDefaultQsoFrequencyHz = 1000,
    kWsjtDefaultTxFrequencyHz = 1000,
    kFtxPayloadBytes = 10
};

typedef struct {
    int64_t utc_time;
    int sample_rate;
    int expected_samples;
    int last_sample_count;
    int last_bridge_raw_count;
    int last_merged_count;
    int ldpc_iterations;
    int qso_frequency_hz;
    int tx_frequency_hz;
    int bridge_handle;
    bool is_ft8;
    wsjtx_decoder_options_t options;
    ap_hints_t ap_hints;
    float *raw_samples;
    int raw_capacity;
    ft8_message session_results[kMax_decoded_messages];
    int session_result_count;
    uint8_t current_a91[FTX_LDPC_K_BYTES];
} wsjtx3_backend_state_t;

#if defined(_WIN32)
static INIT_ONCE g_wsjtx3_bridge_lock_once = INIT_ONCE_STATIC_INIT;
static CRITICAL_SECTION g_wsjtx3_bridge_lock;

static BOOL CALLBACK init_wsjtx3_bridge_lock(PINIT_ONCE init_once,
                                             PVOID parameter,
                                             PVOID *context) {
    (void) init_once;
    (void) parameter;
    (void) context;
    InitializeCriticalSection(&g_wsjtx3_bridge_lock);
    return TRUE;
}

static void wsjtx3_bridge_lock(void) {
    InitOnceExecuteOnce(&g_wsjtx3_bridge_lock_once,
                        init_wsjtx3_bridge_lock,
                        NULL,
                        NULL);
    EnterCriticalSection(&g_wsjtx3_bridge_lock);
}

static void wsjtx3_bridge_unlock(void) {
    LeaveCriticalSection(&g_wsjtx3_bridge_lock);
}
#else
static pthread_mutex_t g_wsjtx3_bridge_lock = PTHREAD_MUTEX_INITIALIZER;

static void wsjtx3_bridge_lock(void) {
    pthread_mutex_lock(&g_wsjtx3_bridge_lock);
}

static void wsjtx3_bridge_unlock(void) {
    pthread_mutex_unlock(&g_wsjtx3_bridge_lock);
}
#endif

static int bridge_create_locked(int is_ft8,
                                int sample_rate,
                                int expected_samples,
                                int64_t utc_time) {
    int handle;
    wsjtx3_bridge_lock();
    handle = wsjtx3_bridge_create(is_ft8, sample_rate, expected_samples, utc_time);
    wsjtx3_bridge_unlock();
    return handle;
}

static void bridge_destroy_locked(int handle) {
    wsjtx3_bridge_lock();
    wsjtx3_bridge_destroy(handle);
    wsjtx3_bridge_unlock();
}

static void bridge_reset_locked(int handle, int64_t utc_time, int expected_samples) {
    wsjtx3_bridge_lock();
    wsjtx3_bridge_reset(handle, utc_time, expected_samples);
    wsjtx3_bridge_unlock();
}

static void bridge_set_options_locked(int handle,
                                      int decode_pass_count,
                                      int multi_decode_round_count,
                                      int qso_freq_sensitivity,
                                      int decode_sensitivity,
                                      int enable_early_decode,
                                      int enable_wideband_dx_search,
                                      int ldpc_iterations) {
    wsjtx3_bridge_lock();
    wsjtx3_bridge_set_options(handle,
                              decode_pass_count,
                              multi_decode_round_count,
                              qso_freq_sensitivity,
                              decode_sensitivity,
                              enable_early_decode,
                              enable_wideband_dx_search,
                              ldpc_iterations);
    wsjtx3_bridge_unlock();
}

static void bridge_set_ap_hints_locked(int handle,
                                       const char *my_call,
                                       const char *his_call,
                                       const char *his_grid) {
    wsjtx3_bridge_lock();
    wsjtx3_bridge_set_ap_hints(handle, my_call, his_call, his_grid);
    wsjtx3_bridge_unlock();
}

static void bridge_set_qso_frequencies_locked(int handle,
                                              int qso_frequency_hz,
                                              int tx_frequency_hz) {
    wsjtx3_bridge_lock();
    wsjtx3_bridge_set_qso_frequencies(handle, qso_frequency_hz, tx_frequency_hz);
    wsjtx3_bridge_unlock();
}

static int bridge_process_float_locked(int handle, const float *samples, int sample_count) {
    int bridge_count;
    wsjtx3_bridge_lock();
    bridge_count = wsjtx3_bridge_process_float(handle, samples, sample_count);
    wsjtx3_bridge_unlock();
    return bridge_count;
}

static int bridge_get_result_locked(int handle,
                                    int index,
                                    wsjtx3_bridge_decode_result_t *out_result) {
    int ok;
    wsjtx3_bridge_lock();
    ok = wsjtx3_bridge_get_result(handle, index, out_result);
    wsjtx3_bridge_unlock();
    return ok;
}

static wsjtx3_backend_state_t *get_state(decoder_t *decoder) {
    return (decoder == NULL) ? NULL : (wsjtx3_backend_state_t *) decoder->backend_state;
}

static int clamp_i16(int value) {
    if (value > 32767) {
        return 32767;
    }
    if (value < -32768) {
        return -32768;
    }
    return value;
}

static float backend_symbol_period(const wsjtx3_backend_state_t *state) {
    return (state != NULL && !state->is_ft8) ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
}

static int score_from_sync(float sync_value) {
    const float scaled = sync_value * 10.0f;
    if (scaled > 32767.0f) {
        return 32767;
    }
    if (scaled < -32768.0f) {
        return -32768;
    }
    return (int) lroundf(scaled);
}

static void clear_decoder_result_view(decoder_t *decoder) {
    if (decoder == NULL) {
        return;
    }

    decoder->num_candidates = 0;
    decoder->num_decoded = 0;
    memset(decoder->candidate_list, 0, sizeof(decoder->candidate_list));
    memset(decoder->decoded, 0, sizeof(decoder->decoded));
    memset(decoder->decoded_freq_hz, 0, sizeof(decoder->decoded_freq_hz));
    memset(decoder->a91, 0, sizeof(decoder->a91));
    for (int index = 0; index < kMax_decoded_messages; ++index) {
        decoder->decoded_hashtable[index] = NULL;
    }
}

static void publish_message_lookup(decoder_t *decoder, const ft8_message *decoded) {
    int slot_index;
    int probe_count;

    if (decoder == NULL || decoded == NULL) {
        return;
    }

    slot_index = decoded->message.hash % kMax_decoded_messages;
    if (slot_index < 0) {
        slot_index += kMax_decoded_messages;
    }

    for (probe_count = 0; probe_count < kMax_decoded_messages; ++probe_count) {
        if (decoder->decoded_hashtable[slot_index] == NULL) {
            decoder->decoded[slot_index] = decoded->message;
            decoder->decoded_freq_hz[slot_index] = decoded->freq_hz;
            decoder->decoded_hashtable[slot_index] = &decoder->decoded[slot_index];
            ++decoder->num_decoded;
            return;
        }
        slot_index = (slot_index + 1) % kMax_decoded_messages;
    }
}

static void populate_candidate_from_bridge_result(const wsjtx3_backend_state_t *state,
                                                  const wsjtx3_bridge_decode_result_t *bridge_result,
                                                  candidate_t *candidate) {
    float symbol_period;
    float quantized_freq;
    float quantized_time;
    int oversampled_freq;
    int time_steps;

    if (bridge_result == NULL || candidate == NULL) {
        return;
    }

    memset(candidate, 0, sizeof(*candidate));
    candidate->score = (int16_t) score_from_sync(bridge_result->sync);
    candidate->snr = bridge_result->snr;

    symbol_period = backend_symbol_period(state);
    quantized_freq = bridge_result->freq * symbol_period * (float) kFreq_osr;
    oversampled_freq = (int) lroundf(quantized_freq);
    if (oversampled_freq < 0) {
        oversampled_freq = 0;
    }
    candidate->freq_offset = (int16_t) clamp_i16(oversampled_freq / kFreq_osr);
    candidate->freq_sub = (uint8_t) (oversampled_freq % kFreq_osr);

    quantized_time = (bridge_result->dt * (float) kTime_osr) / symbol_period;
    time_steps = (int) lroundf(quantized_time);
    candidate->time_offset = (int16_t) clamp_i16(time_steps);
    candidate->time_sub = 0;
}

static void publish_session_results_to_decoder(decoder_t *decoder,
                                               const wsjtx3_backend_state_t *state) {
    int result_count;
    int index;

    clear_decoder_result_view(decoder);
    if (decoder == NULL || state == NULL) {
        return;
    }

    result_count = state->session_result_count;
    if (result_count > kMax_candidates) {
        result_count = kMax_candidates;
    }

    for (index = 0; index < result_count; ++index) {
        decoder->candidate_list[index] = state->session_results[index].candidate;
        publish_message_lookup(decoder, &state->session_results[index]);
    }

    decoder->num_candidates = result_count;
}

static void reset_backend_results(decoder_t *decoder, wsjtx3_backend_state_t *state) {
    if (state == NULL) {
        return;
    }
    state->session_result_count = 0;
    state->last_bridge_raw_count = 0;
    state->last_merged_count = 0;
    memset(state->session_results, 0, sizeof(state->session_results));
    memset(state->current_a91, 0, sizeof(state->current_a91));
    clear_decoder_result_view(decoder);
}

static void copy_text(char *dst, size_t dst_size, const char *src) {
    if (dst == NULL || dst_size == 0) {
        return;
    }
    if (src == NULL) {
        dst[0] = '\0';
        return;
    }
    snprintf(dst, dst_size, "%s", src);
}

static bool has_visible_text(const char *text) {
    if (text == NULL) {
        return false;
    }
    while (*text != '\0') {
        if (*text != ' ') {
            return true;
        }
        ++text;
    }
    return false;
}

static uint32_t fallback_text_hash(const char *text) {
    uint32_t hash = 2166136261u;
    if (text == NULL) {
        return hash;
    }
    while (*text != '\0') {
        hash ^= (uint8_t) *text++;
        hash *= 16777619u;
    }
    return hash;
}

static void select_primary_ap_hint(const ap_hints_t *ap_hints,
                                   char his_call[FTX_AP_CALLSIGN_MAX],
                                   char his_grid[FTX_AP_GRID_MAX]) {
    int index;
    his_call[0] = '\0';
    his_grid[0] = '\0';
    if (ap_hints == NULL) {
        return;
    }
    for (index = 0; index < ap_hints->hint_call_count && index < FTX_AP_MAX_HINT_CALLS; ++index) {
        if (ap_hints->hint_calls[index][0] == '\0') {
            continue;
        }
        copy_text(his_call, FTX_AP_CALLSIGN_MAX, ap_hints->hint_calls[index]);
        copy_text(his_grid, FTX_AP_GRID_MAX, ap_hints->hint_grids[index]);
        return;
    }
}

static int count_ap_hints(const ap_hints_t *ap_hints) {
    int index;
    int count = 0;

    if (ap_hints == NULL) {
        return 0;
    }

    for (index = 0; index < ap_hints->hint_call_count && index < FTX_AP_MAX_HINT_CALLS; ++index) {
        if (ap_hints->hint_calls[index][0] != '\0') {
            ++count;
        }
    }
    return count;
}

static bool copy_ap_hint_at(const ap_hints_t *ap_hints,
                            int hint_index,
                            char his_call[FTX_AP_CALLSIGN_MAX],
                            char his_grid[FTX_AP_GRID_MAX]) {
    int index;
    int visible_index = 0;

    his_call[0] = '\0';
    his_grid[0] = '\0';
    if (ap_hints == NULL || hint_index < 0) {
        return false;
    }

    for (index = 0; index < ap_hints->hint_call_count && index < FTX_AP_MAX_HINT_CALLS; ++index) {
        if (ap_hints->hint_calls[index][0] == '\0') {
            continue;
        }
        if (visible_index == hint_index) {
            copy_text(his_call, FTX_AP_CALLSIGN_MAX, ap_hints->hint_calls[index]);
            copy_text(his_grid, FTX_AP_GRID_MAX, ap_hints->hint_grids[index]);
            return true;
        }
        ++visible_index;
    }
    return false;
}

static int ft4_followup_round_budget(const wsjtx3_backend_state_t *state) {
    int budget;

    if (state == NULL) {
        return 0;
    }

    budget = state->options.multi_decode_round_count - 1;
    if (budget <= 0) {
        return 0;
    }

    if (!state->options.enable_wideband_dx_search || state->options.qso_freq_sensitivity == 0) {
        budget = 1;
    }
    if (state->options.decode_sensitivity == 0 && budget > 1) {
        budget = 1;
    }
    if (budget > FTX_AP_MAX_HINT_CALLS) {
        budget = FTX_AP_MAX_HINT_CALLS;
    }
    return budget;
}

static void build_storage_a91(bool is_ft8,
                              const uint8_t payload[kFtxPayloadBytes],
                              uint8_t storage_a91[FTX_LDPC_K_BYTES],
                              uint16_t *out_crc) {
    uint8_t encoded_payload[kFtxPayloadBytes];
    uint8_t encoded_a91[FTX_LDPC_K_BYTES];

    memset(storage_a91, 0, FTX_LDPC_K_BYTES);
    memset(encoded_payload, 0, sizeof(encoded_payload));
    memset(encoded_a91, 0, sizeof(encoded_a91));
    memcpy(encoded_payload, payload, sizeof(encoded_payload));

    if (!is_ft8) {
        for (int index = 0; index < kFtxPayloadBytes; ++index) {
            encoded_payload[index] ^= kFT4XORSequence[index];
        }
    }

    ftx_add_crc(encoded_payload, encoded_a91);
    memcpy(storage_a91, encoded_a91, sizeof(encoded_a91));

    if (!is_ft8) {
        for (int index = 0; index < kFtxPayloadBytes; ++index) {
            storage_a91[index] ^= kFT4XORSequence[index];
        }
    }

    if (out_crc != NULL) {
        *out_crc = ftx_extract_crc(encoded_a91);
    }
}

static void fill_message_fallback(message_t *message, const char *decoded_text) {
    memset(message, 0, sizeof(*message));
    copy_text(message->text, sizeof(message->text), decoded_text);
    message->report = -100;
    message->hash = (uint16_t) (fallback_text_hash(decoded_text) & 0xFFFFu);
}

static void build_message_from_text(bool is_ft8,
                                    const char *decoded_text,
                                    message_t *message) {
    uint8_t payload[kFtxPayloadBytes];
    uint16_t crc_value = 0;

    memset(payload, 0, sizeof(payload));
    memset(message, 0, sizeof(*message));

    if (decoded_text == NULL || pack77(decoded_text, payload) != 0) {
        fill_message_fallback(message, decoded_text);
        return;
    }

    if (unpackToMessage_t(payload, message) < 0 || !has_visible_text(message->text)) {
        fill_message_fallback(message, decoded_text);
        build_storage_a91(is_ft8, payload, message->a91, &crc_value);
        message->hash = crc_value;
        return;
    }

    build_storage_a91(is_ft8, payload, message->a91, &crc_value);
    message->hash = crc_value;
}

static bool same_decoded_text(const ft8_message *lhs, const ft8_message *rhs) {
    return lhs != NULL &&
           rhs != NULL &&
           lhs->message.hash == rhs->message.hash &&
           strcmp(lhs->message.text, rhs->message.text) == 0;
}

static bool same_frequency_bucket(const ft8_message *lhs, const ft8_message *rhs) {
    if (lhs == NULL || rhs == NULL) {
        return false;
    }
    if (lhs->freq_hz <= 0.0f || rhs->freq_hz <= 0.0f) {
        return true;
    }
    return fabsf(lhs->freq_hz - rhs->freq_hz) <= 20.0f;
}

static int find_duplicate_index(const wsjtx3_backend_state_t *state, const ft8_message *candidate) {
    for (int index = 0; index < state->session_result_count; ++index) {
        const ft8_message *existing = &state->session_results[index];
        if (same_decoded_text(existing, candidate) && same_frequency_bucket(existing, candidate)) {
            return index;
        }
    }
    return -1;
}

static bool prefer_candidate(const ft8_message *candidate, const ft8_message *existing) {
    if (candidate->candidate.score != existing->candidate.score) {
        return candidate->candidate.score > existing->candidate.score;
    }
    if (candidate->snr != existing->snr) {
        return candidate->snr > existing->snr;
    }
    if (fabsf(candidate->time_sec) != fabsf(existing->time_sec)) {
        return fabsf(candidate->time_sec) < fabsf(existing->time_sec);
    }
    return candidate->freq_hz < existing->freq_hz;
}

static int compare_session_results(const void *lhs_ptr, const void *rhs_ptr) {
    const ft8_message *lhs = (const ft8_message *) lhs_ptr;
    const ft8_message *rhs = (const ft8_message *) rhs_ptr;
    if (lhs->candidate.score != rhs->candidate.score) {
        return (rhs->candidate.score - lhs->candidate.score);
    }
    if (lhs->snr != rhs->snr) {
        return rhs->snr - lhs->snr;
    }
    if (fabsf(lhs->time_sec) < fabsf(rhs->time_sec)) {
        return -1;
    }
    if (fabsf(lhs->time_sec) > fabsf(rhs->time_sec)) {
        return 1;
    }
    if (lhs->freq_hz < rhs->freq_hz) {
        return -1;
    }
    if (lhs->freq_hz > rhs->freq_hz) {
        return 1;
    }
    return strcmp(lhs->message.text, rhs->message.text);
}

static void sync_bridge_options(wsjtx3_backend_state_t *state) {
#if FT8CN_ENABLE_WSJTX3_BACKEND
    char his_call[FTX_AP_CALLSIGN_MAX];
    char his_grid[FTX_AP_GRID_MAX];

    if (state == NULL || state->bridge_handle <= 0) {
        return;
    }

    select_primary_ap_hint(&state->ap_hints, his_call, his_grid);
    bridge_set_options_locked(state->bridge_handle,
                              state->options.decode_pass_count,
                              state->options.multi_decode_round_count,
                              state->options.qso_freq_sensitivity,
                              state->options.decode_sensitivity,
                              state->options.enable_early_decode ? 1 : 0,
                              state->options.enable_wideband_dx_search ? 1 : 0,
                              state->ldpc_iterations);
    bridge_set_ap_hints_locked(state->bridge_handle, state->ap_hints.my_call, his_call, his_grid);
    bridge_set_qso_frequencies_locked(state->bridge_handle,
                                      state->qso_frequency_hz,
                                      state->tx_frequency_hz);
#else
    (void) state;
#endif
}

static void push_bridge_options(const wsjtx3_backend_state_t *state,
                                const wsjtx_decoder_options_t *options,
                                const char *his_call,
                                const char *his_grid) {
#if FT8CN_ENABLE_WSJTX3_BACKEND
    const wsjtx_decoder_options_t *effective_options = options;
    static const wsjtx_decoder_options_t default_options = {0};

    if (state == NULL || state->bridge_handle <= 0) {
        return;
    }
    if (effective_options == NULL) {
        effective_options = &default_options;
    }

    bridge_set_options_locked(state->bridge_handle,
                              effective_options->decode_pass_count,
                              effective_options->multi_decode_round_count,
                              effective_options->qso_freq_sensitivity,
                              effective_options->decode_sensitivity,
                              effective_options->enable_early_decode ? 1 : 0,
                              effective_options->enable_wideband_dx_search ? 1 : 0,
                              state->ldpc_iterations);
    bridge_set_ap_hints_locked(state->bridge_handle,
                               state->ap_hints.my_call,
                               his_call == NULL ? "" : his_call,
                               his_grid == NULL ? "" : his_grid);
    bridge_set_qso_frequencies_locked(state->bridge_handle,
                                      state->qso_frequency_hz,
                                      state->tx_frequency_hz);
#else
    (void) state;
    (void) options;
    (void) his_call;
    (void) his_grid;
#endif
}

static void merge_bridge_results(wsjtx3_backend_state_t *state, int bridge_count) {
    int index;

    if (state == NULL || bridge_count <= 0) {
        return;
    }

    for (index = 0; index < bridge_count; ++index) {
        wsjtx3_bridge_decode_result_t bridge_result;
        ft8_message decoded;

        memset(&bridge_result, 0, sizeof(bridge_result));
        if (!bridge_get_result_locked(state->bridge_handle, index, &bridge_result) ||
            !has_visible_text(bridge_result.decoded)) {
            continue;
        }

        memset(&decoded, 0, sizeof(decoded));
        decoded.utcTime = state->utc_time;
        decoded.isValid = true;
        decoded.snr = bridge_result.snr;
        decoded.time_sec = bridge_result.dt;
        decoded.freq_hz = bridge_result.freq;
        populate_candidate_from_bridge_result(state, &bridge_result, &decoded.candidate);
        build_message_from_text(state->is_ft8, bridge_result.decoded, &decoded.message);

        {
            const int duplicate_index = find_duplicate_index(state, &decoded);
            if (duplicate_index >= 0) {
                if (prefer_candidate(&decoded, &state->session_results[duplicate_index])) {
                    state->session_results[duplicate_index] = decoded;
                }
                continue;
            }
        }

        if (state->session_result_count >= kMax_decoded_messages) {
            continue;
        }
        state->session_results[state->session_result_count++] = decoded;
    }
}

static int run_bridge_pass(wsjtx3_backend_state_t *state,
                           int sample_count,
                           const wsjtx_decoder_options_t *options,
                           const char *his_call,
                           const char *his_grid,
                           const char *pass_label,
                           int pass_index,
                           int pass_total) {
#if FT8CN_ENABLE_WSJTX3_BACKEND
    const int merged_before = state->session_result_count;
    const int bridge_count = bridge_process_float_locked(state->bridge_handle,
                                                         state->raw_samples,
                                                         sample_count);

    WSJTX3_LOGI("find_sync bridge pass=%s index=%d/%d handle=%d samples=%d bridgeCount=%d ldpc=%d passes=%d rounds=%d early=%d wideband=%d hisCall=%s",
                pass_label == NULL ? "default" : pass_label,
                pass_index,
                pass_total,
                state->bridge_handle,
                sample_count,
                bridge_count,
                state->ldpc_iterations,
                options == NULL ? 0 : options->decode_pass_count,
                options == NULL ? 0 : options->multi_decode_round_count,
                options != NULL && options->enable_early_decode ? 1 : 0,
                options != NULL && options->enable_wideband_dx_search ? 1 : 0,
                his_call == NULL ? "" : his_call);

    merge_bridge_results(state, bridge_count);
    WSJTX3_LOGI("find_sync merge pass=%s handle=%d rawBridgeCount=%d mergedCount=%d totalMerged=%d",
                pass_label == NULL ? "default" : pass_label,
                state->bridge_handle,
                bridge_count,
                state->session_result_count - merged_before,
                state->session_result_count);
    return bridge_count;
#else
    (void) state;
    (void) sample_count;
    (void) options;
    (void) his_call;
    (void) his_grid;
    (void) pass_label;
    (void) pass_index;
    (void) pass_total;
    return 0;
#endif
}

static int run_ft8_session(decoder_t *decoder,
                           wsjtx3_backend_state_t *state,
                           int sample_count) {
    char his_call[FTX_AP_CALLSIGN_MAX];
    char his_grid[FTX_AP_GRID_MAX];

    (void) decoder;
    if (state == NULL) {
        return 0;
    }

    his_call[0] = '\0';
    his_grid[0] = '\0';
    push_bridge_options(state, &state->options, his_call, his_grid);
    return run_bridge_pass(state,
                           sample_count,
                           &state->options,
                           his_call,
                           his_grid,
                           "ft8-main",
                           1,
                           1);
}

static int run_ft4_session(decoder_t *decoder,
                           wsjtx3_backend_state_t *state,
                           int sample_count) {
    int hint_count;
    int round_budget;
    int total_passes;
    int total_bridge_count = 0;
    int pass_index = 1;
    char his_call[FTX_AP_CALLSIGN_MAX];
    char his_grid[FTX_AP_GRID_MAX];

    (void) decoder;
    if (decoder == NULL || state == NULL) {
        return 0;
    }

    hint_count = count_ap_hints(&state->ap_hints);
    round_budget = ft4_followup_round_budget(state);
    if (round_budget > hint_count) {
        round_budget = hint_count;
    }
    total_passes = 1 + round_budget;

    his_call[0] = '\0';
    his_grid[0] = '\0';
    push_bridge_options(state, &state->options, his_call, his_grid);
    total_bridge_count += run_bridge_pass(state,
                                          sample_count,
                                          &state->options,
                                          his_call,
                                          his_grid,
                                          "ft4-base",
                                          pass_index++,
                                          total_passes);

    for (int hint_index = 0; hint_index < round_budget; ++hint_index) {
        if (!copy_ap_hint_at(&state->ap_hints, hint_index, his_call, his_grid)) {
            continue;
        }
        push_bridge_options(state, &state->options, his_call, his_grid);
        total_bridge_count += run_bridge_pass(state,
                                              sample_count,
                                              &state->options,
                                              his_call,
                                              his_grid,
                                              "ft4-followup",
                                              pass_index++,
                                              total_passes);
    }

    WSJTX3_LOGI("find_sync ft4 sessionResults=%d totalBridgeCount=%d totalPasses=%d hintCount=%d roundBudget=%d",
                state->session_result_count,
                total_bridge_count,
                total_passes,
                hint_count,
                round_budget);
    return total_bridge_count;
}

static int run_wsjtx3_session(decoder_t *decoder,
                              wsjtx3_backend_state_t *state,
                              int sample_count) {
    if (state == NULL || sample_count <= 0) {
        return 0;
    }

    if (state->is_ft8) {
        return run_ft8_session(decoder, state, sample_count);
    }
    return run_ft4_session(decoder, state, sample_count);
}

static int finalize_wsjtx3_session(decoder_t *decoder,
                                   wsjtx3_backend_state_t *state,
                                   int total_bridge_count) {
    if (state == NULL) {
        return 0;
    }

    qsort(state->session_results,
          (size_t) state->session_result_count,
          sizeof(state->session_results[0]),
          compare_session_results);
    state->last_bridge_raw_count = total_bridge_count;
    state->last_merged_count = state->session_result_count;

    WSJTX3_LOGI("find_sync session mode=%s rawBridgeCount=%d mergedCount=%d",
                state->is_ft8 ? "FT8" : "FT4",
                total_bridge_count,
                state->session_result_count);

    publish_session_results_to_decoder(decoder, state);
    sync_bridge_options(state);
    return state->session_result_count;
}

static bool ensure_raw_capacity(wsjtx3_backend_state_t *state, int sample_count) {
    float *resized;
    int new_capacity;
    if (state == NULL) {
        return false;
    }
    if (sample_count <= state->raw_capacity) {
        return true;
    }
    new_capacity = sample_count;
    resized = (float *) realloc(state->raw_samples, (size_t) new_capacity * sizeof(float));
    if (resized == NULL) {
        return false;
    }
    memset(resized + state->raw_capacity, 0, (size_t) (new_capacity - state->raw_capacity) * sizeof(float));
    state->raw_samples = resized;
    state->raw_capacity = new_capacity;
    return true;
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
        WSJTX3_LOGE("init failed: calloc state returned null");
        return false;
    }

    state->utc_time = utcTime;
    state->sample_rate = sample_rate;
    state->expected_samples = num_samples;
    state->last_sample_count = num_samples;
    state->ldpc_iterations = fast_kLDPC_iterations;
    state->qso_frequency_hz = kWsjtDefaultQsoFrequencyHz;
    state->tx_frequency_hz = kWsjtDefaultTxFrequencyHz;
    state->is_ft8 = is_ft8;
    state->raw_capacity = (num_samples > 0) ? num_samples : (is_ft8 ? FT8_SAMPLE_RATE * 15 : FT8_SAMPLE_RATE * 8);
    state->raw_samples = (float *) calloc((size_t) state->raw_capacity, sizeof(float));
    if (state->raw_samples == NULL) {
        WSJTX3_LOGE("init failed: calloc raw_samples returned null capacity=%d", state->raw_capacity);
        free(state);
        return false;
    }

    state->bridge_handle = bridge_create_locked(is_ft8 ? 1 : 0, sample_rate, num_samples, utcTime);
    if (state->bridge_handle <= 0) {
        WSJTX3_LOGE("init failed: wsjtx3_bridge_create returned %d isFt8=%d sampleRate=%d numSamples=%d utc=%lld",
                    state->bridge_handle,
                    is_ft8 ? 1 : 0,
                    sample_rate,
                    num_samples,
                    (long long) utcTime);
        free(state->raw_samples);
        free(state);
        return false;
    }

    decoder->backend_state = state;
    reset_backend_results(decoder, state);
    sync_bridge_options(state);
    WSJTX3_LOGI("init ok: handle=%d isFt8=%d sampleRate=%d numSamples=%d",
                state->bridge_handle,
                is_ft8 ? 1 : 0,
                sample_rate,
                num_samples);
    return true;
#else
    (void) utcTime;
    (void) sample_rate;
    (void) num_samples;
    (void) is_ft8;
    WSJTX3_LOGE("init failed: FT8CN_ENABLE_WSJTX3_BACKEND is disabled at compile time");
    return false;
#endif
}

void wsjtx3_backend_free_decoder(decoder_t *decoder) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL) {
        return;
    }
#if FT8CN_ENABLE_WSJTX3_BACKEND
    if (state->bridge_handle > 0) {
        bridge_destroy_locked(state->bridge_handle);
    }
#endif
    free(state->raw_samples);
    free(state);
    decoder->backend_state = NULL;
}

void wsjtx3_backend_monitor_press(decoder_t *decoder, const float *signal, int sample_count) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL || signal == NULL) {
        return;
    }
    if (sample_count < 0) {
        sample_count = 0;
    }
    if (!ensure_raw_capacity(state, sample_count)) {
        return;
    }
    if (sample_count > 0) {
        memcpy(state->raw_samples, signal, (size_t) sample_count * sizeof(float));
    }
    if (sample_count < state->raw_capacity) {
        memset(state->raw_samples + sample_count,
               0,
               (size_t) (state->raw_capacity - sample_count) * sizeof(float));
    }
    state->last_sample_count = sample_count;
}

int wsjtx3_backend_find_sync(decoder_t *decoder) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (decoder == NULL || state == NULL) {
        return 0;
    }

#if FT8CN_ENABLE_WSJTX3_BACKEND
    int total_bridge_count;
    const int sample_count = state->last_sample_count > 0 ? state->last_sample_count : state->expected_samples;

    reset_backend_results(decoder, state);
    total_bridge_count = run_wsjtx3_session(decoder, state, sample_count);
    return finalize_wsjtx3_session(decoder, state, total_bridge_count);
#else
    (void) decoder;
    return 0;
#endif
}

ft8_message wsjtx3_backend_analyze(decoder_t *decoder, int idx) {
    ft8_message empty_message;
    wsjtx3_backend_state_t *state = get_state(decoder);

    memset(&empty_message, 0, sizeof(empty_message));
    if (state == NULL || idx < 0 || idx >= state->session_result_count) {
        return empty_message;
    }

    memcpy(state->current_a91,
           state->session_results[idx].message.a91,
           FTX_LDPC_K_BYTES);
    if (decoder != NULL) {
        memcpy(decoder->a91,
               state->session_results[idx].message.a91,
               FTX_LDPC_K_BYTES);
    }
    return state->session_results[idx];
}

void wsjtx3_backend_reset(decoder_t *decoder, long utcTime, int num_samples) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL) {
        return;
    }
    state->utc_time = utcTime;
    state->expected_samples = num_samples;
    state->last_sample_count = num_samples;
    reset_backend_results(decoder, state);
#if FT8CN_ENABLE_WSJTX3_BACKEND
    if (state->bridge_handle > 0) {
        bridge_reset_locked(state->bridge_handle, utcTime, num_samples);
        sync_bridge_options(state);
    }
#endif
}

void wsjtx3_backend_get_a91(decoder_t *decoder, uint8_t out[FTX_LDPC_K_BYTES]) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL || out == NULL) {
        return;
    }
    memcpy(out, state->current_a91, FTX_LDPC_K_BYTES);
}

int wsjtx3_backend_get_last_bridge_raw_count(decoder_t *decoder) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL) {
        return 0;
    }
    return state->last_bridge_raw_count;
}

int wsjtx3_backend_get_last_merged_count(decoder_t *decoder) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL) {
        return 0;
    }
    return state->last_merged_count;
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
    sync_bridge_options(state);
}

void wsjtx3_backend_set_ap_hints(decoder_t *decoder, const ap_hints_t *ap_hints) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL) {
        return;
    }
    if (ap_hints == NULL) {
        memset(&state->ap_hints, 0, sizeof(state->ap_hints));
    } else {
        memcpy(&state->ap_hints, ap_hints, sizeof(state->ap_hints));
    }
    sync_bridge_options(state);
}

void wsjtx3_backend_set_options(decoder_t *decoder, const wsjtx_decoder_options_t *options) {
    wsjtx3_backend_state_t *state = get_state(decoder);
    if (state == NULL) {
        return;
    }
    if (options == NULL) {
        memset(&state->options, 0, sizeof(state->options));
    } else {
        memcpy(&state->options, options, sizeof(state->options));
    }
    sync_bridge_options(state);
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
