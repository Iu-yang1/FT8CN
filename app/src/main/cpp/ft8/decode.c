#include "decode.h"
#include "constants.h"
#include "crc.h"
#include "ldpc.h"
#include "unpack.h"

#include <stdbool.h>
#include <math.h>
#include "../common/debug.h"
#include "hash22.h"

/// Compute log likelihood log(p(1) / p(0)) of 174 message bits for later use in soft-decision LDPC decoding
/// @param[in] wf Waterfall data collected during message slot
/// @param[in] cand Candidate to extract the message from
/// @param[in] code_map Symbol encoding map
/// @param[out] log174 Output of decoded log likelihoods for each of the 174 message bits
static void ft4_extract_likelihood(const waterfall_t *wf, const candidate_t *cand, float *log174);
static void ft8_extract_likelihood(const waterfall_t *wf, candidate_t *cand, float *log174);

/// Packs a string of bits each represented as a zero/non-zero byte in bit_array[],
/// as a string of packed bits starting from the MSB of the first byte of packed[]
/// @param[in] plain Array of bits (0 and nonzero values) with num_bits entires
/// @param[in] num_bits Number of bits (entries) passed in bit_array
/// @param[out] packed Byte-packed bits representing the data in bit_array
static void pack_bits(const uint8_t bit_array[], int num_bits, uint8_t packed[]);

static float max2(float a, float b);
static float max4(float a, float b, float c, float d);
static void heapify_down(candidate_t heap[], int heap_size);
static void heapify_up(candidate_t heap[], int heap_size);
static void ftx_normalize_logl(float *log174);
static void ft4_extract_symbol(const uint8_t *wf, float *logl);
static void ft8_extract_symbol(const uint8_t *wf, float *logl);
static void ft8_decode_multi_symbols(const uint8_t *wf, int num_bins, int n_syms, int bit_idx, float *log174);

/**
 * SNR 限幅，避免异常值
 */
static inline int clamp_snr_value(int snr) {
    if (snr < -30) return -30;
    if (snr > 20) return 20;
    return snr;
}

/**
 * 取候选在瀑布图中的起始索引
 */
static int get_index(const waterfall_t *wf, const candidate_t *candidate) {
    int offset = candidate->time_offset;
    offset = (offset * wf->time_osr) + candidate->time_sub;
    offset = (offset * wf->freq_osr) + candidate->freq_sub;
    offset = (offset * wf->num_bins) + candidate->freq_offset;
    return offset;
}

/**
 * FT8 同步评分
 *
 * 改动点：
 * 1. 保留原来的频率/时间邻居差分
 * 2. 增加“目标 bin 与其余 bin 平均差值”的弱信号增益项
 * 3. 对后段 sync block 的边界判断统一修正
 *
 * 这样会比原来稍微灵敏一些，但不至于把噪声候选放大得太夸张。
 */
static int ft8_sync_score(const waterfall_t *wf, candidate_t *candidate) {
    int score = 0;
    int num_average = 0;

    const uint8_t *mag_cand = wf->mag + get_index(wf, candidate);

    for (int m = 0; m < FT8_NUM_SYNC; ++m) {
        for (int k = 0; k < FT8_LENGTH_SYNC; ++k) {
            int block = (FT8_SYNC_OFFSET * m) + k;
            int block_abs = candidate->time_offset + block;

            if (block_abs < 0)
                continue;
            if (block_abs >= wf->num_blocks)
                break;

            const uint8_t *p8 = mag_cand + (block * wf->block_stride);
            int sm = kFT8CostasPattern[k];

            // 频率邻居差分
            if (sm > 0) {
                score += (int)p8[sm] - (int)p8[sm - 1];
                ++num_average;
            }
            if (sm < 7) {
                score += (int)p8[sm] - (int)p8[sm + 1];
                ++num_average;
            }

            // 时间邻居差分
            if ((k > 0) && (block_abs > 0)) {
                score += (int)p8[sm] - (int)p8[sm - wf->block_stride];
                ++num_average;
            }
            if (((k + 1) < FT8_LENGTH_SYNC) && ((block_abs + 1) < wf->num_blocks)) {
                score += (int)p8[sm] - (int)p8[sm + wf->block_stride];
                ++num_average;
            }

            // 增加一个“目标 bin 相对其它 7 个 bin 的平均优势”
            // 这个项对弱信号更友好一些
            {
                int others = 0;
                for (int n = 0; n < 8; ++n) {
                    if (n == sm) continue;
                    others += p8[n];
                }
                score += ((int)p8[sm] * 7 - others) / 4;
                ++num_average;
            }
        }
    }

    if (num_average > 0) {
        score /= num_average;
    }

    return score;
}

