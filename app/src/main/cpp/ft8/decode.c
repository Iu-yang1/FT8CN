#include "decode.h"
#include "constants.h"
#include "crc.h"
#include "ldpc.h"
#include "encode.h"
#include "pack.h"
#include "unpack.h"

#include <stdbool.h>
#include <ctype.h>
#include <float.h>
#include <math.h>
#include <stdarg.h>
#include <string.h>
#include <stdio.h>
#include "../common/debug.h"
#include "hash22.h"

/// 为后续的软判决 LDPC 解码计算 174 个消息位的对数似然对数 log(p(1) / p(0))
/// @param[in] wf 在消息时槽期间收集的瀑布数据
/// @param[in] cand 从中提取消息的候选
/// @param[in] code_map 符号编码映射
/// @param[out] log174 174 个消息位中每一个的解码对数似然输出
static void ft4_extract_likelihood(const waterfall_t *wf, const candidate_t *cand, float *log174);
static void ft8_extract_likelihood(const waterfall_t *wf, candidate_t *cand, float *log174);

/// 将位字符串打包，每个位在 bit_array[] 中表示为一个 0/非 0 字节。
/// 作为一个打包位字符串，从 packed[] 的第一个字节的最高有效位 (MSB) 开始。
/// @param[in] plain 包含 num_bits 个条目的位数组（0 和非零值）
/// @param[in] num_bits bit_array 中传入的位数（条目数）
/// @param[out] packed 表示 bit_array 中数据的字节打包位
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
static void ftx_prepare_logl(const waterfall_t *wf, candidate_t *cand, bool strong, float *log174);
static bool ftx_finalize_message(waterfall_t *wf, candidate_t *cand, const uint8_t a91[],
                                 message_t *message, decode_status_t *status);
static bool ftx_message_text_is_blank(const char *text);
static int ftx_ldpc_check_codeword(const uint8_t codeword[]);
static bool ftx_osd_refine(const float *log174, uint8_t plain174[], int *errors);
static bool ftx_try_decode_pass(const float *log174, int max_iterations, float llr_scale,
                                uint8_t plain174[], uint8_t a91[], decode_status_t *status);
static bool ftx_try_decode_pass_no_osd(const float *log174, int max_iterations, float llr_scale,
                                       uint8_t plain174[], uint8_t a91[], decode_status_t *status);
static void ftx_unpack_bits_from_bytes(const uint8_t packed[], int num_bits, uint8_t unpacked[]);
static void ftx_normalize_ap_text(const char *src, char *dst, int dst_size);
static bool ftx_build_ap_hypothesis(ftx_protocol_t protocol, const char *text,
                                    uint8_t a91[], uint8_t codeword174[]);
static bool ftx_crc_matches_a91(const uint8_t a91[], uint16_t *crc_extracted, uint16_t *crc_calculated);
static float ftx_score_codeword_match(const float *log174, const uint8_t codeword174[]);
static bool ftx_consider_crc_guided_trial(const float *log174, const uint8_t info_bits[],
                                          float *best_score, bool *found,
                                          uint8_t best_plain174[], uint8_t best_a91[],
                                          uint16_t *best_crc_extracted,
                                          uint16_t *best_crc_calculated);
static bool ftx_crc_guided_refine(const float *log174, const uint8_t plain174[],
                                  uint8_t refined_plain174[], uint8_t refined_a91[],
                                  decode_status_t *status);
static void ftx_apply_ap_prior(float *log174, const uint8_t codeword174[], float prior_strength);
static float ftx_score_ap_match(const float *log174, const uint8_t codeword174[]);
static bool ftx_try_ap_codeword(const float *log174, const uint8_t hypothesisA91[],
                                const uint8_t hypothesisCodeword174[], int max_iterations,
                                uint8_t plain174[], uint8_t a91[], decode_status_t *status);
static bool ftx_try_ap_text_list(const float *log174, ftx_protocol_t protocol,
                                 const char *const texts[], int text_count,
                                 int max_iterations, uint8_t plain174[], uint8_t a91[],
                                 decode_status_t *status);
static bool ftx_try_ap_decode(const float *log174, ftx_protocol_t protocol, const ap_hints_t *ap_hints,
                              int max_iterations, uint8_t plain174[], uint8_t a91[],
                              decode_status_t *status);

typedef struct
{
    float evidence;
    uint8_t a91[FTX_LDPC_K_BYTES];
    uint8_t codeword174[FTX_LDPC_N];
} ap_trial_candidate_t;

