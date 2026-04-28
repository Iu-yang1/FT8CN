#include "ft8Decoder.h"

#include "ft8/encode.h"
#include "wsjtx/wsjtx_port.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#define LOG_LEVEL LOG_INFO

static const float kDecodeDuplicateFrequencyToleranceHz = 20.0f;

static float hann_i(int i, int N) {
    float x = sinf((float) M_PI * i / N);
    return x * x;
}

static inline bool decoder_is_ft4(decoder_t *decoder) {
    return decoder->mon_cfg.protocol == PROTO_FT4;
}

static inline int decoder_min_sync_score(decoder_t *decoder) {
    return decoder_is_ft4(decoder) ? 8 : kMin_score;
}

static decoder_backend_t select_decoder_backend(void) {
    // New work lands behind the WSJT-X port backend first so we can migrate
    // the Fortran session logic incrementally without changing JNI again.
    return DECODER_BACKEND_WSJTX_PORT;
}

static inline void setMagToZero(decoder_t *decoder, int index, int max_block_size) {
    if (index > 0 && index < max_block_size) {
        decoder->mon.wf.mag[index] = 0;
    }
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
    decoder->backend = select_decoder_backend();

    if (decoder->backend == DECODER_BACKEND_WSJTX_PORT) {
        if (wsjtx_port_init_decoder(decoder, utcTime, sample_rate, num_samples, is_ft8)) {
            return decoder;
        }
        decoder->backend = DECODER_BACKEND_LEGACY;
    }

    decoder->mon_cfg = (monitor_config_t) {
            .f_min = 100,
            .f_max = 3000,
            .sample_rate = sample_rate,
            .time_osr = kTime_osr,
            .freq_osr = kFreq_osr,
            .protocol = is_ft8 ? PROTO_FT8 : PROTO_FT4
    };

    decoder->kLDPC_iterations = fast_kLDPC_iterations;
    monitor_init(&decoder->mon, &decoder->mon_cfg);

    return decoder;
}

void delete_decoder(decoder_t *decoder) {
    if (decoder == NULL) {
        return;
    }

    if (decoder->backend == DECODER_BACKEND_WSJTX_PORT) {
        wsjtx_port_free_decoder(decoder);
        free(decoder);
        return;
    }

    monitor_free(&decoder->mon);
    free(decoder);
}

void decoder_monitor_press_samples(float signal[], decoder_t *decoder, int sample_count) {
    if (decoder == NULL || signal == NULL) {
        return;
    }
    if (sample_count < 0) {
        sample_count = 0;
    }

    if (decoder->backend == DECODER_BACKEND_WSJTX_PORT) {
        wsjtx_port_monitor_press(decoder, signal, sample_count);
        return;
    }

    decoder->num_samples = sample_count;
    for (int frame_pos = 0;
         frame_pos + decoder->mon.block_size <= decoder->num_samples;
         frame_pos += decoder->mon.block_size) {
        monitor_process(&decoder->mon, signal + frame_pos);
    }

    LOG(LOG_DEBUG, "Waterfall accumulated %d symbols\n", decoder->mon.wf.num_blocks);
    LOG(LOG_INFO, "Max magnitude: %.1f dB\n", decoder->mon.max_mag);
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

    if (decoder->backend == DECODER_BACKEND_WSJTX_PORT) {
        return wsjtx_port_find_sync(decoder);
    }

    int min_score = decoder_min_sync_score(decoder);
    decoder->num_candidates = ft8_find_sync(&decoder->mon.wf,
                                            kMax_candidates,
                                            decoder->candidate_list,
                                            min_score);

    decoder->num_decoded = 0;
    for (int i = 0; i < kMax_decoded_messages; ++i) {
        decoder->decoded_hashtable[i] = NULL;
        decoder->decoded_freq_hz[i] = 0.0f;
    }
    return decoder->num_candidates;
}