/**
 * FT4 同步评分
 *
 * 改动点：
 * 1. 保留原来的频率/时间邻居差分
 * 2. 增加“目标 bin 与其余 3 个 bin 平均差值”的增强项
 * 3. FT4 本来同步符号更短、更密，适当增强这一项有助于弱信号候选进入后续 LDPC
 */
static int ft4_sync_score(const waterfall_t *wf, const candidate_t *candidate) {
    int score = 0;
    int num_average = 0;

    const uint8_t *mag_cand = wf->mag + get_index(wf, candidate);

    // sync symbols: 1-4, 34-37, 67-70, 100-103
    for (int m = 0; m < FT4_NUM_SYNC; ++m) {
        for (int k = 0; k < FT4_LENGTH_SYNC; ++k) {
            int block = 1 + (FT4_SYNC_OFFSET * m) + k;
            int block_abs = candidate->time_offset + block;

            if (block_abs < 0)
                continue;
            if (block_abs >= wf->num_blocks)
                break;

            const uint8_t *p4 = mag_cand + (block * wf->block_stride);
            int sm = kFT4CostasPattern[m][k];

            // 频率邻居差分
            if (sm > 0) {
                score += (int)p4[sm] - (int)p4[sm - 1];
                ++num_average;
            }
            if (sm < 3) {
                score += (int)p4[sm] - (int)p4[sm + 1];
                ++num_average;
            }

            // 时间邻居差分
            if ((k > 0) && (block_abs > 0)) {
                score += (int)p4[sm] - (int)p4[sm - wf->block_stride];
                ++num_average;
            }
            if (((k + 1) < FT4_LENGTH_SYNC) && ((block_abs + 1) < wf->num_blocks)) {
                score += (int)p4[sm] - (int)p4[sm + wf->block_stride];
                ++num_average;
            }

            // 目标 bin 相对其余 3 个 bin 的平均优势
            {
                int others = 0;
                for (int n = 0; n < 4; ++n) {
                    if (n == sm) continue;
                    others += p4[n];
                }
                score += ((int)p4[sm] * 3 - others) / 2;
                ++num_average;
            }
        }
    }

    if (num_average > 0) {
        score /= num_average;
    }

    return score;
}

// 检测信号候选
int ft8_find_sync(const waterfall_t *wf, int num_candidates, candidate_t heap[], int min_score) {
    int heap_size = 0;
    candidate_t candidate;

    // 注意：
    // FT8 / FT4 这里都共用同一个扫描框架
    // 当前先不改扫描范围，只优化评分函数，避免引入新的时序副作用
    for (candidate.time_sub = 0; candidate.time_sub < wf->time_osr; ++candidate.time_sub) {
        for (candidate.freq_sub = 0; candidate.freq_sub < wf->freq_osr; ++candidate.freq_sub) {
            for (candidate.time_offset = -12; candidate.time_offset < 24; ++candidate.time_offset) {
                for (candidate.freq_offset = 0;
                     (candidate.freq_offset + 7) < wf->num_bins; ++candidate.freq_offset) {

                    if (wf->protocol == PROTO_FT4) {
                        candidate.score = ft4_sync_score(wf, &candidate);
                    } else {
                        candidate.score = ft8_sync_score(wf, &candidate);
                    }

                    if (candidate.score < min_score)
                        continue;

                    if (heap_size == num_candidates && candidate.score > heap[0].score) {
                        heap[0] = heap[heap_size - 1];
                        --heap_size;
                        heapify_down(heap, heap_size);
                    }

                    if (heap_size < num_candidates) {
                        heap[heap_size] = candidate;
                        ++heap_size;
                        heapify_up(heap, heap_size);
                    }
                }
            }
        }
    }

    // 按同步分数排序
    int len_unsorted = heap_size;
    while (len_unsorted > 1) {
        candidate_t tmp = heap[len_unsorted - 1];
        heap[len_unsorted - 1] = heap[0];
        heap[0] = tmp;
        len_unsorted--;
        heapify_down(heap, len_unsorted);
    }

    return heap_size;
}

