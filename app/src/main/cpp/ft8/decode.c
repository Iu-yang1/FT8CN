#include "decode.h"
#include "constants.h"
#include "crc.h"
#include "ldpc.h"
#include "encode.h"
#include "pack.h"
#include "unpack.h"

#include <stdbool.h>
#include <float.h>
#include <math.h>
#include <string.h>
#include <stdio.h>
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
static void ft4_decode_multi_symbols(const uint8_t *wf, int symbol_stride, int n_syms, int bit_idx, float *log174);
static void ft8_decode_multi_symbols(const uint8_t *wf, int symbol_stride, int n_syms, int bit_idx, float *log174);
static void ft4_extract_likelihood_n(const waterfall_t *wf, const candidate_t *cand, int n_syms, float *log174);
static void ft8_extract_likelihood_n(const waterfall_t *wf, const candidate_t *cand, int n_syms, float *log174);
static void ft4_extract_likelihood_strong(const waterfall_t *wf, const candidate_t *cand, float *log174);
static void ft8_extract_likelihood_strong(const waterfall_t *wf, candidate_t *cand, float *log174);
static int ftx_ldpc_check_codeword(const uint8_t codeword[]);
static bool ftx_osd_refine(const float *log174, uint8_t plain174[], int *errors);
static bool ftx_try_decode_pass(const float *log174, int max_iterations, float llr_scale,
                                uint8_t plain174[], uint8_t a91[], decode_status_t *status);
static void ftx_unpack_bits_from_bytes(const uint8_t packed[], int num_bits, uint8_t unpacked[]);
static bool ftx_build_ap_hypothesis(ftx_protocol_t protocol, const char *text,
                                    uint8_t a91[], uint8_t codeword174[]);
static void ftx_apply_ap_prior(float *log174, const uint8_t codeword174[], float prior_strength);
static float ftx_score_ap_match(const float *log174, const uint8_t codeword174[]);
static bool ftx_try_ap_hypothesis(const float *log174, ftx_protocol_t protocol, const char *text,
                                  int max_iterations, uint8_t plain174[], uint8_t a91[],
                                  decode_status_t *status, float *evidence_out);
static bool ftx_try_ap_decode(const float *log174, ftx_protocol_t protocol, const ap_hints_t *ap_hints,
                              int max_iterations, uint8_t plain174[], uint8_t a91[],
                              decode_status_t *status);

