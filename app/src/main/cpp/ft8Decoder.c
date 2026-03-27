//
// Created by jmsmf on 2022/4/24.
//

#include "ft8Decoder.h"
#include <string.h>

#define LOG_LEVEL LOG_INFO

// Hanning窗（汉宁窗）适用于95%的情况。
static float hann_i(int i, int N) {
    float x = sinf((float) M_PI * i / N);
    return x * x;
}

static inline bool decoder_is_ft4(decoder_t *decoder) {
    return decoder->mon_cfg.protocol == PROTO_FT4;
}

static inline int decoder_min_sync_score(decoder_t *decoder) {
    // FT4 扫描窗更宽、候选更多，阈值略放宽以减少弱信号漏检。
    return decoder_is_ft4(decoder) ? 8 : kMin_score;
}

/**
 * 把信号FFT,在解码decoder中减去信号
 * 改进点：移除未使用的 last_frame 分配，减少内存浪费
 */
void signalToFFT(decoder_t *decoder, float signal[], int sample_rate) {
    int nfft = kFreq_osr * (int) (sample_rate * FT8_SYMBOL_PERIOD);
    float fft_norm = 2.0f / nfft;

    // 分配并初始化汉宁窗
    float *window = (float *) malloc(nfft * sizeof(window[0]));
    if (window == NULL) {
        LOG(LOG_ERROR, "Failed to allocate memory for window\n");
        return;
    }

    for (int i = 0; i < nfft; ++i) {
        window[i] = hann_i(i, nfft);
    }

    // 移除未使用的 last_frame 分配
    // float *last_frame = (float *) malloc(nfft * sizeof(last_frame[0]));

    size_t fft_work_size;
    kiss_fftr_alloc(nfft, 0, 0, &fft_work_size);

    void *fft_work = malloc(fft_work_size);
    if (fft_work == NULL) {
        LOG(LOG_ERROR, "Failed to allocate memory for fft_work\n");
        free(window);
        return;
    }

    kiss_fftr_cfg fft_cfg = kiss_fftr_alloc(nfft, 0, fft_work, &fft_work_size);

    // 清理资源
    free(fft_work);
    free(window);
    // free(last_frame); //
}

void *init_decoder(int64_t utcTime, int sample_rate, int num_samples, bool is_ft8) {
    decoder_t *decoder;
    decoder = malloc(sizeof(decoder_t));
    if (decoder == NULL) {
        return NULL;
    }
    memset(decoder, 0, sizeof(decoder_t));
    decoder->utcTime = utcTime;
    decoder->num_samples = num_samples;
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
    monitor_free(&decoder->mon);
    free(decoder);
}

void decoder_monitor_press(float signal[], decoder_t *decoder) {
    for (int frame_pos = 0;
         frame_pos + decoder->mon.block_size <= decoder->num_samples;
         frame_pos += decoder->mon.block_size) {
        monitor_process(&decoder->mon, signal + frame_pos);
    }

    LOG(LOG_DEBUG, "Waterfall accumulated %d symbols\n", decoder->mon.wf.num_blocks);
    LOG(LOG_INFO, "Max magnitude: %.1f dB\n", decoder->mon.max_mag);
}

int decoder_ft8_find_sync(decoder_t *decoder) {
    int min_score = decoder_min_sync_score(decoder);
    decoder->num_candidates = ft8_find_sync(&decoder->mon.wf, kMax_candidates,
                                            decoder->candidate_list, min_score);
    LOG(LOG_DEBUG, "ft8_find_sync finished. %d candidates\n", decoder->num_candidates);

    decoder->num_decoded = 0;
    for (int i = 0; i < kMax_decoded_messages; ++i) {
        decoder->decoded_hashtable[i] = NULL;
    }
    return decoder->num_candidates;
}