static const float kApPriorStrength = 0.75f;
static const float kApMinEvidence = 1.15f;
static const float kApMinMargin = 0.18f;
enum {
    kApMaxDecodeTrials = 3,
    kApTextBufferSize = 80,
    kApGeneratedTextLimit = FTX_AP_MAX_HINT_CALLS * 210,
    kCrcGuidedWeakBitCount = 10
};
// AP 先按 soft evidence 排序，只对前几条候选做硬解，因此可以承受更宽的假设集。
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
 * 2. 增加“目标 bin 与其它 bin 平均差值”的弱信号增益项
 * 3. 对后续 sync block 的边界判断统一修正
 *
 * 这样会比原来稍微灵敏一些，但不至于把噪声候选放大得太夸张。
 */
static int ft8_sync_score(const waterfall_t *wf, candidate_t *candidate) {
    if (wf == NULL || candidate == NULL || wf->mag2 == NULL) {
        return 0;
    }

    const float *mag_cand_linear = wf->mag2 + get_index(wf, candidate);
    float target_abc = 0.0f;
    float other_abc = 0.0f;
    int count_abc = 0;
    float target_bc = 0.0f;
    float other_bc = 0.0f;
    int count_bc = 0;

    for (int m = 0; m < FT8_NUM_SYNC; ++m) {
        for (int k = 0; k < FT8_LENGTH_SYNC; ++k) {
            int block = (FT8_SYNC_OFFSET * m) + k;
            int block_abs = candidate->time_offset + block;

            if (block_abs < 0) {
                continue;
            }
            if (block_abs >= wf->num_blocks) {
                break;
            }

            const float *p8 = mag_cand_linear + (block * wf->block_stride);
            int sm = kFT8CostasPattern[k];
            float others = 0.0f;
            for (int n = 0; n < FT8_LENGTH_SYNC; ++n) {
                if (n != sm) {
                    others += p8[n];
                }
            }

            target_abc += p8[sm];
            other_abc += others;
            ++count_abc;

            if (m > 0) {
                target_bc += p8[sm];
                other_bc += others;
                ++count_bc;
            }
        }
    }

    float best_ratio = 0.0f;
    if (count_abc > 0 && other_abc > 0.0f) {
        best_ratio = target_abc / (other_abc / 6.0f);
    }
    if (count_bc > 0 && other_bc > 0.0f) {
        float ratio_bc = target_bc / (other_bc / 6.0f);
        if (ratio_bc > best_ratio) {
            best_ratio = ratio_bc;
        }
    }

    if (!isfinite(best_ratio) || best_ratio <= 1.0f) {
        best_ratio = 1.0f;
    }
    if (best_ratio > 8.0f) {
        best_ratio = 8.0f;
    }

    // 直接返回 Costas 比例分数，保持与现有 min_score=10 大致兼容。
    return (int) lroundf(best_ratio * 10.0f);

    const int ratio_score = (int) ((best_ratio - 1.0f) * 20.0f + 0.5f);
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

            // 增加一个“目标 bin 相对其它 7 个 bin 的平均优势”。
            // 这个项对弱信号更友好一些。
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

    const int ratio_bonus = ratio_score > score ? ratio_score - score : 0;
    return score + ((ratio_bonus > 4) ? 4 : ratio_bonus);
}

/**
 * FT4 同步评分
 *
 * 改动点：
 * 1. 保留原来的频率/时间邻居差分
 * 2. 增加“目标 bin 与其余 3 个 bin 平均差值”的增强项
 * 3. FT4 本来同步符号更短、更密，适当增强这一项有助于弱信号候选进入后续 LDPC
 */
