#include "ft8Decoder.h"

#include "wsjtx3/wsjtx3_backend.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#define LOG_LEVEL LOG_INFO

static float hann_i(int i, int N) {
    float x = sinf((float) M_PI * i / N);
    return x * x;
}

void signalToFFT(decoder_t *decoder, float signal[], int sample_rate) {
    (void) decoder;
    (void) signal;

    int nfft = kFreq_osr * (int) (sample_rate * FT8_SYMBOL_PERIOD);
    float *window = (float *) malloc(nfft * sizeof(window[0]));
    if (window == NULL) {
        LOG(LOG_ERROR, "Failed to allocate memory for window\n");
        return;
    }

    for (int i = 0; i < nfft; ++i) {
        window[i] = hann_i(i, nfft);
    }

    size_t fft_work_size;
    kiss_fftr_alloc(nfft, 0, 0, &fft_work_size);

    void *fft_work = malloc(fft_work_size);
    if (fft_work == NULL) {
        LOG(LOG_ERROR, "Failed to allocate memory for fft_work\n");
        free(window);
        return;
    }

    (void) kiss_fftr_alloc(nfft, 0, fft_work, &fft_work_size);
    free(fft_work);
    free(window);
}

void *init_decoder(int64_t utcTime, int sample_rate, int num_samples, bool is_ft8) {
    decoder_t *decoder = (decoder_t *) malloc(sizeof(decoder_t));
    if (decoder == NULL) {
        return NULL;
    }

    memset(decoder, 0, sizeof(decoder_t));
    decoder->utcTime = utcTime;
    decoder->num_samples = num_samples;
    decoder->backend = DECODER_BACKEND_WSJTX3_OFFICIAL;
    decoder->kLDPC_iterations = fast_kLDPC_iterations;
    decoder->mon_cfg = (monitor_config_t) {
            .f_min = 0,
            .f_max = 3000,
            .sample_rate = sample_rate,
            .time_osr = kTime_osr,
            .freq_osr = kFreq_osr,
            .protocol = is_ft8 ? PROTO_FT8 : PROTO_FT4
    };

    if (!wsjtx3_backend_init_decoder(decoder, utcTime, sample_rate, num_samples, is_ft8)) {
        free(decoder);
        return NULL;
    }

    return decoder;
}

void delete_decoder(decoder_t *decoder) {
    if (decoder == NULL) {
        return;
    }

    wsjtx3_backend_free_decoder(decoder);
    free(decoder);
}

void decoder_monitor_press_samples(float signal[], decoder_t *decoder, int sample_count) {
    if (decoder == NULL || signal == NULL) {
        return;
    }
    if (sample_count < 0) {
        sample_count = 0;
    }

    decoder->num_samples = sample_count;
    wsjtx3_backend_monitor_press(decoder, signal, sample_count);
}

void decoder_monitor_press(float signal[], decoder_t *decoder) {
    if (decoder == NULL) {
        return;
    }
    decoder_monitor_press_samples(signal, decoder, decoder->num_samples);
}

int decoder_ft8_find_sync(decoder_t *decoder) {
    if (decoder == NULL) {
        return 0;
    }
    return wsjtx3_backend_find_sync(decoder);
}

ft8_message decoder_ft8_analysis(int idx, decoder_t *decoder) {
    if (decoder == NULL) {
        ft8_message empty_message;
        memset(&empty_message, 0, sizeof(empty_message));
        return empty_message;
    }
    return wsjtx3_backend_analyze(decoder, idx);
}

void decoder_ft8_reset(decoder_t *decoder, long utcTime, int num_samples) {
    if (decoder == NULL) {
        return;
    }

    decoder->utcTime = utcTime;
    decoder->num_samples = num_samples;
    wsjtx3_backend_reset(decoder, utcTime, num_samples);
}

void decoder_get_a91(decoder_t *decoder, uint8_t out[FTX_LDPC_K_BYTES]) {
    if (decoder == NULL || out == NULL) {
        return;
    }
    wsjtx3_backend_get_a91(decoder, out);
}

int decoder_get_last_bridge_raw_count(decoder_t *decoder) {
    if (decoder == NULL) {
        return 0;
    }
    return wsjtx3_backend_get_last_bridge_raw_count(decoder);
}

int decoder_get_last_merged_count(decoder_t *decoder) {
    if (decoder == NULL) {
        return 0;
    }
    return wsjtx3_backend_get_last_merged_count(decoder);
}

void decoder_set_ldpc_iterations(decoder_t *decoder, bool is_deep) {
    if (decoder == NULL) {
        return;
    }

    int iterations = is_deep ? deep_kLDPC_iterations : fast_kLDPC_iterations;
    decoder_set_ldpc_iterations_value(decoder, iterations);
}

void decoder_set_ldpc_iterations_value(decoder_t *decoder, int iterations) {
    if (decoder == NULL) {
        return;
    }

    if (iterations < 1) {
        iterations = 1;
    }

    decoder->kLDPC_iterations = iterations;
    wsjtx3_backend_set_ldpc_iterations(decoder, iterations);
}

void decoder_set_ap_hints(decoder_t *decoder, const ap_hints_t *ap_hints) {
    if (decoder == NULL) {
        return;
    }

    if (ap_hints == NULL) {
        memset(&decoder->ap_hints, 0, sizeof(decoder->ap_hints));
    } else {
        memcpy(&decoder->ap_hints, ap_hints, sizeof(decoder->ap_hints));
    }
    wsjtx3_backend_set_ap_hints(decoder, ap_hints);
}

void decoder_set_wsjtx_options(decoder_t *decoder, const wsjtx_decoder_options_t *options) {
    if (decoder == NULL) {
        return;
    }
    wsjtx3_backend_set_options(decoder, options);
}

bool decoder_owns_session_flow(decoder_t *decoder) {
    if (decoder == NULL) {
        return false;
    }
    return wsjtx3_backend_owns_session_flow(decoder);
}

void decoder_subtract_signal(decoder_t *decoder,
                             const uint8_t *payload,
                             int sample_rate,
                             float frequency,
                             float time_sec,
                             int mode) {
    if (decoder == NULL || payload == NULL) {
        return;
    }
    wsjtx3_backend_subtract_signal(decoder, payload, sample_rate, frequency, time_sec, mode);
}

void recode(int a174[], int a79[]) {
    int i174 = 0;
    for (int i79 = 0; i79 < 79; i79++) {
        if (i79 < 7) {
            a79[i79] = kFT8CostasPattern[i79];
        } else if (i79 >= 36 && i79 < 43) {
            a79[i79] = kFT8CostasPattern[i79 - 36];
        } else if (i79 >= 72) {
            a79[i79] = kFT8CostasPattern[i79 - 72];
        } else {
            int sym = (a174[i174 + 0] << 2) | (a174[i174 + 1] << 1) | (a174[i174 + 2] << 0);
            static const int map[] = {0, 1, 3, 2, 5, 6, 4, 7};
            i174 += 3;
            a79[i79] = map[sym];
        }
    }
}