ft8_message decoder_ft8_analysis(int idx, decoder_t *decoder) {
    ft8_message ft8Message;
    ft8Message.isValid = false;
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
            ((ft8Message.candidate.time_offset + (float) ft8Message.candidate.time_sub)
             * decoder->mon.symbol_period) / decoder->mon.wf.time_osr;

    if (!ft8_decode(&decoder->mon.wf,
                    &ft8Message.candidate,
                    &ft8Message.message,
                    decoder->kLDPC_iterations,
                    &decoder->ap_hints,
                    &ft8Message.status)) {
        if (ft8Message.status.ldpc_errors > 0) {
            LOG(LOG_DEBUG, "LDPC decode: %d errors\n", ft8Message.status.ldpc_errors);
        } else if (ft8Message.status.crc_calculated != ft8Message.status.crc_extracted) {
            LOG(LOG_DEBUG, "CRC mismatch!\n");
        } else if (ft8Message.status.unpack_status != 0) {
            LOG(LOG_DEBUG, "Error while unpacking!\n");
        }
        return ft8Message;
    }

    // 这里不再做 FT4 的二次显示补偿，候选 SNR 已在 decode.c 中按协议分别计算
    ft8Message.snr = ft8Message.candidate.snr;

    LOG(LOG_DEBUG, "Checking hash table for %4.1fs / %4.1fHz [%d]...\n",
        ft8Message.time_sec,
        ft8Message.freq_hz,
        ft8Message.candidate.score);

    int idx_hash = ft8Message.message.hash % kMax_decoded_messages;

    bool found_empty_slot = false;
    bool found_duplicate = false;
    int probe_count = 0;

    do {
        if (decoder->decoded_hashtable[idx_hash] == NULL) {
            LOG(LOG_DEBUG, "Found an empty slot\n");
            found_empty_slot = true;
        } else if ((decoder->decoded_hashtable[idx_hash]->hash == ft8Message.message.hash) &&
                   (0 == strcmp(decoder->decoded_hashtable[idx_hash]->text, ft8Message.message.text))) {
            LOG(LOG_DEBUG, "Found a duplicate [%s]\n", ft8Message.message.text);
            found_duplicate = true;
        } else {
            LOG(LOG_DEBUG, "Hash table clash!\n");
            idx_hash = (idx_hash + 1) % kMax_decoded_messages;
        }
        ++probe_count;
    } while (!found_empty_slot && !found_duplicate && probe_count < kMax_decoded_messages);

    if (!found_empty_slot && !found_duplicate) {
        // 哈希表已满时避免死循环，直接放弃本条去重写入。
        LOG(LOG_DEBUG, "Decoded hash table full, drop this message\n");
    }

    if (found_empty_slot) {
        memcpy(&decoder->decoded[idx_hash], &ft8Message.message, sizeof(ft8Message.message));
        decoder->decoded_hashtable[idx_hash] = &decoder->decoded[idx_hash];
        ++decoder->num_decoded;

        ft8Message.isValid = true;

        LOG_PRINTF("%s %3d %+4.2f %4.0f ~  %s report:%d grid:%s,toHash:%x,fromHash:%x",
                   decoder_is_ft4(decoder) ? "FT4" : "FT8",
                   ft8Message.snr,
                   ft8Message.time_sec,
                   ft8Message.freq_hz,
                   ft8Message.message.text,
                   ft8Message.message.report,
                   ft8Message.message.maidenGrid,
                   ft8Message.message.call_to_hash.hash12,
                   ft8Message.message.call_de_hash.hash12);
    }

    memcpy(decoder->a91, ft8Message.message.a91, FTX_LDPC_K_BYTES);
    return ft8Message;
}

void decoder_ft8_reset(decoder_t *decoder, long utcTime, int num_samples) {
    LOG(LOG_DEBUG, "Monitor is resetting...");
    decoder->mon.wf.num_blocks = 0;
    decoder->mon.max_mag = -120.0f;
    decoder->utcTime = utcTime;
    decoder->num_samples = num_samples;
}

/**
 * 对174码，重新编生成79码
 * @param a174 174个int
 * @param a79 79个int
 */
void recode(int a174[], int a79[]) {
    int i174 = 0;
    for (int i79 = 0; i79 < 79; i79++) {
        if (i79 < 7) {
            a79[i79] = kFT8CostasPattern[i79];
        } else if (i79 >= 36 && i79 < 36 + 7) {
            a79[i79] = kFT8CostasPattern[i79 - 36];
        } else if (i79 >= 72) {
            a79[i79] = kFT8CostasPattern[i79 - 72];
        } else {
            int sym = (a174[i174 + 0] << 2) | (a174[i174 + 1] << 1) | (a174[i174 + 2] << 0);
            i174 += 3;
            int map[] = {0, 1, 3, 2, 5, 6, 4, 7};
            sym = map[sym];
            a79[i79] = sym;
        }
    }
}