static int ft8_sync_score_stable(const waterfall_t *wf, candidate_t *candidate) {
    if (wf == NULL || candidate == NULL || wf->mag2 == NULL) {
        return 0;
    }

    const float *mag_cand_linear = wf->mag2 + get_index(wf, candidate);
    float target_abc = 0.0f;
    float other_abc = 0.0f;
    int count_abc = 0;
    float target_bc = 0.0f;
    float other_bc = 0.0f;
    int count_bc = 0;

    for (int m = 0; m < FT8_NUM_SYNC; ++m) {
        for (int k = 0; k < FT8_LENGTH_SYNC; ++k) {
            const int block = (FT8_SYNC_OFFSET * m) + k;
            const int block_abs = candidate->time_offset + block;

            if (block_abs < 0) {
                continue;
            }
            if (block_abs >= wf->num_blocks) {
                break;
            }

            const float *p8 = mag_cand_linear + (block * wf->block_stride);
            const int sm = kFT8CostasPattern[k];
            float others = 0.0f;
            for (int n = 0; n < FT8_LENGTH_SYNC; ++n) {
                if (n != sm) {
                    others += p8[n];
                }
            }

            target_abc += p8[sm];
            other_abc += others;
            ++count_abc;

            if (m > 0) {
                target_bc += p8[sm];
                other_bc += others;
                ++count_bc;
            }
        }
    }

    float best_ratio = 0.0f;
    if (count_abc > 0 && other_abc > 0.0f) {
        best_ratio = target_abc / (other_abc / 6.0f);
    }
    if (count_bc > 0 && other_bc > 0.0f) {
        const float ratio_bc = target_bc / (other_bc / 6.0f);
        if (ratio_bc > best_ratio) {
            best_ratio = ratio_bc;
        }
    }

    if (!isfinite(best_ratio) || best_ratio <= 1.0f) {
        best_ratio = 1.0f;
    }
    if (best_ratio > 8.0f) {
        best_ratio = 8.0f;
    }

    const int ratio_score = (int) ((best_ratio - 1.0f) * 20.0f + 0.5f);
    int score = 0;
    int num_average = 0;

    const uint8_t *mag_cand = wf->mag + get_index(wf, candidate);
    for (int m = 0; m < FT8_NUM_SYNC; ++m) {
        for (int k = 0; k < FT8_LENGTH_SYNC; ++k) {
            const int block = (FT8_SYNC_OFFSET * m) + k;
            const int block_abs = candidate->time_offset + block;

            if (block_abs < 0) {
                continue;
            }
            if (block_abs >= wf->num_blocks) {
                break;
            }

            const uint8_t *p8 = mag_cand + (block * wf->block_stride);
            const int sm = kFT8CostasPattern[k];

            if (sm > 0) {
                score += (int) p8[sm] - (int) p8[sm - 1];
                ++num_average;
            }
            if (sm < 7) {
                score += (int) p8[sm] - (int) p8[sm + 1];
                ++num_average;
            }
            if ((k > 0) && (block_abs > 0)) {
                score += (int) p8[sm] - (int) p8[sm - wf->block_stride];
                ++num_average;
            }
            if (((k + 1) < FT8_LENGTH_SYNC) && ((block_abs + 1) < wf->num_blocks)) {
                score += (int) p8[sm] - (int) p8[sm + wf->block_stride];
                ++num_average;
            }

            int others = 0;
            for (int n = 0; n < 8; ++n) {
                if (n != sm) {
                    others += p8[n];
                }
            }
            score += ((int) p8[sm] * 7 - others) / 4;
            ++num_average;
        }
    }

    if (num_average > 0) {
        score /= num_average;
    }

    const int ratio_bonus = ratio_score > score ? ratio_score - score : 0;
    return score + ((ratio_bonus > 4) ? 4 : ratio_bonus);
}

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
                        candidate.score = ft8_sync_score_stable(wf, &candidate);
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

    // 避免极弱信号/纯噪声下方差过小导致归一化爆炸
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

static void ftx_prepare_logl(const waterfall_t *wf, candidate_t *cand, bool strong, float *log174) {
    if (wf->protocol == PROTO_FT4) {
        if (strong) {
            ft4_extract_likelihood_strong(wf, cand, log174);
        } else {
            ft4_extract_likelihood(wf, cand, log174);
        }
    } else {
        if (strong) {
            ft8_extract_likelihood_strong(wf, cand, log174);
        } else {
            ft8_extract_likelihood(wf, cand, log174);
        }
    }
    ftx_normalize_logl(log174);
}

static bool ftx_message_text_is_blank(const char *text) {
    if (text == NULL) {
        return true;
    }
    while (*text != '\0') {
        if (!isspace((unsigned char) *text)) {
            return false;
        }
        ++text;
    }
    return true;
}