static const int kApReportValues[] = {1, 3, 5, 7, 10, 12, 15, 18, 20, 24};
static const float kApPriorStrength = 0.75f;
static const float kApMinEvidence = 1.15f;
static const float kApMinMargin = 0.18f;
// AP-lite uses a very small fixed hypothesis set so the fallback path remains bounded and reviewable.

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
    const bool is_ft4 = (wf->protocol == PROTO_FT4);
    // FT4 对起始时刻偏差更敏感，放宽搜索窗口以提高检出率
    const int time_offset_min = is_ft4 ? -40 : -12;
    const int time_offset_max = is_ft4 ? 80 : 24;
    // 频率扫描边界：FT4 为 4-FSK，FT8 为 8-FSK
    const int tone_span = is_ft4 ? 3 : 7;

    // 注意：
    // FT8 / FT4 共用同一套扫描框架，但窗口按协议分别配置
    // FT4 放宽时偏搜索范围，可减少“耳朵能听到但候选未入堆”的漏检
    for (candidate.time_sub = 0; candidate.time_sub < wf->time_osr; ++candidate.time_sub) {
        for (candidate.freq_sub = 0; candidate.freq_sub < wf->freq_osr; ++candidate.freq_sub) {
            for (candidate.time_offset = time_offset_min; candidate.time_offset < time_offset_max; ++candidate.time_offset) {
                for (candidate.freq_offset = 0;
                     (candidate.freq_offset + tone_span) < wf->num_bins; ++candidate.freq_offset) {

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
    float llr_tmp[FTX_LDPC_N];
    float llr_acc[FTX_LDPC_N];
    int llr_cnt[FTX_LDPC_N];

    memset(llr_acc, 0, sizeof(llr_acc));
    memset(llr_cnt, 0, sizeof(llr_cnt));

    // FT4 融合 1/2/4 符号联合软判决，改善强信号但软信息失真的场景
    const int joint_list[] = {1, 2, 4};
    for (int j = 0; j < 3; ++j) {
        memset(llr_tmp, 0, sizeof(llr_tmp));
        ft4_extract_likelihood_n(wf, cand, joint_list[j], llr_tmp);
        for (int i = 0; i < FTX_LDPC_N; ++i) {
            llr_acc[i] += llr_tmp[i];
            llr_cnt[i] += 1;
        }
    }

    for (int i = 0; i < FTX_LDPC_N; ++i) {
        log174[i] = (llr_cnt[i] > 0) ? (llr_acc[i] / (float) llr_cnt[i]) : 0.0f;
    }
}

// 计算 FT8 的软判决输入
static void ft8_extract_likelihood(const waterfall_t *wf, candidate_t *cand, float *log174) {
    float llr_tmp[FTX_LDPC_N];
    float llr_acc[FTX_LDPC_N];
    int llr_cnt[FTX_LDPC_N];

    memset(llr_acc, 0, sizeof(llr_acc));
    memset(llr_cnt, 0, sizeof(llr_cnt));

    // FT8 融合 1/2/3 符号联合软判决，降低单符号判决波动
    const int joint_list[] = {1, 2, 3};
    for (int j = 0; j < 3; ++j) {
        memset(llr_tmp, 0, sizeof(llr_tmp));
        ft8_extract_likelihood_n(wf, cand, joint_list[j], llr_tmp);
        for (int i = 0; i < FTX_LDPC_N; ++i) {
            llr_acc[i] += llr_tmp[i];
            llr_cnt[i] += 1;
        }
    }

    for (int i = 0; i < FTX_LDPC_N; ++i) {
        log174[i] = (llr_cnt[i] > 0) ? (llr_acc[i] / (float) llr_cnt[i]) : 0.0f;
    }
}

static void ft4_extract_likelihood_n(const waterfall_t *wf, const candidate_t *cand, int n_syms, float *log174) {
    const uint8_t *mag_cand = wf->mag + get_index(wf, cand);
    memset(log174, 0, sizeof(float) * FTX_LDPC_N);

    const int data_k_start[3] = {0, 29, 58};
    const int sym_start[3] = {5, 38, 71};

    for (int seg = 0; seg < 3; ++seg) {
        int pos = 0;
        while (pos < 29) {
            int group = n_syms;
            if ((pos + group) > 29) {
                // 联合判决不能跨段，尾部退化为单符号
                group = 1;
            }

            int data_idx = data_k_start[seg] + pos;
            int bit_idx = 2 * data_idx;
            int first_sym = sym_start[seg] + pos;
            int block = cand->time_offset + first_sym;

            if (group == 1) {
                if ((block < 0) || (block >= wf->num_blocks)) {
                    log174[bit_idx + 0] = 0.0f;
                    log174[bit_idx + 1] = 0.0f;
                } else {
                    const uint8_t *ps = mag_cand + (first_sym * wf->block_stride);
                    ft4_extract_symbol(ps, log174 + bit_idx);
                }
                pos += 1;
                continue;
            }

            bool in_range = true;
            for (int s = 0; s < group; ++s) {
                int b = cand->time_offset + first_sym + s;
                if ((b < 0) || (b >= wf->num_blocks)) {
                    in_range = false;
                    break;
                }
            }

            if (!in_range) {
                for (int b = 0; b < 2 * group && (bit_idx + b) < FTX_LDPC_N; ++b) {
                    log174[bit_idx + b] = 0.0f;
                }
            } else {
                const uint8_t *ps = mag_cand + (first_sym * wf->block_stride);
                ft4_decode_multi_symbols(ps, wf->block_stride, group, bit_idx, log174);
            }

            pos += group;
        }
    }
}

static void ft8_extract_likelihood_n(const waterfall_t *wf, const candidate_t *cand, int n_syms, float *log174) {
    const uint8_t *mag_cand = wf->mag + get_index(wf, cand);
    memset(log174, 0, sizeof(float) * FTX_LDPC_N);

    const int data_k_start[2] = {0, 29};
    const int sym_start[2] = {7, 43};

    for (int seg = 0; seg < 2; ++seg) {
        int pos = 0;
        while (pos < 29) {
            int group = n_syms;
            if ((pos + group) > 29) {
                // 联合判决不能跨段，尾部退化为单符号
                group = 1;
            }

            int data_idx = data_k_start[seg] + pos;
            int bit_idx = 3 * data_idx;
            int first_sym = sym_start[seg] + pos;
            int block = cand->time_offset + first_sym;

            if (group == 1) {
                if ((block < 0) || (block >= wf->num_blocks)) {
                    log174[bit_idx + 0] = 0.0f;
                    log174[bit_idx + 1] = 0.0f;
                    log174[bit_idx + 2] = 0.0f;
                } else {
                    const uint8_t *ps = mag_cand + (first_sym * wf->block_stride);
                    ft8_extract_symbol(ps, log174 + bit_idx);
                }
                pos += 1;
                continue;
            }

            bool in_range = true;
            for (int s = 0; s < group; ++s) {
                int b = cand->time_offset + first_sym + s;
                if ((b < 0) || (b >= wf->num_blocks)) {
                    in_range = false;
                    break;
                }
            }

            if (!in_range) {
                for (int b = 0; b < 3 * group && (bit_idx + b) < FTX_LDPC_N; ++b) {
                    log174[bit_idx + b] = 0.0f;
                }
            } else {
                const uint8_t *ps = mag_cand + (first_sym * wf->block_stride);
                ft8_decode_multi_symbols(ps, wf->block_stride, group, bit_idx, log174);
            }

            pos += group;
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

 //max_iterations=20 LDPC的迭代次数。
bool
ft8_decode(waterfall_t *wf, candidate_t *cand, message_t *message, int max_iterations,
           const ap_hints_t *ap_hints, decode_status_t *status) {
    status->ldpc_errors = FTX_LDPC_M;
    status->crc_extracted = 0;
    status->crc_calculated = 0;
    status->unpack_status = -1;

    float log174[FTX_LDPC_N];

    if (wf->protocol == PROTO_FT4) {
        ft4_extract_likelihood(wf, cand, log174);
    } else {
        ft8_extract_likelihood(wf, cand, log174);
    }

    ftx_normalize_logl(log174);
    float ap_base_log174[FTX_LDPC_N];
    memcpy(ap_base_log174, log174, sizeof(ap_base_log174));
    // AP-lite starts from the same normalized LLRs as the regular decode path.

    uint8_t plain174[FTX_LDPC_N];
    uint8_t a91[FTX_LDPC_K_BYTES];
    bool crc_ok = ftx_try_decode_pass(log174, max_iterations, 1.0f, plain174, a91, status);

    // Deep decode gets one stronger CRC-preserving retry on near-converged candidates.
    if (!crc_ok && max_iterations >= 100 && status->ldpc_errors >= 0 && status->ldpc_errors <= 6) {
        decode_status_t retry_status = *status;
        uint8_t retry_plain174[FTX_LDPC_N];
        uint8_t retry_a91[FTX_LDPC_K_BYTES];
        float retry_log174[FTX_LDPC_N];
        int retry_iterations = max_iterations + (max_iterations / 2);
        if (retry_iterations > 320) {
            retry_iterations = 320;
        }

        if (wf->protocol == PROTO_FT4) {
            ft4_extract_likelihood_strong(wf, cand, retry_log174);
        } else {
            ft8_extract_likelihood_strong(wf, cand, retry_log174);
        }
        ftx_normalize_logl(retry_log174);
        memcpy(ap_base_log174, retry_log174, sizeof(ap_base_log174));
        // When the strong retry runs, AP-lite reuses that stronger LLR view as its input.

        crc_ok = ftx_try_decode_pass(retry_log174, retry_iterations, 0.92f,
                                     retry_plain174, retry_a91, &retry_status);
        if (crc_ok) {
            memcpy(plain174, retry_plain174, sizeof(plain174));
            memcpy(a91, retry_a91, sizeof(a91));
            *status = retry_status;
        }
    }

    int apMinScore = (wf->protocol == PROTO_FT4) ? 12 : 14;
    if (!crc_ok &&
        max_iterations >= 100 &&
        status->ldpc_errors >= 0 &&
        status->ldpc_errors <= 8 &&
        ap_hints != NULL &&
        ap_hints->hint_call_count > 0 &&
        cand->score >= apMinScore) {
        decode_status_t ap_status = *status;
        crc_ok = ftx_try_ap_decode(ap_base_log174, wf->protocol, ap_hints, max_iterations,
                                   plain174, a91, &ap_status);
        if (crc_ok) {
            *status = ap_status;
        }
    }
    // AP-lite stays behind the near-converged deep-decode failure gate and also skips low-score candidates.

    if (!crc_ok) {
        return false;
    }

    if (wf->protocol == PROTO_FT4) {
        // FT4 在 CRC/FEC 前做过异或扰码，解码后恢复
        for (int i = 0; i < 10; ++i) {
            a91[i] ^= kFT4XORSequence[i];
        }
    }

    message->call_to[0] = message->call_de[0] = message->dx_call_to2[0] =
            message->maidenGrid[0] = message->extra[0] = message->rtty_state[0] =
            message->arrl_rac[0] = message->arrl_class[0] = '\0';
    message->call_de_hash.hash10 = message->call_de_hash.hash12 = message->call_de_hash.hash22 = 0;
    message->call_to_hash.hash10 = message->call_to_hash.hash12 = message->call_to_hash.hash22 = 0;
    message->report = -100;
    message->r_flag = 0;
    message->rtty_tu = 0;
    message->eu_serial = 0;
    memcpy(message->a91, a91, FTX_LDPC_K_BYTES);

    status->unpack_status = unpackToMessage_t(a91, message);

    if (status->unpack_status < 0) {
        // CRC/FEC 已通过但当前实现不支持该消息类型时，保留占位文本而不直接丢弃
        message->call_to[0] = '\0';
        message->call_de[0] = '\0';
        message->dx_call_to2[0] = '\0';
        message->maidenGrid[0] = '\0';
        message->rtty_state[0] = '\0';
        message->arrl_rac[0] = '\0';
        message->arrl_class[0] = '\0';
        message->call_de_hash.hash10 = message->call_de_hash.hash12 = message->call_de_hash.hash22 = 0;
        message->call_to_hash.hash10 = message->call_to_hash.hash12 = message->call_to_hash.hash22 = 0;
        message->r_flag = 0;
        message->rtty_tu = 0;
        message->eu_serial = 0;
        snprintf(message->extra, sizeof(message->extra), "UNSUP i3=%u n3=%u",
                 (unsigned) message->i3, (unsigned) message->n3);
        snprintf(message->text, sizeof(message->text), "%s", message->extra);
    }

    message->hash = status->crc_extracted;

    // 按当前协议分别估算 SNR
    ftx_guess_snr(wf, cand);

    return true;
}

static bool ftx_try_decode_pass(const float *log174, int max_iterations, float llr_scale,
                                uint8_t plain174[], uint8_t a91[], decode_status_t *status) {
    float work_log174[FTX_LDPC_N];
    memcpy(work_log174, log174, sizeof(work_log174));

    if (llr_scale > 0.0f && fabsf(llr_scale - 1.0f) > 1e-6f) {
        for (int i = 0; i < FTX_LDPC_N; ++i) {
            work_log174[i] *= llr_scale;
        }
    }

    // First pass: fast BP.
    bp_decode(work_log174, max_iterations, plain174, &status->ldpc_errors);

    // Second pass: full LDPC if parity still fails.
    if (status->ldpc_errors > 0) {
        ldpc_decode(work_log174, max_iterations, plain174, &status->ldpc_errors);
    }

    // Third pass: lightweight OSD bit-flip search on the least reliable bits.
    if (status->ldpc_errors > 0) {
        if (!ftx_osd_refine(work_log174, plain174, &status->ldpc_errors)) {
            return false;
        }
    }

    pack_bits(plain174, FTX_LDPC_K, a91);
    status->crc_extracted = ftx_extract_crc(a91);

    uint8_t crc_buf[FTX_LDPC_K_BYTES];
    memcpy(crc_buf, a91, sizeof(crc_buf));

    // The CRC is calculated on the source-encoded message, zero-extended from 77 to 82 bits.
    crc_buf[9] &= 0xF8;
    crc_buf[10] &= 0x00;
    status->crc_calculated = ftx_compute_crc(crc_buf, 96 - 14);

    return status->crc_extracted == status->crc_calculated;
}

static void ftx_unpack_bits_from_bytes(const uint8_t packed[], int num_bits, uint8_t unpacked[]) {
    for (int i = 0; i < num_bits; ++i) {
        int byteIndex = i / 8;
        int bitIndex = 7 - (i % 8);
        unpacked[i] = (uint8_t) ((packed[byteIndex] >> bitIndex) & 0x01u);
    }
    // AP-lite expands the packed 174-bit codeword so soft-prior injection and evidence scoring can share it.
}

static bool ftx_build_ap_hypothesis(ftx_protocol_t protocol, const char *text,
                                    uint8_t a91[], uint8_t codeword174[]) {
    uint8_t payload[10];
    if (pack77_1(text, payload) != 0) {
        return false;
    }

    if (protocol == PROTO_FT4) {
        for (int i = 0; i < 10; ++i) {
            payload[i] ^= kFT4XORSequence[i];
        }
        // FT4 AP hypotheses must use the same whitening and CRC path as the real protocol.
    }

    ftx_add_crc(payload, a91);

    uint8_t codewordBytes[FTX_LDPC_N_BYTES];
    ftx_encode_174(a91, codewordBytes);
    ftx_unpack_bits_from_bytes(codewordBytes, FTX_LDPC_N, codeword174);
    return true;
}

static void ftx_apply_ap_prior(float *log174, const uint8_t codeword174[], float prior_strength) {
    for (int i = 0; i < FTX_LDPC_N; ++i) {
        log174[i] += codeword174[i] ? prior_strength : -prior_strength;
    }
    // The prior is additive rather than hard-overwritten, so AP failures do not erase the measurement itself.
}

static float ftx_score_ap_match(const float *log174, const uint8_t codeword174[]) {
    float score = 0.0f;

    for (int i = 0; i < FTX_LDPC_N; ++i) {
        score += codeword174[i] ? log174[i] : -log174[i];
    }

    // The normalized mean agreement score adds one more conservative gate against noise-only AP matches.
    return score / (float) FTX_LDPC_N;
}

static bool ftx_try_ap_hypothesis(const float *log174, ftx_protocol_t protocol, const char *text,
                                  int max_iterations, uint8_t plain174[], uint8_t a91[],
                                  decode_status_t *status, float *evidence_out) {
    uint8_t hypothesisA91[FTX_LDPC_K_BYTES];
    uint8_t hypothesisCodeword174[FTX_LDPC_N];
    if (!ftx_build_ap_hypothesis(protocol, text, hypothesisA91, hypothesisCodeword174)) {
        return false;
    }

    float apLog174[FTX_LDPC_N];
    memcpy(apLog174, log174, sizeof(apLog174));
    ftx_apply_ap_prior(apLog174, hypothesisCodeword174, kApPriorStrength);

    uint8_t apPlain174[FTX_LDPC_N];
    uint8_t apA91[FTX_LDPC_K_BYTES];
    decode_status_t apStatus = *status;

    int apIterations = max_iterations + 40;
    if (apIterations > 320) {
        apIterations = 320;
    }

    if (!ftx_try_decode_pass(apLog174, apIterations, 1.0f, apPlain174, apA91, &apStatus)) {
        return false;
    }

    if (memcmp(apA91, hypothesisA91, sizeof(hypothesisA91)) != 0) {
        return false;
    }
    // Even after AP assistance, LDPC and CRC must land on the exact same hypothesis message.

    if (evidence_out != NULL) {
        *evidence_out = ftx_score_ap_match(log174, hypothesisCodeword174);
    }

    memcpy(plain174, apPlain174, sizeof(apPlain174));
    memcpy(a91, apA91, sizeof(apA91));
    *status = apStatus;
    return true;
}

static bool ftx_try_ap_decode(const float *log174, ftx_protocol_t protocol, const ap_hints_t *ap_hints,
                              int max_iterations, uint8_t plain174[], uint8_t a91[],
                              decode_status_t *status) {
    if (ap_hints == NULL || ap_hints->my_call[0] == '\0' || ap_hints->hint_call_count <= 0) {
        return false;
    }

    uint8_t bestPlain174[FTX_LDPC_N];
    uint8_t bestA91[FTX_LDPC_K_BYTES];
    decode_status_t bestStatus = *status;
    float bestEvidence = -FLT_MAX;
    float secondBestEvidence = -FLT_MAX;
    bool found = false;
    char text[48];
    char extra[8];

    for (int i = 0; i < ap_hints->hint_call_count; ++i) {
        const char *otherCall = ap_hints->hint_calls[i];
        const char *otherGrid = ap_hints->hint_grids[i];

        if (otherCall[0] == '\0') {
            continue;
        }

        if (otherGrid[0] != '\0') {
            uint8_t trialPlain174[FTX_LDPC_N];
            uint8_t trialA91[FTX_LDPC_K_BYTES];
            decode_status_t trialStatus = *status;
            float evidence = 0.0f;

            snprintf(text, sizeof(text), "%s %s %s", ap_hints->my_call, otherCall, otherGrid);
            if (ftx_try_ap_hypothesis(log174, protocol, text, max_iterations,
                                      trialPlain174, trialA91, &trialStatus, &evidence)) {
                if (evidence > bestEvidence) {
                    secondBestEvidence = bestEvidence;
                    bestEvidence = evidence;
                    memcpy(bestPlain174, trialPlain174, sizeof(bestPlain174));
                    memcpy(bestA91, trialA91, sizeof(bestA91));
                    bestStatus = trialStatus;
                    found = true;
                } else if (evidence > secondBestEvidence) {
                    secondBestEvidence = evidence;
                }
            }
            // If the peer grid is known, try the most common standard first-reply shape: MYCALL DXCALL GRID.
        }

        for (int reportIdx = 0; reportIdx < (int) (sizeof(kApReportValues) / sizeof(kApReportValues[0])); ++reportIdx) {
            int reportValue = kApReportValues[reportIdx];
            const char *prefixList[] = {"-", "R-"};

            for (int prefixIdx = 0; prefixIdx < 2; ++prefixIdx) {
                snprintf(extra, sizeof(extra), "%s%02d", prefixList[prefixIdx], reportValue);

                for (int order = 0; order < 2; ++order) {
                    const char *callA = (order == 0) ? ap_hints->my_call : otherCall;
                    const char *callB = (order == 0) ? otherCall : ap_hints->my_call;
                    uint8_t trialPlain174[FTX_LDPC_N];
                    uint8_t trialA91[FTX_LDPC_K_BYTES];
                    decode_status_t trialStatus = *status;
                    float evidence = 0.0f;

                    snprintf(text, sizeof(text), "%s %s %s", callA, callB, extra);
                    if (ftx_try_ap_hypothesis(log174, protocol, text, max_iterations,
                                              trialPlain174, trialA91, &trialStatus, &evidence)) {
                        if (evidence > bestEvidence) {
                            secondBestEvidence = bestEvidence;
                            bestEvidence = evidence;
                            memcpy(bestPlain174, trialPlain174, sizeof(bestPlain174));
                            memcpy(bestA91, trialA91, sizeof(bestA91));
                            bestStatus = trialStatus;
                            found = true;
                        } else if (evidence > secondBestEvidence) {
                            secondBestEvidence = evidence;
                        }
                    }
                }
            }
        }

        const char *ackList[] = {"RRR", "RR73", "73"};
        for (int ackIdx = 0; ackIdx < 3; ++ackIdx) {
            for (int order = 0; order < 2; ++order) {
                const char *callA = (order == 0) ? ap_hints->my_call : otherCall;
                const char *callB = (order == 0) ? otherCall : ap_hints->my_call;
                uint8_t trialPlain174[FTX_LDPC_N];
                uint8_t trialA91[FTX_LDPC_K_BYTES];
                decode_status_t trialStatus = *status;
                float evidence = 0.0f;

                snprintf(text, sizeof(text), "%s %s %s", callA, callB, ackList[ackIdx]);
                if (ftx_try_ap_hypothesis(log174, protocol, text, max_iterations,
                                          trialPlain174, trialA91, &trialStatus, &evidence)) {
                    if (evidence > bestEvidence) {
                        secondBestEvidence = bestEvidence;
                        bestEvidence = evidence;
                        memcpy(bestPlain174, trialPlain174, sizeof(bestPlain174));
                        memcpy(bestA91, trialA91, sizeof(bestA91));
                        bestStatus = trialStatus;
                        found = true;
                    } else if (evidence > secondBestEvidence) {
                        secondBestEvidence = evidence;
                    }
                }
            }
        }
    }

    if (!found || bestEvidence < kApMinEvidence) {
        return false;
    }

    if (secondBestEvidence > -FLT_MAX / 2.0f &&
        (bestEvidence - secondBestEvidence) < kApMinMargin) {
        return false;
    }
    // Only accept AP when the best hypothesis is both strong enough and clearly ahead of the runner-up.

    memcpy(plain174, bestPlain174, sizeof(bestPlain174));
    memcpy(a91, bestA91, sizeof(bestA91));
    *status = bestStatus;
    return true;
}

static void ft4_extract_likelihood_strong(const waterfall_t *wf, const candidate_t *cand, float *log174) {
    float llr_tmp[FTX_LDPC_N];
    float llr_acc[FTX_LDPC_N];
    float llr_weight[FTX_LDPC_N];

    memset(llr_acc, 0, sizeof(llr_acc));
    memset(llr_weight, 0, sizeof(llr_weight));

    const int joint_list[] = {1, 2, 4, 5};
    const float weight_list[] = {1.00f, 1.05f, 1.20f, 1.35f};
    for (int j = 0; j < 4; ++j) {
        memset(llr_tmp, 0, sizeof(llr_tmp));
        ft4_extract_likelihood_n(wf, cand, joint_list[j], llr_tmp);
        for (int i = 0; i < FTX_LDPC_N; ++i) {
            llr_acc[i] += llr_tmp[i] * weight_list[j];
            llr_weight[i] += weight_list[j];
        }
    }

    for (int i = 0; i < FTX_LDPC_N; ++i) {
        log174[i] = (llr_weight[i] > 0.0f) ? (llr_acc[i] / llr_weight[i]) : 0.0f;
    }
}

static void ft8_extract_likelihood_strong(const waterfall_t *wf, candidate_t *cand, float *log174) {
    float llr_tmp[FTX_LDPC_N];
    float llr_acc[FTX_LDPC_N];
    float llr_weight[FTX_LDPC_N];

    memset(llr_acc, 0, sizeof(llr_acc));
    memset(llr_weight, 0, sizeof(llr_weight));

    const int joint_list[] = {1, 2, 3, 4};
    const float weight_list[] = {1.00f, 1.05f, 1.18f, 1.32f};
    for (int j = 0; j < 4; ++j) {
        memset(llr_tmp, 0, sizeof(llr_tmp));
        ft8_extract_likelihood_n(wf, cand, joint_list[j], llr_tmp);
        for (int i = 0; i < FTX_LDPC_N; ++i) {
            llr_acc[i] += llr_tmp[i] * weight_list[j];
            llr_weight[i] += weight_list[j];
        }
    }

    for (int i = 0; i < FTX_LDPC_N; ++i) {
        log174[i] = (llr_weight[i] > 0.0f) ? (llr_acc[i] / llr_weight[i]) : 0.0f;
    }
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

// 检查 174 bit 是否满足 LDPC 校验方程，返回未满足个数
static int ftx_ldpc_check_codeword(const uint8_t codeword[]) {
    int errors = 0;
    for (int m = 0; m < FTX_LDPC_M; ++m) {
        uint8_t x = 0;
        for (int i = 0; i < kFTX_LDPCNumRows[m]; ++i) {
            x ^= codeword[kFTX_LDPC_Nm[m][i] - 1];
        }
        if (x != 0) {
            ++errors;
        }
    }
    return errors;
}

// 轻量 OSD：在最不可靠比特上做 1/2/3 位翻转搜索
static bool ftx_osd_refine(const float *log174, uint8_t plain174[], int *errors) {
    if (errors == NULL) {
        return false;
    }

    const int max_candidates = 10;
    int idx[max_candidates];
    float reliab[max_candidates];

    for (int i = 0; i < max_candidates; ++i) {
        idx[i] = -1;
        reliab[i] = 1e30f;
    }

    for (int bit = 0; bit < FTX_LDPC_N; ++bit) {
        float r = fabsf(log174[bit]);
        for (int pos = 0; pos < max_candidates; ++pos) {
            if (r < reliab[pos]) {
                for (int sh = max_candidates - 1; sh > pos; --sh) {
                    reliab[sh] = reliab[sh - 1];
                    idx[sh] = idx[sh - 1];
                }
                reliab[pos] = r;
                idx[pos] = bit;
                break;
            }
        }
    }

    int use_count = 0;
    for (int i = 0; i < max_candidates; ++i) {
        if (idx[i] >= 0) {
            ++use_count;
        }
    }
    if (use_count == 0) {
        return false;
    }

    uint8_t base[FTX_LDPC_N];
    uint8_t trial[FTX_LDPC_N];
    uint8_t best[FTX_LDPC_N];
    memcpy(base, plain174, sizeof(base));
    memcpy(best, plain174, sizeof(best));

    int best_errors = (*errors >= 0) ? *errors : FTX_LDPC_M;

    for (int i = 0; i < use_count; ++i) {
        memcpy(trial, base, sizeof(trial));
        trial[idx[i]] ^= 1;
        int e = ftx_ldpc_check_codeword(trial);
        if (e < best_errors) {
            best_errors = e;
            memcpy(best, trial, sizeof(best));
            if (best_errors == 0) {
                memcpy(plain174, best, sizeof(best));
                *errors = 0;
                return true;
            }
        }
    }

    for (int i = 0; i < use_count; ++i) {
        for (int j = i + 1; j < use_count; ++j) {
            memcpy(trial, base, sizeof(trial));
            trial[idx[i]] ^= 1;
            trial[idx[j]] ^= 1;
            int e = ftx_ldpc_check_codeword(trial);
            if (e < best_errors) {
                best_errors = e;
                memcpy(best, trial, sizeof(best));
                if (best_errors == 0) {
                    memcpy(plain174, best, sizeof(best));
                    *errors = 0;
                    return true;
                }
            }
        }
    }

    int use3 = (use_count > 8) ? 8 : use_count;
    for (int i = 0; i < use3; ++i) {
        for (int j = i + 1; j < use3; ++j) {
            for (int k = j + 1; k < use3; ++k) {
                memcpy(trial, base, sizeof(trial));
                trial[idx[i]] ^= 1;
                trial[idx[j]] ^= 1;
                trial[idx[k]] ^= 1;
                int e = ftx_ldpc_check_codeword(trial);
                if (e < best_errors) {
                    best_errors = e;
                    memcpy(best, trial, sizeof(best));
                    if (best_errors == 0) {
                        memcpy(plain174, best, sizeof(best));
                        *errors = 0;
                        return true;
                    }
                }
            }
        }
    }

    if (best_errors < *errors) {
        memcpy(plain174, best, sizeof(best));
        *errors = best_errors;
    }
    return (best_errors == 0);
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
static void ft4_decode_multi_symbols(const uint8_t *wf, int symbol_stride, int n_syms, int bit_idx, float *log174) {
    const int n_bits = 2 * n_syms;
    const int n_tones = (1 << n_bits);
    float s2[n_tones];

    for (int j = 0; j < n_tones; ++j) {
        float sum = 0.0f;
        for (int s = 0; s < n_syms; ++s) {
            int shift = 2 * (n_syms - 1 - s);
            int bits2 = (j >> shift) & 0x03;
            int tone = kFT4GrayMap[bits2];
            sum += (float) wf[tone + s * symbol_stride];
        }
        s2[j] = sum;
    }

    for (int i = 0; i < n_bits; ++i) {
        if (bit_idx + i >= FTX_LDPC_N) {
            break;
        }

        uint16_t mask = (n_tones >> (i + 1));
        float max_zero = -1000.0f;
        float max_one = -1000.0f;
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

static void ft8_decode_multi_symbols(const uint8_t *wf, int symbol_stride, int n_syms, int bit_idx, float *log174) {
    const int n_bits = 3 * n_syms;
    const int n_tones = (1 << n_bits);
    float s2[n_tones];

    for (int j = 0; j < n_tones; ++j) {
        float sum = 0.0f;
        for (int s = 0; s < n_syms; ++s) {
            int shift = 3 * (n_syms - 1 - s);
            int bits3 = (j >> shift) & 0x07;
            int tone = kFT8GrayMap[bits3];
            sum += (float) wf[tone + s * symbol_stride];
        }
        s2[j] = sum;
    }

    for (int i = 0; i < n_bits; ++i) {
        if (bit_idx + i >= FTX_LDPC_N) {
            break;
        }

        uint16_t mask = (n_tones >> (i + 1));
        float max_zero = -1000.0f;
        float max_one = -1000.0f;
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