static void ft4_extract_likelihood(const waterfall_t *wf, const candidate_t *cand, float *log174) {
    const uint8_t *mag_cand = wf->mag + get_index(wf, cand);

    for (int k = 0; k < FT4_ND; ++k) {
        // Skip either 5, 9 or 13 sync symbols
        int sym_idx = k + ((k < 29) ? 5 : ((k < 58) ? 9 : 13));
        int bit_idx = 2 * k;

        int block = cand->time_offset + sym_idx;
        if ((block < 0) || (block >= wf->num_blocks)) {
            log174[bit_idx + 0] = 0;
            log174[bit_idx + 1] = 0;
        } else {
            const uint8_t *ps = mag_cand + (sym_idx * wf->block_stride);
            ft4_extract_symbol(ps, log174 + bit_idx);
        }
    }

    // FT4 一帧只有 2bit/符号，174 中后半部分未写入的位清零，避免残值干扰 LDPC
    for (int i = FT4_ND * 2; i < FTX_LDPC_N; ++i) {
        log174[i] = 0.0f;
    }
}

// 解开可能的 FT8 信号
static void ft8_extract_likelihood(const waterfall_t *wf, candidate_t *cand, float *log174) {
    const uint8_t *mag_cand = wf->mag + get_index(wf, cand);

    for (int k = 0; k < FT8_ND; ++k) {
        int sym_idx = k + ((k < 29) ? 7 : 14);
        int bit_idx = 3 * k;

        int block = cand->time_offset + sym_idx;
        if ((block < 0) || (block >= wf->num_blocks)) {
            log174[bit_idx + 0] = 0;
            log174[bit_idx + 1] = 0;
            log174[bit_idx + 2] = 0;
        } else {
            const uint8_t *ps = mag_cand + (sym_idx * wf->block_stride);
            ft8_extract_symbol(ps, log174 + bit_idx);
        }
    }
}

static void ftx_normalize_logl(float *log174) {
    float sum = 0.0f;
    float sum2 = 0.0f;

    for (int i = 0; i < FTX_LDPC_N; ++i) {
        sum += log174[i];
        sum2 += log174[i] * log174[i];
    }

    float inv_n = 1.0f / FTX_LDPC_N;
    float variance = (sum2 - (sum * sum * inv_n)) * inv_n;

    // 避免极弱信号/纯噪声下方差过小导致归一化爆掉
    if (variance < 1e-6f) {
        variance = 1e-6f;
    }

    // 略微提高归一化系数，增强软判决输入的动态范围
    float norm_factor = sqrtf(26.0f / variance);
    for (int i = 0; i < FTX_LDPC_N; ++i) {
        log174[i] *= norm_factor;
    }
}

/**
 * FT8 SNR 估算
 *
 */
static void ft8_guess_snr(const waterfall_t *wf, candidate_t *cand) {
    const float *mag_signal = wf->mag2 + get_index(wf, cand);

    float signal = 0.0f;
    float noise = 0.0f;
    int count = 0;

    for (int i = 0; i < FT8_LENGTH_SYNC; ++i) {
        int block0 = i;
        int block1 = i + FT8_SYNC_OFFSET;
        int block2 = i + FT8_SYNC_OFFSET * 2;

        if ((cand->time_offset + block0 >= 0) && (cand->time_offset + block0 < wf->num_blocks)) {
            signal += mag_signal[block0 * wf->block_stride + kFT8CostasPattern[i]];
            noise += mag_signal[block0 * wf->block_stride + ((kFT8CostasPattern[i] + 4) % 8)];
            ++count;
        }

        if ((cand->time_offset + block1 >= 0) && (cand->time_offset + block1 < wf->num_blocks)) {
            signal += mag_signal[block1 * wf->block_stride + kFT8CostasPattern[i]];
            noise += mag_signal[block1 * wf->block_stride + ((kFT8CostasPattern[i] + 4) % 8)];
            ++count;
        }

        if ((cand->time_offset + block2 >= 0) && (cand->time_offset + block2 < wf->num_blocks)) {
            signal += mag_signal[block2 * wf->block_stride + kFT8CostasPattern[i]];
            noise += mag_signal[block2 * wf->block_stride + ((kFT8CostasPattern[i] + 4) % 8)];
            ++count;
        }
    }

    if (noise > 0.0f && count > 0) {
        float raw = signal / noise;
        cand->snr = clamp_snr_value((int)floorf(10.0f * log10f(1E-12f + raw) - 24.0f + 0.5f));
    } else {
        cand->snr = -100;
    }
}

