#include "ftx_decoder.h"

#include "../ft8Decoder.h"

#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct ftx_decoder {
    decoder_t *impl;
    ftx_mode_t mode;
    int sample_rate;
    int num_samples;
    long long utc_time;
    ftx_decoder_options_t options;
    ap_hints_t ap_hints;
    ftx_decode_result_t results[FTX_MAX_DECODE_RESULTS];
    int result_count;
};

static void copy_text(char *dest, size_t dest_size, const char *src) {
    if (dest == NULL || dest_size == 0) {
        return;
    }

    dest[0] = '\0';
    if (src == NULL) {
        return;
    }

    snprintf(dest, dest_size, "%s", src);
}

static int clamp_count(int value, int min_value, int max_value) {
    if (value < min_value) {
        return min_value;
    }
    if (value > max_value) {
        return max_value;
    }
    return value;
}

static void load_default_options(ftx_decoder_options_t *options) {
    if (options == NULL) {
        return;
    }

    memset(options, 0, sizeof(*options));
    options->decode_pass_count = 3;
    options->multi_decode_round_count = 3;
    options->qso_freq_sensitivity = 1;
    options->decode_sensitivity = 1;
    options->enable_early_decode = 1;
    options->enable_wideband_dx_search = 1;
    options->ldpc_iterations = fast_kLDPC_iterations;
    options->deep_decode_enabled = 0;
}

static void apply_decoder_options(ftx_decoder_t *decoder) {
    wsjtx_decoder_options_t wsjtx_options;
    int iterations;

    if (decoder == NULL || decoder->impl == NULL) {
        return;
    }

    memset(&wsjtx_options, 0, sizeof(wsjtx_options));
    wsjtx_options.decode_pass_count = clamp_count(decoder->options.decode_pass_count, 1, 3);
    wsjtx_options.multi_decode_round_count = clamp_count(decoder->options.multi_decode_round_count, 1, 3);
    wsjtx_options.qso_freq_sensitivity = clamp_count(decoder->options.qso_freq_sensitivity, 0, 2);
    wsjtx_options.decode_sensitivity = clamp_count(decoder->options.decode_sensitivity, 0, 2);
    wsjtx_options.enable_early_decode = decoder->options.enable_early_decode != 0;
    wsjtx_options.enable_wideband_dx_search = decoder->options.enable_wideband_dx_search != 0;
    decoder_set_wsjtx_options(decoder->impl, &wsjtx_options);

    iterations = decoder->options.ldpc_iterations;
    if (iterations <= 0) {
        iterations = decoder->options.deep_decode_enabled ? deep_kLDPC_iterations : fast_kLDPC_iterations;
    }
    decoder_set_ldpc_iterations_value(decoder->impl, iterations);
}

static void store_decode_result(ftx_decoder_t *decoder, const ft8_message *message) {
    ftx_decode_result_t *result;

    if (decoder == NULL || message == NULL) {
        return;
    }
    if (decoder->result_count >= FTX_MAX_DECODE_RESULTS) {
        return;
    }

    result = &decoder->results[decoder->result_count];
    memset(result, 0, sizeof(*result));
    result->utc_time = message->utcTime;
    result->is_valid = message->isValid ? 1 : 0;
    result->snr = message->snr;
    result->score = message->candidate.score;
    result->time_sec = message->time_sec;
    result->freq_hz = message->freq_hz;
    result->i3 = message->message.i3;
    result->n3 = message->message.n3;
    result->report = message->message.report;
    result->r_flag = message->message.r_flag;
    result->rtty_tu = message->message.rtty_tu;
    result->eu_serial = message->message.eu_serial;
    result->call_to_hash10 = message->message.call_to_hash.hash10;
    result->call_to_hash12 = message->message.call_to_hash.hash12;
    result->call_to_hash22 = message->message.call_to_hash.hash22;
    result->call_de_hash10 = message->message.call_de_hash.hash10;
    result->call_de_hash12 = message->message.call_de_hash.hash12;
    result->call_de_hash22 = message->message.call_de_hash.hash22;
    result->message_hash = message->message.hash;

    copy_text(result->text, sizeof(result->text), message->message.text);
    copy_text(result->call_to, sizeof(result->call_to), message->message.call_to);
    copy_text(result->call_de, sizeof(result->call_de), message->message.call_de);
    copy_text(result->dx_call_to2, sizeof(result->dx_call_to2), message->message.dx_call_to2);
    copy_text(result->extra, sizeof(result->extra), message->message.extra);
    copy_text(result->grid, sizeof(result->grid), message->message.maidenGrid);
    copy_text(result->rtty_state, sizeof(result->rtty_state), message->message.rtty_state);
    copy_text(result->arrl_rac, sizeof(result->arrl_rac), message->message.arrl_rac);
    copy_text(result->arrl_class, sizeof(result->arrl_class), message->message.arrl_class);

    decoder->result_count++;
}