ft8_message decoder_ft8_analysis(int idx, decoder_t *decoder) {
    if (decoder != NULL && decoder->backend == DECODER_BACKEND_WSJTX_PORT) {
        return wsjtx_port_analyze(decoder, idx);
    }

    ft8_message ft8Message;
    memset(&ft8Message, 0, sizeof(ft8Message));
    if (decoder == NULL) {
        return ft8Message;
    }

    ft8Message.utcTime = decoder->utcTime;
    if (idx < 0 || idx >= decoder->num_candidates) {
        return ft8Message;
    }
    ft8Message.candidate = decoder->candidate_list[idx];

    if (ft8Message.candidate.score < decoder_min_sync_score(decoder)) {
        return ft8Message;
    }

    ft8Message.freq_hz =
            (ft8Message.candidate.freq_offset +
             (float) ft8Message.candidate.freq_sub / decoder->mon.wf.freq_osr) /
            decoder->mon.symbol_period;

    ft8Message.time_sec =
            ((ft8Message.candidate.time_offset + (float) ft8Message.candidate.time_sub) *
             decoder->mon.symbol_period) / decoder->mon.wf.time_osr;

    if (!ft8_decode(&decoder->mon.wf,
                    &ft8Message.candidate,
                    &ft8Message.message,
                    decoder->kLDPC_iterations,
                    &decoder->ap_hints,
                    &ft8Message.status)) {
        return ft8Message;
    }

    ft8Message.snr = ft8Message.candidate.snr;

    int idx_hash = ft8Message.message.hash % kMax_decoded_messages;
    bool found_empty_slot = false;
    bool found_duplicate = false;
    int probe_count = 0;

    do {
        if (decoder->decoded_hashtable[idx_hash] == NULL) {
            found_empty_slot = true;
        } else if ((decoder->decoded_hashtable[idx_hash]->hash == ft8Message.message.hash) &&
                   (0 == strcmp(decoder->decoded_hashtable[idx_hash]->text, ft8Message.message.text))) {
            float existing_freq = decoder->decoded_freq_hz[idx_hash];
            if (existing_freq <= 0.0f || ft8Message.freq_hz <= 0.0f ||
                fabsf(existing_freq - ft8Message.freq_hz) <= kDecodeDuplicateFrequencyToleranceHz) {
                found_duplicate = true;
            } else {
                idx_hash = (idx_hash + 1) % kMax_decoded_messages;
            }
        } else {
            idx_hash = (idx_hash + 1) % kMax_decoded_messages;
        }
        ++probe_count;
    } while (!found_empty_slot && !found_duplicate && probe_count < kMax_decoded_messages);

    if (found_empty_slot) {
        memcpy(&decoder->decoded[idx_hash], &ft8Message.message, sizeof(ft8Message.message));
        decoder->decoded_freq_hz[idx_hash] = ft8Message.freq_hz;
        decoder->decoded_hashtable[idx_hash] = &decoder->decoded[idx_hash];
        ++decoder->num_decoded;
        ft8Message.isValid = true;
    }

    memcpy(decoder->a91, ft8Message.message.a91, FTX_LDPC_K_BYTES);
    return ft8Message;
}

void decoder_ft8_reset(decoder_t *decoder, long utcTime, int num_samples) {
    if (decoder == NULL) {
        return;
    }

    decoder->utcTime = utcTime;
    decoder->num_samples = num_samples;

    if (decoder->backend == DECODER_BACKEND_WSJTX_PORT) {
        wsjtx_port_reset(decoder, utcTime, num_samples);
        return;
    }

    decoder->mon.wf.num_blocks = 0;
    decoder->mon.max_mag = -120.0f;
}

void decoder_get_a91(decoder_t *decoder, uint8_t out[FTX_LDPC_K_BYTES]) {
    if (decoder == NULL || out == NULL) {
        return;
    }

    if (decoder->backend == DECODER_BACKEND_WSJTX_PORT) {
        wsjtx_port_get_a91(decoder, out);
        return;
    }
    memcpy(out, decoder->a91, FTX_LDPC_K_BYTES);
}