/**
 * FT4 SNR 估算
 */
static void ft4_guess_snr(const waterfall_t *wf, candidate_t *cand) {
    const float *mag_signal = wf->mag2 + get_index(wf, cand);

    float signal = 0.0f;
    float noise = 0.0f;
    int signal_count = 0;
    int noise_count = 0;

    for (int m = 0; m < FT4_NUM_SYNC; ++m) {
        for (int k = 0; k < FT4_LENGTH_SYNC; ++k) {
            int block = 1 + (FT4_SYNC_OFFSET * m) + k;
            int block_abs = cand->time_offset + block;

            if (block_abs < 0 || block_abs >= wf->num_blocks) {
                continue;
            }

            int tone = kFT4CostasPattern[m][k];
            const float *p4 = mag_signal + (block * wf->block_stride);

            signal += p4[tone];
            ++signal_count;

            for (int n = 0; n < 4; ++n) {
                if (n == tone) continue;
                noise += p4[n];
                ++noise_count;
            }
        }
    }

    if (signal_count > 0 && noise_count > 0) {
        float signal_avg = signal / (float)signal_count;
        float noise_avg = noise / (float)noise_count;

        // 这个 offset 比 FT8 小，适合 FT4 显示
        int snr = (int)floorf(10.0f * log10f(1E-12f + signal_avg / noise_avg) - 20.0f + 0.5f);
        cand->snr = clamp_snr_value(snr);
    } else {
        cand->snr = -100;
    }
}

static void ftx_guess_snr(const waterfall_t *wf, candidate_t *cand) {
    if (wf->protocol == PROTO_FT4) {
        ft4_guess_snr(wf, cand);
    } else {
        ft8_guess_snr(wf, cand);
    }
}

// max_iterations=20 LDPC的迭代次数。
bool
ft8_decode(waterfall_t *wf, candidate_t *cand, message_t *message, int max_iterations,
           decode_status_t *status) {
    float log174[FTX_LDPC_N];

    if (wf->protocol == PROTO_FT4) {
        ft4_extract_likelihood(wf, cand, log174);
    } else {
        ft8_extract_likelihood(wf, cand, log174);
    }

    ftx_normalize_logl(log174);

    uint8_t plain174[FTX_LDPC_N];

    bp_decode(log174, max_iterations, plain174, &status->ldpc_errors);
    // ldpc_decode(log174, max_iterations, plain174, &status->ldpc_errors);

    if (status->ldpc_errors > 0) {
        return false;
    }

    uint8_t a91[FTX_LDPC_K_BYTES];
    pack_bits(plain174, FTX_LDPC_K, a91);

    status->crc_extracted = ftx_extract_crc(a91);

    // [1]: 'The CRC is calculated on the source-encoded message, zero-extended from 77 to 82 bits.'
    a91[9] &= 0xF8;
    a91[10] &= 0x00;
    status->crc_calculated = ftx_compute_crc(a91, 96 - 14);

    if (status->crc_extracted != status->crc_calculated) {
        return false;
    }

    if (wf->protocol == PROTO_FT4) {
        // FT4 在 CRC/FEC 前做过异或扰码，解码后恢复
        for (int i = 0; i < 10; ++i) {
            a91[i] ^= kFT4XORSequence[i];
        }
    }

    message->call_to[0] = message->call_de[0] = message->maidenGrid[0] = message->extra[0] = '\0';
    message->call_de_hash.hash10 = message->call_de_hash.hash12 = message->call_de_hash.hash22 = 0;
    message->call_to_hash.hash10 = message->call_to_hash.hash12 = message->call_to_hash.hash22 = 0;
    memcpy(message->a91, a91, FTX_LDPC_K_BYTES);

    status->unpack_status = unpackToMessage_t(a91, message);

    if (status->unpack_status < 0) {
        return false;
    }

    message->hash = status->crc_extracted;

    // 按当前协议分别估算 SNR
    ftx_guess_snr(wf, cand);

    return true;
}

static float max2(float a, float b) {
    return (a >= b) ? a : b;
}

static float max4(float a, float b, float c, float d) {
    return max2(max2(a, b), max2(c, d));
}