ftx_decoder_t *ftx_decoder_create(ftx_mode_t mode,
                                  int sample_rate,
                                  int num_samples,
                                  long long utc_time) {
    ftx_decoder_t *decoder;
    int is_ft8;

    if (sample_rate <= 0 || num_samples <= 0) {
        return NULL;
    }
    if (mode != FTX_MODE_FT8 && mode != FTX_MODE_FT4) {
        return NULL;
    }

    decoder = (ftx_decoder_t *) calloc(1, sizeof(ftx_decoder_t));
    if (decoder == NULL) {
        return NULL;
    }

    decoder->mode = mode;
    decoder->sample_rate = sample_rate;
    decoder->num_samples = num_samples;
    decoder->utc_time = utc_time;
    load_default_options(&decoder->options);

    is_ft8 = (mode == FTX_MODE_FT8);
    decoder->impl = (decoder_t *) init_decoder((int64_t) utc_time, sample_rate, num_samples, is_ft8);
    if (decoder->impl == NULL) {
        free(decoder);
        return NULL;
    }

    apply_decoder_options(decoder);
    decoder_set_ap_hints(decoder->impl, &decoder->ap_hints);
    return decoder;
}

void ftx_decoder_destroy(ftx_decoder_t *decoder) {
    if (decoder == NULL) {
        return;
    }

    delete_decoder(decoder->impl);
    free(decoder);
}

int ftx_decoder_set_options(ftx_decoder_t *decoder, const ftx_decoder_options_t *options) {
    if (decoder == NULL || options == NULL) {
        return -1;
    }

    decoder->options = *options;
    apply_decoder_options(decoder);
    return 0;
}

int ftx_decoder_set_ap_hints(ftx_decoder_t *decoder,
                             const char *my_call,
                             const char **hint_calls,
                             const char **hint_grids,
                             int hint_count) {
    int index;

    if (decoder == NULL) {
        return -1;
    }

    memset(&decoder->ap_hints, 0, sizeof(decoder->ap_hints));
    copy_text(decoder->ap_hints.my_call, sizeof(decoder->ap_hints.my_call), my_call);

    if (hint_count < 0) {
        hint_count = 0;
    }
    if (hint_count > FTX_MAX_HINT_CALLS) {
        hint_count = FTX_MAX_HINT_CALLS;
    }

    for (index = 0; index < hint_count; ++index) {
        const char *call = (hint_calls == NULL) ? NULL : hint_calls[index];
        const char *grid = (hint_grids == NULL) ? NULL : hint_grids[index];

        copy_text(decoder->ap_hints.hint_calls[index],
                  sizeof(decoder->ap_hints.hint_calls[index]),
                  call);
        copy_text(decoder->ap_hints.hint_grids[index],
                  sizeof(decoder->ap_hints.hint_grids[index]),
                  grid);
        if (decoder->ap_hints.hint_calls[index][0] != '\0') {
            decoder->ap_hints.hint_call_count = index + 1;
        }
    }

    decoder_set_ap_hints(decoder->impl, &decoder->ap_hints);
    return 0;
}

int ftx_decoder_process_float(ftx_decoder_t *decoder, const float *samples, int sample_count) {
    if (decoder == NULL) {
        return -1;
    }
    return ftx_decoder_process_float_slot(decoder, samples, sample_count, decoder->utc_time);
}

int ftx_decoder_process_float_slot(ftx_decoder_t *decoder,
                                   const float *samples,
                                   int sample_count,
                                   long long utc_time) {
    int candidate_count;
    int index;

    if (decoder == NULL || samples == NULL || sample_count <= 0) {
        return -1;
    }

    decoder->result_count = 0;
    decoder->utc_time = utc_time;
    decoder->num_samples = sample_count;

    decoder_ft8_reset(decoder->impl, (long) utc_time, sample_count);
    apply_decoder_options(decoder);
    decoder_set_ap_hints(decoder->impl, &decoder->ap_hints);
    decoder_monitor_press_samples((float *) samples, decoder->impl, sample_count);

    candidate_count = decoder_ft8_find_sync(decoder->impl);
    if (candidate_count < 0) {
        return -1;
    }

    for (index = 0; index < candidate_count; ++index) {
        ft8_message message = decoder_ft8_analysis(index, decoder->impl);
        if (!message.isValid) {
            continue;
        }
        store_decode_result(decoder, &message);
    }

    return decoder->result_count;
}

int ftx_decoder_get_result_count(const ftx_decoder_t *decoder) {
    if (decoder == NULL) {
        return -1;
    }
    return decoder->result_count;
}

int ftx_decoder_get_last_bridge_raw_count(const ftx_decoder_t *decoder) {
    if (decoder == NULL || decoder->impl == NULL) {
        return -1;
    }
    return decoder_get_last_bridge_raw_count(decoder->impl);
}

int ftx_decoder_get_last_merged_count(const ftx_decoder_t *decoder) {
    if (decoder == NULL || decoder->impl == NULL) {
        return -1;
    }
    return decoder_get_last_merged_count(decoder->impl);
}

int ftx_decoder_get_result(const ftx_decoder_t *decoder, int index, ftx_decode_result_t *out) {
    if (decoder == NULL || out == NULL) {
        return -1;
    }
    if (index < 0 || index >= decoder->result_count) {
        return -1;
    }

    *out = decoder->results[index];
    return 0;
}