void decoder_set_ldpc_iterations(decoder_t *decoder, bool is_deep) {
    if (decoder == NULL) {
        return;
    }

    int iterations = is_deep ? deep_kLDPC_iterations : fast_kLDPC_iterations;
    if (decoder->backend == DECODER_BACKEND_WSJTX_PORT) {
        wsjtx_port_set_ldpc_iterations(decoder, iterations);
        return;
    }
    decoder->kLDPC_iterations = iterations;
}

void decoder_set_ap_hints(decoder_t *decoder, const ap_hints_t *ap_hints) {
    if (decoder == NULL) {
        return;
    }

    if (decoder->backend == DECODER_BACKEND_WSJTX_PORT) {
        wsjtx_port_set_ap_hints(decoder, ap_hints);
        return;
    }

    if (ap_hints == NULL) {
        memset(&decoder->ap_hints, 0, sizeof(decoder->ap_hints));
        return;
    }
    memcpy(&decoder->ap_hints, ap_hints, sizeof(decoder->ap_hints));
}

void decoder_set_wsjtx_options(decoder_t *decoder, const wsjtx_decoder_options_t *options) {
    if (decoder == NULL) {
        return;
    }

    if (decoder->backend == DECODER_BACKEND_WSJTX_PORT) {
        wsjtx_port_set_options(decoder, options);
    }
}

bool decoder_owns_session_flow(decoder_t *decoder) {
    if (decoder == NULL) {
        return false;
    }

    if (decoder->backend == DECODER_BACKEND_WSJTX_PORT) {
        return wsjtx_port_owns_session_flow(decoder);
    }
    return false;
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

    if (decoder->backend == DECODER_BACKEND_WSJTX_PORT) {
        wsjtx_port_subtract_signal(decoder, payload, sample_rate, frequency, time_sec, mode);
        return;
    }

    int nn;
    float symbol_period;
    float slot_time;

    if (mode == 1) {
        nn = FT4_NN;
        symbol_period = FT4_SYMBOL_PERIOD;
        slot_time = FT4_SLOT_TIME;
    } else {
        nn = FT8_NN;
        symbol_period = FT8_SYMBOL_PERIOD;
        slot_time = FT8_SLOT_TIME;
    }

    uint8_t *tones = (uint8_t *) malloc(nn);
    if (tones == NULL) {
        return;
    }
    memset(tones, 0, nn);

    if (mode == 1) {
        ft4_encode(payload, tones);
    } else {
        ft8_encode(payload, tones);
    }

    int max_block_size = (int) (slot_time / symbol_period) * kTime_osr * kFreq_osr
                         * (int) (sample_rate * symbol_period / 2);
    int block_size = (int) (symbol_period * decoder->mon_cfg.sample_rate);
    int freq_offset = (int) (frequency * symbol_period) * kFreq_osr;
    int time_offset = (int) ((time_sec / symbol_period) * kTime_osr + 0.5f);

    for (int i = 0; i < nn; ++i) {
        int index = (i + time_offset) * 2;
        int index1 = index * block_size + freq_offset + tones[i];
        int index2 = (index + 1) * block_size + freq_offset + tones[i];
        int index3 = index1 + 1;
        int index4 = index2 + 1;
        int index5 = index1 - 1;
        int index6 = index2 - 1;
        int index7 = index1 - 2;
        int index8 = index2 - 2;
        int index9 = index1 + 2;
        int index10 = index2 + 2;

        setMagToZero(decoder, index1, max_block_size);
        setMagToZero(decoder, index2, max_block_size);
        setMagToZero(decoder, index3, max_block_size);
        setMagToZero(decoder, index4, max_block_size);
        setMagToZero(decoder, index5, max_block_size);
        setMagToZero(decoder, index6, max_block_size);
        setMagToZero(decoder, index7, max_block_size);
        setMagToZero(decoder, index8, max_block_size);
        setMagToZero(decoder, index9, max_block_size);
        setMagToZero(decoder, index10, max_block_size);
    }

    free(tones);
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