static void heapify_down(candidate_t heap[], int heap_size) {
    int current = 0;
    while (true) {
        int largest = current;
        int left = 2 * current + 1;
        int right = left + 1;

        if (left < heap_size && heap[left].score < heap[largest].score) {
            largest = left;
        }
        if (right < heap_size && heap[right].score < heap[largest].score) {
            largest = right;
        }
        if (largest == current) {
            break;
        }

        candidate_t tmp = heap[largest];
        heap[largest] = heap[current];
        heap[current] = tmp;
        current = largest;
    }
}

static void heapify_up(candidate_t heap[], int heap_size) {
    int current = heap_size - 1;
    while (current > 0) {
        int parent = (current - 1) / 2;
        if (heap[current].score >= heap[parent].score) {
            break;
        }

        candidate_t tmp = heap[parent];
        heap[parent] = heap[current];
        heap[current] = tmp;
        current = parent;
    }
}

// Compute unnormalized log likelihood log(p(1) / p(0)) of 2 message bits (1 FSK symbol)
static void ft4_extract_symbol(const uint8_t *wf, float *logl) {
    float s2[4];

    for (int j = 0; j < 4; ++j) {
        s2[j] = (float) wf[kFT4GrayMap[j]];
    }

    logl[0] = max2(s2[2], s2[3]) - max2(s2[0], s2[1]);
    logl[1] = max2(s2[1], s2[3]) - max2(s2[0], s2[2]);
}

// Compute unnormalized log likelihood log(p(1) / p(0)) of 3 message bits (1 FSK symbol)
static void ft8_extract_symbol(const uint8_t *wf, float *logl) {
    float s2[8];

    for (int j = 0; j < 8; ++j) {
        s2[j] = (float) wf[kFT8GrayMap[j]];
    }

    logl[0] = max4(s2[4], s2[5], s2[6], s2[7]) - max4(s2[0], s2[1], s2[2], s2[3]);
    logl[1] = max4(s2[2], s2[3], s2[6], s2[7]) - max4(s2[0], s2[1], s2[4], s2[5]);
    logl[2] = max4(s2[1], s2[3], s2[5], s2[7]) - max4(s2[0], s2[2], s2[4], s2[6]);
}

// Compute unnormalized log likelihood log(p(1) / p(0)) of bits corresponding to several FSK symbols at once
static void
ft8_decode_multi_symbols(const uint8_t *wf, int num_bins, int n_syms, int bit_idx, float *log174) {
    const int n_bits = 3 * n_syms;
    const int n_tones = (1 << n_bits);

    float s2[n_tones];

    for (int j = 0; j < n_tones; ++j) {
        int j1 = j & 0x07;
        if (n_syms == 1) {
            s2[j] = (float) wf[kFT8GrayMap[j1]];
            continue;
        }
        int j2 = (j >> 3) & 0x07;
        if (n_syms == 2) {
            s2[j] = (float) wf[kFT8GrayMap[j2]];
            s2[j] += (float) wf[kFT8GrayMap[j1] + 4 * num_bins];
            continue;
        }
        int j3 = (j >> 6) & 0x07;
        s2[j] = (float) wf[kFT8GrayMap[j3]];
        s2[j] += (float) wf[kFT8GrayMap[j2] + 4 * num_bins];
        s2[j] += (float) wf[kFT8GrayMap[j1] + 8 * num_bins];
    }

    for (int i = 0; i < n_bits; ++i) {
        if (bit_idx + i >= FTX_LDPC_N) {
            break;
        }

        uint16_t mask = (n_tones >> (i + 1));
        float max_zero = -1000, max_one = -1000;
        for (int n = 0; n < n_tones; ++n) {
            if (n & mask) {
                max_one = max2(max_one, s2[n]);
            } else {
                max_zero = max2(max_zero, s2[n]);
            }
        }

        log174[bit_idx + i] = max_one - max_zero;
    }
}

// Packs a string of bits each represented as a zero/non-zero byte in plain[],
// as a string of packed bits starting from the MSB of the first byte of packed[]
static void pack_bits(const uint8_t bit_array[], int num_bits, uint8_t packed[]) {
    int num_bytes = (num_bits + 7) / 8;
    for (int i = 0; i < num_bytes; ++i) {
        packed[i] = 0;
    }

    uint8_t mask = 0x80;
    int byte_idx = 0;
    for (int i = 0; i < num_bits; ++i) {
        if (bit_array[i]) {
            packed[byte_idx] |= mask;
        }
        mask >>= 1;
        if (!mask) {
            mask = 0x80;
            ++byte_idx;
        }
    }
}