static bool ftx_finalize_message(waterfall_t *wf, candidate_t *cand, const uint8_t a91[],
                                 message_t *message, decode_status_t *status) {
    uint8_t payload[FTX_LDPC_K_BYTES];
    memcpy(payload, a91, sizeof(payload));

    if (wf->protocol == PROTO_FT4) {
        // Undo FT4 whitening before unpacking the final message fields.
        for (int i = 0; i < 10; ++i) {
            payload[i] ^= kFT4XORSequence[i];
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
    memcpy(message->a91, payload, FTX_LDPC_K_BYTES);
    status->unpack_status = unpackToMessage_t(payload, message);

    if (status->unpack_status >= 0 && ftx_message_text_is_blank(message->text)) {
        // CRC 已经通过但文本为空通常来自空自由文本/损坏候选；不要向 UI 返回空白消息。
        return false;
    }

    if (status->unpack_status < 0) {
        // Keep a placeholder text when the payload decodes but this message type is unsupported.
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
    ftx_guess_snr(wf, cand);
    return true;
}

// max_iterations=20 means the basic LDPC iteration budget.
bool
ft8_decode(waterfall_t *wf, candidate_t *cand, message_t *message, int max_iterations,
           const ap_hints_t *ap_hints, decode_status_t *status) {
    status->ldpc_errors = FTX_LDPC_M;
    status->crc_extracted = 0;
    status->crc_calculated = 0;
    status->unpack_status = -1;

    float log174[FTX_LDPC_N];
    ftx_prepare_logl(wf, cand, false, log174);
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

        ftx_prepare_logl(wf, cand, true, retry_log174);
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

    return ftx_finalize_message(wf, cand, a91, message, status);
}

bool ft8_decode_with_ap_texts(waterfall_t *wf, candidate_t *cand,
                              const char *const ap_texts[], int ap_text_count,
                              int max_iterations, message_t *message, decode_status_t *status) {
    status->ldpc_errors = FTX_LDPC_M;
    status->crc_extracted = 0;
    status->crc_calculated = 0;
    status->unpack_status = -1;

    if (ap_texts == NULL || ap_text_count <= 0) {
        return false;
    }

    float log174[FTX_LDPC_N];
    ftx_prepare_logl(wf, cand, false, log174);

    uint8_t plain174[FTX_LDPC_N];
    uint8_t a91[FTX_LDPC_K_BYTES];
    decode_status_t ap_status = *status;
    bool crc_ok = ftx_try_ap_text_list(log174, wf->protocol, ap_texts, ap_text_count,
                                       max_iterations, plain174, a91, &ap_status);

    if (!crc_ok && max_iterations >= 100) {
        float strong_log174[FTX_LDPC_N];
        ftx_prepare_logl(wf, cand, true, strong_log174);
        ap_status = *status;
        crc_ok = ftx_try_ap_text_list(strong_log174, wf->protocol, ap_texts, ap_text_count,
                                      max_iterations, plain174, a91, &ap_status);
    }

    if (!crc_ok) {
        return false;
    }

    *status = ap_status;
    return ftx_finalize_message(wf, cand, a91, message, status);
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
    if (ftx_crc_matches_a91(a91, &status->crc_extracted, &status->crc_calculated)) {
        return true;
    }

    if (status->ldpc_errors <= 3 &&
        ftx_crc_guided_refine(work_log174, plain174, plain174, a91, status)) {
        return true;
    }

    return false;
}

static bool ftx_try_decode_pass_no_osd(const float *log174, int max_iterations, float llr_scale,
                                       uint8_t plain174[], uint8_t a91[], decode_status_t *status) {
    float work_log174[FTX_LDPC_N];
    memcpy(work_log174, log174, sizeof(work_log174));

    if (llr_scale > 0.0f && fabsf(llr_scale - 1.0f) > 1e-6f) {
        for (int i = 0; i < FTX_LDPC_N; ++i) {
            work_log174[i] *= llr_scale;
        }
    }

    bp_decode(work_log174, max_iterations, plain174, &status->ldpc_errors);
    if (status->ldpc_errors > 0) {
        ldpc_decode(work_log174, max_iterations, plain174, &status->ldpc_errors);
    }
    if (status->ldpc_errors > 0) {
        return false;
    }

    pack_bits(plain174, FTX_LDPC_K, a91);
    if (ftx_crc_matches_a91(a91, &status->crc_extracted, &status->crc_calculated)) {
        return true;
    }

    if (ftx_crc_guided_refine(work_log174, plain174, plain174, a91, status)) {
        return true;
    }

    return false;
}

static void ftx_unpack_bits_from_bytes(const uint8_t packed[], int num_bits, uint8_t unpacked[]) {
    for (int i = 0; i < num_bits; ++i) {
        int byteIndex = i / 8;
        int bitIndex = 7 - (i % 8);
        unpacked[i] = (uint8_t) ((packed[byteIndex] >> bitIndex) & 0x01u);
    }
    // AP-lite expands the packed 174-bit codeword so soft-prior injection and evidence scoring can share it.
}

static bool ftx_crc_matches_a91(const uint8_t a91[], uint16_t *crc_extracted, uint16_t *crc_calculated) {
    if (a91 == NULL) {
        return false;
    }

    uint16_t extracted = ftx_extract_crc(a91);
    uint8_t crc_buf[FTX_LDPC_K_BYTES];
    memcpy(crc_buf, a91, sizeof(crc_buf));
    crc_buf[9] &= 0xF8;
    crc_buf[10] &= 0x00;
    uint16_t calculated = ftx_compute_crc(crc_buf, 96 - 14);

    if (crc_extracted != NULL) {
        *crc_extracted = extracted;
    }
    if (crc_calculated != NULL) {
        *crc_calculated = calculated;
    }

    return extracted == calculated;
}

static float ftx_score_codeword_match(const float *log174, const uint8_t codeword174[]) {
    float score = 0.0f;

    for (int i = 0; i < FTX_LDPC_N; ++i) {
        score += codeword174[i] ? log174[i] : -log174[i];
    }

    return score;
}

static bool ftx_consider_crc_guided_trial(const float *log174, const uint8_t info_bits[],
                                          float *best_score, bool *found,
                                          uint8_t best_plain174[], uint8_t best_a91[],
                                          uint16_t *best_crc_extracted,
                                          uint16_t *best_crc_calculated) {
    if (log174 == NULL || info_bits == NULL || best_score == NULL || found == NULL ||
        best_plain174 == NULL || best_a91 == NULL) {
        return false;
    }

    uint8_t trial_a91[FTX_LDPC_K_BYTES];
    memset(trial_a91, 0, sizeof(trial_a91));
    pack_bits(info_bits, FTX_LDPC_K, trial_a91);

    uint16_t crc_extracted = 0;
    uint16_t crc_calculated = 0;
    if (!ftx_crc_matches_a91(trial_a91, &crc_extracted, &crc_calculated)) {
        return false;
    }

    uint8_t codeword_bytes[FTX_LDPC_N_BYTES];
    uint8_t trial_plain174[FTX_LDPC_N];
    ftx_encode_174(trial_a91, codeword_bytes);
    ftx_unpack_bits_from_bytes(codeword_bytes, FTX_LDPC_N, trial_plain174);

    const float score = ftx_score_codeword_match(log174, trial_plain174);
    if (*found && score <= *best_score) {
        return true;
    }

    *best_score = score;
    *found = true;
    memcpy(best_plain174, trial_plain174, FTX_LDPC_N);
    memcpy(best_a91, trial_a91, FTX_LDPC_K_BYTES);
    if (best_crc_extracted != NULL) {
        *best_crc_extracted = crc_extracted;
    }
    if (best_crc_calculated != NULL) {
        *best_crc_calculated = crc_calculated;
    }
    return true;
}

static bool ftx_crc_guided_refine(const float *log174, const uint8_t plain174[],
                                  uint8_t refined_plain174[], uint8_t refined_a91[],
                                  decode_status_t *status) {
    if (log174 == NULL || plain174 == NULL || refined_plain174 == NULL || refined_a91 == NULL ||
        status == NULL) {
        return false;
    }

    int weakest_idx[kCrcGuidedWeakBitCount];
    float weakest_reliab[kCrcGuidedWeakBitCount];
    for (int i = 0; i < kCrcGuidedWeakBitCount; ++i) {
        weakest_idx[i] = -1;
        weakest_reliab[i] = FLT_MAX;
    }

    for (int bit = 0; bit < FTX_LDPC_K; ++bit) {
        const float reliab = fabsf(log174[bit]);
        for (int pos = 0; pos < kCrcGuidedWeakBitCount; ++pos) {
            if (reliab < weakest_reliab[pos]) {
                for (int sh = kCrcGuidedWeakBitCount - 1; sh > pos; --sh) {
                    weakest_reliab[sh] = weakest_reliab[sh - 1];
                    weakest_idx[sh] = weakest_idx[sh - 1];
                }
                weakest_reliab[pos] = reliab;
                weakest_idx[pos] = bit;
                break;
            }
        }
    }

    int use_count = 0;
    while (use_count < kCrcGuidedWeakBitCount && weakest_idx[use_count] >= 0) {
        ++use_count;
    }
    if (use_count <= 0) {
        return false;
    }

    uint8_t base_info_bits[FTX_LDPC_K];
    uint8_t trial_info_bits[FTX_LDPC_K];
    for (int i = 0; i < FTX_LDPC_K; ++i) {
        base_info_bits[i] = plain174[i];
    }

    uint8_t best_plain174[FTX_LDPC_N];
    uint8_t best_a91[FTX_LDPC_K_BYTES];
    float best_score = -FLT_MAX;
    uint16_t best_crc_extracted = 0;
    uint16_t best_crc_calculated = 0;
    bool found = false;

    for (int i = 0; i < use_count; ++i) {
        memcpy(trial_info_bits, base_info_bits, sizeof(base_info_bits));
        trial_info_bits[weakest_idx[i]] ^= 1;
        ftx_consider_crc_guided_trial(log174, trial_info_bits, &best_score, &found,
                                      best_plain174, best_a91,
                                      &best_crc_extracted, &best_crc_calculated);
    }

    for (int i = 0; i < use_count; ++i) {
        for (int j = i + 1; j < use_count; ++j) {
            memcpy(trial_info_bits, base_info_bits, sizeof(base_info_bits));
            trial_info_bits[weakest_idx[i]] ^= 1;
            trial_info_bits[weakest_idx[j]] ^= 1;
            ftx_consider_crc_guided_trial(log174, trial_info_bits, &best_score, &found,
                                          best_plain174, best_a91,
                                          &best_crc_extracted, &best_crc_calculated);
        }
    }

    const int use3 = (use_count > 6) ? 6 : use_count;
    for (int i = 0; i < use3; ++i) {
        for (int j = i + 1; j < use3; ++j) {
            for (int k = j + 1; k < use3; ++k) {
                memcpy(trial_info_bits, base_info_bits, sizeof(base_info_bits));
                trial_info_bits[weakest_idx[i]] ^= 1;
                trial_info_bits[weakest_idx[j]] ^= 1;
                trial_info_bits[weakest_idx[k]] ^= 1;
                ftx_consider_crc_guided_trial(log174, trial_info_bits, &best_score, &found,
                                              best_plain174, best_a91,
                                              &best_crc_extracted, &best_crc_calculated);
            }
        }
    }

    if (!found) {
        return false;
    }

    memcpy(refined_plain174, best_plain174, FTX_LDPC_N);
    memcpy(refined_a91, best_a91, FTX_LDPC_K_BYTES);
    status->ldpc_errors = 0;
    status->crc_extracted = best_crc_extracted;
    status->crc_calculated = best_crc_calculated;
    return true;
}

static void ftx_normalize_ap_text(const char *src, char *dst, int dst_size) {
    if (dst == NULL || dst_size <= 0) {
        return;
    }

    int out = 0;
    bool pending_space = false;
    while (src != NULL && *src != '\0' && out < dst_size - 1) {
        unsigned char ch = (unsigned char) *src++;
        if (isspace(ch)) {
            pending_space = (out > 0);
            continue;
        }

        if (pending_space && out < dst_size - 1) {
            dst[out++] = ' ';
            pending_space = false;
        }

        if (ch >= 'a' && ch <= 'z') {
            ch = (unsigned char) (ch - ('a' - 'A'));
        }
        dst[out++] = (char) ch;
    }

    dst[out] = '\0';
}

static void ftx_append_ap_text(char text_storage[][kApTextBufferSize], const char *text_ptrs[],
                               int max_texts, int *text_count, const char *format, ...) {
    if (text_storage == NULL || text_ptrs == NULL || text_count == NULL || *text_count >= max_texts) {
        return;
    }

    va_list args;
    va_start(args, format);
    vsnprintf(text_storage[*text_count], kApTextBufferSize, format, args);
    va_end(args);

    text_ptrs[*text_count] = text_storage[*text_count];
    ++(*text_count);
}

static bool ftx_build_ap_hypothesis(ftx_protocol_t protocol, const char *text,
                                    uint8_t a91[], uint8_t codeword174[]) {
    uint8_t payload[10];
    if (pack77(text, payload) != 0) {
        return false;
    }

    message_t roundtrip;
    memset(&roundtrip, 0, sizeof(roundtrip));
    if (unpackToMessage_t(payload, &roundtrip) < 0) {
        return false;
    }
    if (roundtrip.i3 == 0 && roundtrip.n3 == 0) {
        // Reject silent pack77() free-text fallback; AP must target a structured on-air message.
        return false;
    }

    char normalized_input[kApTextBufferSize];
    char normalized_roundtrip[kApTextBufferSize];
    ftx_normalize_ap_text(text, normalized_input, sizeof(normalized_input));
    ftx_normalize_ap_text(roundtrip.text, normalized_roundtrip, sizeof(normalized_roundtrip));
    if (strcmp(normalized_input, normalized_roundtrip) != 0) {
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

static bool ftx_try_ap_codeword(const float *log174, const uint8_t hypothesisA91[],
                                const uint8_t hypothesisCodeword174[], int max_iterations,
                                uint8_t plain174[], uint8_t a91[], decode_status_t *status) {
    float apLog174[FTX_LDPC_N];
    memcpy(apLog174, log174, sizeof(apLog174));
    ftx_apply_ap_prior(apLog174, hypothesisCodeword174, kApPriorStrength);

    uint8_t apPlain174[FTX_LDPC_N];
    uint8_t apA91[FTX_LDPC_K_BYTES];
    decode_status_t apStatus = *status;

    int apIterations = max_iterations;
    if (apIterations > 120) {
        apIterations = 120;
    }

    if (!ftx_try_decode_pass_no_osd(apLog174, apIterations, 1.0f, apPlain174, apA91, &apStatus)) {
        return false;
    }

    if (memcmp(apA91, hypothesisA91, FTX_LDPC_K_BYTES) != 0) {
        return false;
    }
    // Even after AP assistance, LDPC and CRC must land on the exact same hypothesis message.

    memcpy(plain174, apPlain174, sizeof(apPlain174));
    memcpy(a91, apA91, sizeof(apA91));
    *status = apStatus;
    return true;
}

static void ftx_insert_ap_trial(ap_trial_candidate_t best_trials[], int *trial_count,
                                float evidence, const uint8_t a91[], const uint8_t codeword174[]) {
    if (best_trials == NULL || trial_count == NULL) {
        return;
    }

    if (*trial_count < kApMaxDecodeTrials) {
        best_trials[*trial_count].evidence = evidence;
        memcpy(best_trials[*trial_count].a91, a91, FTX_LDPC_K_BYTES);
        memcpy(best_trials[*trial_count].codeword174, codeword174, FTX_LDPC_N);
        ++(*trial_count);
    } else if (evidence > best_trials[*trial_count - 1].evidence) {
        best_trials[*trial_count - 1].evidence = evidence;
        memcpy(best_trials[*trial_count - 1].a91, a91, FTX_LDPC_K_BYTES);
        memcpy(best_trials[*trial_count - 1].codeword174, codeword174, FTX_LDPC_N);
    } else {
        return;
    }

    for (int pos = *trial_count - 1; pos > 0; --pos) {
        const ap_trial_candidate_t *lhs = &best_trials[pos - 1];
        const ap_trial_candidate_t *rhs = &best_trials[pos];
        if (lhs->evidence >= rhs->evidence) {
            break;
        }

        ap_trial_candidate_t tmp = best_trials[pos - 1];
        best_trials[pos - 1] = best_trials[pos];
        best_trials[pos] = tmp;
    }
}

static bool ftx_try_ap_text_list(const float *log174, ftx_protocol_t protocol,
                                 const char *const texts[], int text_count,
                                 int max_iterations, uint8_t plain174[], uint8_t a91[],
                                 decode_status_t *status) {
    if (texts == NULL || text_count <= 0) {
        return false;
    }

    ap_trial_candidate_t best_trials[kApMaxDecodeTrials];
    int trial_count = 0;

    for (int idx = 0; idx < text_count; ++idx) {
        const char *text = texts[idx];
        if (text == NULL || text[0] == '\0') {
            continue;
        }

        uint8_t hypothesisA91[FTX_LDPC_K_BYTES];
        uint8_t hypothesisCodeword174[FTX_LDPC_N];
        if (!ftx_build_ap_hypothesis(protocol, text, hypothesisA91, hypothesisCodeword174)) {
            continue;
        }

        const float evidence = ftx_score_ap_match(log174, hypothesisCodeword174);
        if (evidence < kApMinEvidence) {
            continue;
        }
        ftx_insert_ap_trial(best_trials, &trial_count, evidence, hypothesisA91, hypothesisCodeword174);
    }

    if (trial_count <= 0) {
        return false;
    }

    uint8_t bestPlain174[FTX_LDPC_N];
    uint8_t bestA91[FTX_LDPC_K_BYTES];
    decode_status_t bestStatus = *status;
    float bestEvidence = -FLT_MAX;
    float secondBestEvidence = -FLT_MAX;
    bool found = false;

    for (int idx = 0; idx < trial_count; ++idx) {
        uint8_t trialPlain174[FTX_LDPC_N];
        uint8_t trialA91[FTX_LDPC_K_BYTES];
        decode_status_t trialStatus = *status;

        if (!ftx_try_ap_codeword(log174,
                                 best_trials[idx].a91,
                                 best_trials[idx].codeword174,
                                 max_iterations,
                                 trialPlain174,
                                 trialA91,
                                 &trialStatus)) {
            continue;
        }

        if (best_trials[idx].evidence > bestEvidence) {
            secondBestEvidence = bestEvidence;
            bestEvidence = best_trials[idx].evidence;
            memcpy(bestPlain174, trialPlain174, sizeof(bestPlain174));
            memcpy(bestA91, trialA91, sizeof(bestA91));
            bestStatus = trialStatus;
            found = true;
        } else if (best_trials[idx].evidence > secondBestEvidence) {
            secondBestEvidence = best_trials[idx].evidence;
        }
    }

    if (!found || bestEvidence < kApMinEvidence) {
        return false;
    }

    if (secondBestEvidence > -FLT_MAX / 2.0f &&
        (bestEvidence - secondBestEvidence) < kApMinMargin) {
        return false;
    }

    memcpy(plain174, bestPlain174, sizeof(bestPlain174));
    memcpy(a91, bestA91, sizeof(bestA91));
    *status = bestStatus;
    return true;
}

static bool ftx_try_ap_decode(const float *log174, ftx_protocol_t protocol, const ap_hints_t *ap_hints,
                              int max_iterations, uint8_t plain174[], uint8_t a91[],
                              decode_status_t *status) {
    if (ap_hints == NULL || ap_hints->my_call[0] == '\0' || ap_hints->hint_call_count <= 0) {
        return false;
    }

    char text_storage[kApGeneratedTextLimit][kApTextBufferSize];
    const char *text_ptrs[kApGeneratedTextLimit];
    int text_count = 0;

    for (int i = 0; i < ap_hints->hint_call_count; ++i) {
        const char *otherCall = ap_hints->hint_calls[i];
        const char *otherGrid = ap_hints->hint_grids[i];

        if (otherCall[0] == '\0') {
            continue;
        }

        ftx_append_ap_text(text_storage, text_ptrs, kApGeneratedTextLimit, &text_count,
                           "%s %s", ap_hints->my_call, otherCall);
        ftx_append_ap_text(text_storage, text_ptrs, kApGeneratedTextLimit, &text_count,
                           "%s %s", otherCall, ap_hints->my_call);

        if (otherGrid[0] != '\0') {
            ftx_append_ap_text(text_storage, text_ptrs, kApGeneratedTextLimit, &text_count,
                               "%s %s %s", ap_hints->my_call, otherCall, otherGrid);
            // If the peer grid is known, try the most common standard first-reply shape: MYCALL DXCALL GRID.
        }

        const char *ackList[] = {"RRR", "RR73", "73"};
        for (int ackIdx = 0; ackIdx < 3; ++ackIdx) {
            for (int order = 0; order < 2; ++order) {
                const char *callA = (order == 0) ? ap_hints->my_call : otherCall;
                const char *callB = (order == 0) ? otherCall : ap_hints->my_call;
                ftx_append_ap_text(text_storage, text_ptrs, kApGeneratedTextLimit, &text_count,
                                   "%s %s %s", callA, callB, ackList[ackIdx]);
            }
        }

        for (int reportValue = -50; reportValue <= 49; ++reportValue) {
            char reportText[6];
            char replyReportText[7];
            snprintf(reportText, sizeof(reportText), "%+03d", reportValue);
            snprintf(replyReportText, sizeof(replyReportText), "R%+03d", reportValue);

            for (int order = 0; order < 2; ++order) {
                const char *callA = (order == 0) ? ap_hints->my_call : otherCall;
                const char *callB = (order == 0) ? otherCall : ap_hints->my_call;
                ftx_append_ap_text(text_storage, text_ptrs, kApGeneratedTextLimit, &text_count,
                                   "%s %s %s", callA, callB, reportText);
                ftx_append_ap_text(text_storage, text_ptrs, kApGeneratedTextLimit, &text_count,
                                   "%s %s %s", callA, callB, replyReportText);
            }
        }
    }

    if (text_count <= 0) {
        return false;
    }

    return ftx_try_ap_text_list(log174, protocol, text_ptrs, text_count,
                                max_iterations, plain174, a91, status);
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

