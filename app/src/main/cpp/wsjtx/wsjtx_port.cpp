#include "wsjtx_port.h"
#include "session_plan.h"

#include <android/log.h>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>

extern "C" {
#include "../ft8/encode.h"
}

namespace {

constexpr const char *kWsjtLogTag = "FT8CN-WSJTX";

constexpr int kFt8PhaseTicksFull = 50;
constexpr int64_t kFt8SlotMilliseconds = 15000;
constexpr int64_t kFt4SlotMilliseconds = 7500;
constexpr int kFt8FollowupMinSyncScore = 6;
constexpr int kFt4FollowupMinSyncScore = 7;
constexpr int kFt8FollowupMaxEntries = 16;
constexpr int kFt8FollowupMaxCandidatesPerEntry = 4;
constexpr int kApPassMaxTexts = 96;
constexpr int kApPassHistoryEntries = 6;
constexpr int kSubtractHistoryBudget = 48;
constexpr int kFollowupModeCount = 2;
constexpr float kFt8FollowupHistoryMatchHz = 3.0f;
constexpr float kFt8FollowupHistoryMatchTimeSec = 0.30f;
constexpr float kDecodeDuplicateFrequencyToleranceHz = 20.0f;

constexpr int kFt4CoarseFftSize = 2304;
constexpr int kFt4CoarseHopSize = 576;
constexpr int kFt4MaxWaveSamples = 21 * 3456;
constexpr int kFt4BaselineSegments = 10;
constexpr float kFt4PeakPercentile = 0.10f;
constexpr float kFt4PeakOffsetHz = -1.5f * 12000.0f / 576.0f;

struct subtract_job_t {
    uint8_t a91[FTX_LDPC_K_BYTES];
    float frequency;
    float time_sec;
};

struct ft4_peak_t {
    float frequency;
    float strength;
};

struct ft8_followup_entry_t {
    char call_1[FTX_AP_CALLSIGN_MAX];
    char call_2[FTX_AP_CALLSIGN_MAX];
    char grid4[5];
    float frequency;
    float time_sec;
};

struct followup_search_policy_t {
    float freq_window_hz;
    float time_window_sec;
    int max_candidates;
};

struct pass_decode_stats_t {
    int decode_success_count;
    int ap_success_count;
    int duplicate_count;
};

struct wsjtx_port_decoder_t {
    int64_t utc_time;
    int slot_samples;
    int num_samples;
    int num_candidates;
    int num_decoded;
    int ldpc_iterations;
    candidate_t candidate_list[kMax_candidates];
    message_t decoded[kMax_decoded_messages];
    message_t *decoded_hashtable[kMax_decoded_messages];
    monitor_t mon;
    monitor_config_t mon_cfg;
    uint8_t current_a91[FTX_LDPC_K_BYTES];
    ap_hints_t ap_hints;
    std::vector<float> raw_samples;
    std::vector<ft8_message> session_results;
    wsjtx::DecoderOptions options;
};

std::mutex g_ft8_followup_mutex;
std::array<std::vector<ft8_followup_entry_t>, kFollowupModeCount> g_ft8_followup_current_slot;
std::array<std::vector<ft8_followup_entry_t>, kFollowupModeCount> g_ft8_followup_previous_slot;
std::array<int64_t, kFollowupModeCount> g_ft8_followup_slot_ids = {{-1, -1}};

static wsjtx::DecoderOptions convert_decoder_options(const wsjtx_decoder_options_t *options) {
    wsjtx::DecoderOptions converted = wsjtx::DefaultDecoderOptions();
    if (options == nullptr) {
        return converted;
    }

    converted.decode_pass_count = options->decode_pass_count;
    converted.multi_decode_round_count = options->multi_decode_round_count;
    converted.qso_freq_sensitivity = options->qso_freq_sensitivity;
    converted.decode_sensitivity = options->decode_sensitivity;
    converted.enable_early_decode = options->enable_early_decode;
    converted.enable_wideband_dx_search = options->enable_wideband_dx_search;
    return converted;
}

static wsjtx_port_decoder_t *get_state(decoder_t *decoder) {
    return (decoder == nullptr) ? nullptr : static_cast<wsjtx_port_decoder_t *>(decoder->backend_state);
}

static bool state_is_ft4(const wsjtx_port_decoder_t *state) {
    return state->mon_cfg.protocol == PROTO_FT4;
}

static bool state_is_ft8(const wsjtx_port_decoder_t *state) {
    return state->mon_cfg.protocol == PROTO_FT8;
}

static bool state_owns_session_flow(const wsjtx_port_decoder_t *state) {
    return wsjtx::ResolveModeDescriptor(state->mon_cfg.protocol).owns_session_flow;
}

static int base_min_sync_score(const wsjtx_port_decoder_t *state) {
    return state_is_ft4(state) ? 8 : kMin_score;
}

static void reset_dedupe_state(wsjtx_port_decoder_t *state) {
    state->num_decoded = 0;
    for (int i = 0; i < kMax_decoded_messages; ++i) {
        state->decoded_hashtable[i] = nullptr;
    }
}

static inline void set_mag_to_zero(wsjtx_port_decoder_t *state, int index, int max_block_size) {
    if (index > 0 && index < max_block_size) {
        state->mon.wf.mag[index] = 0;
        if (state->mon.wf.mag2 != nullptr) {
            state->mon.wf.mag2[index] = 0.0f;
        }
    }
}

static float candidate_freq_hz(const wsjtx_port_decoder_t *state, const candidate_t &candidate) {
    return (candidate.freq_offset + (float) candidate.freq_sub / state->mon.wf.freq_osr) /
           state->mon.symbol_period;
}

static float candidate_time_sec(const wsjtx_port_decoder_t *state, const candidate_t &candidate) {
    return ((candidate.time_offset + (float) candidate.time_sub) * state->mon.symbol_period) /
           state->mon.wf.time_osr;
}

static bool is_duplicate_result(const wsjtx_port_decoder_t *state, const ft8_message &candidate) {
    for (const ft8_message &existing : state->session_results) {
        if (!existing.isValid) {
            continue;
        }
        if (existing.message.hash != candidate.message.hash ||
            0 != std::strcmp(existing.message.text, candidate.message.text)) {
            continue;
        }
        if (existing.freq_hz <= 0.0f || candidate.freq_hz <= 0.0f ||
            std::fabs(existing.freq_hz - candidate.freq_hz) <= kDecodeDuplicateFrequencyToleranceHz) {
            return true;
        }
    }
    return false;
}

static int find_duplicate_result_index(const wsjtx_port_decoder_t *state, const ft8_message &candidate) {
    if (state == nullptr) {
        return -1;
    }

    for (int idx = 0; idx < (int) state->session_results.size(); ++idx) {
        const ft8_message &existing = state->session_results[idx];
        if (!existing.isValid) {
            continue;
        }
        if (existing.message.hash != candidate.message.hash ||
            0 != std::strcmp(existing.message.text, candidate.message.text)) {
            continue;
        }
        if (existing.freq_hz <= 0.0f || candidate.freq_hz <= 0.0f ||
            std::fabs(existing.freq_hz - candidate.freq_hz) <= kDecodeDuplicateFrequencyToleranceHz) {
            return idx;
        }
    }
    return -1;
}

static bool prefer_decode_result(const ft8_message &candidate, const ft8_message &existing) {
    if (candidate.candidate.score != existing.candidate.score) {
        return candidate.candidate.score > existing.candidate.score;
    }
    if (candidate.snr != existing.snr) {
        return candidate.snr > existing.snr;
    }
    if (candidate.status.ldpc_errors != existing.status.ldpc_errors) {
        return candidate.status.ldpc_errors < existing.status.ldpc_errors;
    }
    if (candidate.time_sec != existing.time_sec) {
        return std::fabs(candidate.time_sec) < std::fabs(existing.time_sec);
    }
    return candidate.freq_hz < existing.freq_hz;
}

static bool decode_candidate_with_ap_texts(wsjtx_port_decoder_t *state,
                                           waterfall_t *wf,
                                           candidate_t candidate,
                                           const std::vector<std::string> &texts,
                                           int iterations,
                                           ft8_message *out);

static bool decode_candidate_with_waterfall(wsjtx_port_decoder_t *state,
                                            waterfall_t *wf,
                                            candidate_t candidate,
                                            int iterations,
                                            ft8_message *out) {
    if (state == nullptr || wf == nullptr || out == nullptr) {
        return false;
    }

    ft8_message decoded;
    std::memset(&decoded, 0, sizeof(decoded));
    decoded.utcTime = state->utc_time;
    decoded.candidate = candidate;

    if (!ft8_decode(wf,
                    &decoded.candidate,
                    &decoded.message,
                    iterations,
                    &state->ap_hints,
                    &decoded.status)) {
        return false;
    }

    decoded.isValid = true;
    decoded.snr = decoded.candidate.snr;
    decoded.freq_hz = candidate_freq_hz(state, decoded.candidate);
    decoded.time_sec = candidate_time_sec(state, decoded.candidate);
    *out = decoded;
    return true;
}

static bool remember_decode(wsjtx_port_decoder_t *state, const ft8_message &decoded) {
    if (state == nullptr || !decoded.isValid) {
        return false;
    }

    const int duplicate_index = find_duplicate_result_index(state, decoded);
    if (duplicate_index >= 0) {
        ft8_message &existing = state->session_results[duplicate_index];
        if (prefer_decode_result(decoded, existing)) {
            existing = decoded;
        }
        return false;
    }

    if ((int) state->session_results.size() >= kMax_decoded_messages) {
        return false;
    }

    state->session_results.push_back(decoded);
    return true;
}

static void remember_subtract_job(const ft8_message &decoded, std::vector<subtract_job_t> *jobs) {
    if (jobs == nullptr || !decoded.isValid) {
        return;
    }

    subtract_job_t job;
    std::memcpy(job.a91, decoded.message.a91, sizeof(job.a91));
    job.frequency = decoded.freq_hz;
    job.time_sec = decoded.time_sec;
    jobs->push_back(job);
}

static void subtract_ftx_signal(wsjtx_port_decoder_t *state,
                                const uint8_t *payload,
                                int sample_rate,
                                float frequency,
                                float time_sec,
                                int mode) {
    if (state == nullptr || payload == nullptr) {
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

    std::vector<uint8_t> tones(nn, 0);
    if (mode == 1) {
        ft4_encode(payload, tones.data());
    } else {
        ft8_encode(payload, tones.data());
    }

    int max_block_size = (int) (slot_time / symbol_period) * kTime_osr * kFreq_osr
                         * (int) (sample_rate * symbol_period / 2);
    int block_size = (int) (symbol_period * state->mon_cfg.sample_rate);
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

        set_mag_to_zero(state, index1, max_block_size);
        set_mag_to_zero(state, index2, max_block_size);
        set_mag_to_zero(state, index3, max_block_size);
        set_mag_to_zero(state, index4, max_block_size);
        set_mag_to_zero(state, index5, max_block_size);
        set_mag_to_zero(state, index6, max_block_size);
        set_mag_to_zero(state, index7, max_block_size);
        set_mag_to_zero(state, index8, max_block_size);
        set_mag_to_zero(state, index9, max_block_size);
        set_mag_to_zero(state, index10, max_block_size);
    }
}

static void apply_subtract_jobs(wsjtx_port_decoder_t *state,
                                const std::vector<subtract_job_t> &jobs,
                                int mode) {
    if (state == nullptr) {
        return;
    }

    for (const subtract_job_t &job : jobs) {
        subtract_ftx_signal(state,
                            job.a91,
                            state->mon_cfg.sample_rate,
                            job.frequency,
                            job.time_sec,
                            mode);
    }
}

static int clamp_candidate_count(int count) {
    if (count < 0) {
        return 0;
    }
    if (count > kMax_candidates) {
        return kMax_candidates;
    }
    return count;
}

static const char *pass_role_name(wsjtx::PassRole role) {
    switch (role) {
        case wsjtx::PassRole::kEarly:
            return "early";
        case wsjtx::PassRole::kLate:
            return "late";
        case wsjtx::PassRole::kAp:
            return "ap";
        case wsjtx::PassRole::kSubtract:
            return "subtract";
        case wsjtx::PassRole::kBase:
        default:
            return "base";
    }
}

static const char *candidate_source_name(wsjtx::CandidateSource source) {
    switch (source) {
        case wsjtx::CandidateSource::kFt4RawFft:
            return "ft4_raw_fft";
        case wsjtx::CandidateSource::kWaterfall:
        default:
            return "waterfall";
    }
}

static bool same_candidate_identity(const candidate_t &lhs, const candidate_t &rhs) {
    return lhs.time_offset == rhs.time_offset &&
           lhs.time_sub == rhs.time_sub &&
           lhs.freq_offset == rhs.freq_offset &&
           lhs.freq_sub == rhs.freq_sub &&
           lhs.score == rhs.score &&
           lhs.snr == rhs.snr;
}

static int sample_count_for_phase(const wsjtx_port_decoder_t *state, int phase_ticks) {
    if (state == nullptr || phase_ticks <= 0 || state->raw_samples.empty()) {
        return 0;
    }

    const int available_samples = (int) state->raw_samples.size();
    const int slot_samples = (state->slot_samples > 0) ? state->slot_samples : available_samples;
    int sample_count = 0;
    if (phase_ticks >= kFt8PhaseTicksFull) {
        sample_count = slot_samples;
    } else {
        sample_count = (slot_samples * phase_ticks) / kFt8PhaseTicksFull;
    }
    if (sample_count < state->mon.block_size || available_samples < sample_count) {
        return 0;
    }
    return sample_count;
}

static bool has_full_slot_samples(const wsjtx_port_decoder_t *state) {
    if (state == nullptr) {
        return false;
    }

    const int slot_samples = (state->slot_samples > 0)
                             ? state->slot_samples
                             : (int) state->raw_samples.size();
    return slot_samples > 0 && (int) state->raw_samples.size() >= slot_samples;
}

static void rebuild_waterfall(wsjtx_port_decoder_t *state, int sample_count) {
    if (state == nullptr) {
        return;
    }

    monitor_reset(&state->mon);
    const int available_samples = std::min(sample_count, (int) state->raw_samples.size());
    for (int frame_pos = 0;
         frame_pos + state->mon.block_size <= available_samples;
         frame_pos += state->mon.block_size) {
        monitor_process(&state->mon, state->raw_samples.data() + frame_pos);
    }
}

static void rebuild_fullslot_waterfall(wsjtx_port_decoder_t *state) {
    rebuild_waterfall(state, (int) state->raw_samples.size());
}

static int collect_sorted_candidates(wsjtx_port_decoder_t *state,
                                     int min_score,
                                     candidate_t out[kMax_candidates]) {
    if (state == nullptr || out == nullptr) {
        return 0;
    }

    int count = clamp_candidate_count(ft8_find_sync(&state->mon.wf, kMax_candidates, out, min_score));
    if (count <= 1) {
        return count;
    }

    std::stable_sort(out, out + count, [](const candidate_t &lhs, const candidate_t &rhs) {
        if (lhs.score != rhs.score) {
            return lhs.score > rhs.score;
        }
        if (lhs.snr != rhs.snr) {
            return lhs.snr > rhs.snr;
        }
        if (lhs.time_offset != rhs.time_offset) {
            return lhs.time_offset < rhs.time_offset;
        }
        if (lhs.time_sub != rhs.time_sub) {
            return lhs.time_sub < rhs.time_sub;
        }
        if (lhs.freq_offset != rhs.freq_offset) {
            return lhs.freq_offset < rhs.freq_offset;
        }
        return lhs.freq_sub < rhs.freq_sub;
    });

    int filtered_count = 1;
    for (int idx = 1; idx < count; ++idx) {
        if (same_candidate_identity(out[filtered_count - 1], out[idx])) {
            continue;
        }
        out[filtered_count++] = out[idx];
    }
    return filtered_count;
}

static int decode_candidates(wsjtx_port_decoder_t *state,
                             candidate_t candidates[kMax_candidates],
                             int candidate_count,
                             int iterations,
                             const std::vector<std::string> *ap_texts,
                             std::vector<subtract_job_t> *jobs,
                             pass_decode_stats_t *stats) {
    candidate_count = clamp_candidate_count(candidate_count);
    if (state == nullptr || candidate_count <= 0) {
        return 0;
    }

    if (stats != nullptr) {
        std::memset(stats, 0, sizeof(*stats));
    }

    int new_results = 0;
    for (int idx = 0; idx < candidate_count; ++idx) {
        ft8_message decoded;
        bool decoded_ok = false;
        bool decoded_by_ap = false;
        if (ap_texts != nullptr && !ap_texts->empty()) {
            decoded_ok = decode_candidate_with_ap_texts(state,
                                                        &state->mon.wf,
                                                        candidates[idx],
                                                        *ap_texts,
                                                        iterations,
                                                        &decoded);
            decoded_by_ap = decoded_ok;
        }

        if (!decoded_ok) {
            decoded_ok = decode_candidate_with_waterfall(state,
                                                         &state->mon.wf,
                                                         candidates[idx],
                                                         iterations,
                                                         &decoded);
        }

        if (!decoded_ok) {
            continue;
        }

        if (stats != nullptr) {
            ++stats->decode_success_count;
            if (decoded_by_ap) {
                ++stats->ap_success_count;
            }
        }

        if (!remember_decode(state, decoded)) {
            if (stats != nullptr) {
                ++stats->duplicate_count;
            }
            continue;
        }

        ++new_results;
        remember_subtract_job(decoded, jobs);
    }

    return new_results;
}

static float abs_diff(float lhs, float rhs);
static int collect_ft4_candidates(wsjtx_port_decoder_t *state,
                                  int min_score,
                                  candidate_t out[kMax_candidates]);
static int ft8_followup_mode_index(const wsjtx_port_decoder_t *state);
static int64_t ft8_slot_id(const wsjtx_port_decoder_t *state);
static int ft8_followup_min_sync_score(const wsjtx_port_decoder_t *state);
static followup_search_policy_t ft8_followup_search_policy(const wsjtx_port_decoder_t *state);
static bool is_plain_grid4(const char *grid);
static bool is_cq_call_token(const char *call);
static bool is_contest_cq_call(const char *call);
static bool has_followup_forbidden_char(const char *value);
static bool message_mentions_call(const message_t &message, const char *call);
static bool build_ft8_followup_entry(const ft8_message &decoded, ft8_followup_entry_t *entry);
static bool same_ft8_followup_entry(const ft8_followup_entry_t &lhs, const ft8_followup_entry_t &rhs);
static void append_ft8_followup_entry_unique(std::vector<ft8_followup_entry_t> *entries,
                                             const ft8_followup_entry_t &entry);
static std::vector<ft8_followup_entry_t> prepare_ft8_followup_history(const wsjtx_port_decoder_t *state);
static bool ft8_followup_entry_is_covered(const wsjtx_port_decoder_t *state,
                                          const ft8_followup_entry_t &entry);
static void commit_ft8_followup_history(const wsjtx_port_decoder_t *state);
static void build_ap_pass_texts(const wsjtx_port_decoder_t *state,
                                const std::vector<ft8_followup_entry_t> &history_entries,
                                std::vector<std::string> *texts);

static void append_ft8_followup_text(std::vector<std::string> *texts, const std::string &text) {
    if (texts == nullptr || text.empty() || (int) texts->size() >= kApPassMaxTexts) {
        return;
    }

    for (const std::string &existing : *texts) {
        if (existing == text) {
            return;
        }
    }
    texts->push_back(text);
}

static bool decode_candidate_with_ap_texts(wsjtx_port_decoder_t *state,
                                           waterfall_t *wf,
                                           candidate_t candidate,
                                           const std::vector<std::string> &texts,
                                           int iterations,
                                           ft8_message *out) {
    if (state == nullptr || wf == nullptr || out == nullptr || texts.empty()) {
        return false;
    }

    std::vector<const char *> text_ptrs;
    text_ptrs.reserve(texts.size());
    for (const std::string &text : texts) {
        text_ptrs.push_back(text.c_str());
    }

    ft8_message decoded;
    std::memset(&decoded, 0, sizeof(decoded));
    decoded.utcTime = state->utc_time;
    decoded.candidate = candidate;

    if (!ft8_decode_with_ap_texts(wf,
                                  &decoded.candidate,
                                  text_ptrs.data(),
                                  (int) text_ptrs.size(),
                                  iterations,
                                  &decoded.message,
                                  &decoded.status)) {
        return false;
    }

    decoded.isValid = true;
    decoded.snr = decoded.candidate.snr;
    decoded.freq_hz = candidate_freq_hz(state, decoded.candidate);
    decoded.time_sec = candidate_time_sec(state, decoded.candidate);
    *out = decoded;
    return true;
}

static void append_ft8_followup_exchange_texts(std::vector<std::string> *texts,
                                               const std::string &base,
                                               const char *grid4) {
    append_ft8_followup_text(texts, base);
    if (is_plain_grid4(grid4)) {
        append_ft8_followup_text(texts, base + " " + grid4);
    }
    append_ft8_followup_text(texts, base + " RRR");
    append_ft8_followup_text(texts, base + " RR73");
    append_ft8_followup_text(texts, base + " 73");

    for (int reportValue = -50; reportValue <= 49; ++reportValue) {
        char reportText[6];
        char replyReportText[7];
        std::snprintf(reportText, sizeof(reportText), "%+03d", reportValue);
        std::snprintf(replyReportText, sizeof(replyReportText), "R%+03d", reportValue);
        append_ft8_followup_text(texts, base + " " + reportText);
        append_ft8_followup_text(texts, base + " " + replyReportText);
    }
}

static void build_ft8_followup_texts(const wsjtx_port_decoder_t *state,
                                     const ft8_followup_entry_t &entry,
                                     std::vector<std::string> *texts) {
    if (texts == nullptr) {
        return;
    }

    texts->clear();
    const std::string base = std::string(entry.call_1) + " " + entry.call_2;

    if (is_cq_call_token(entry.call_1)) {
        if (entry.grid4[0] != '\0') {
            append_ft8_followup_text(texts, base + " " + entry.grid4);
        } else {
            append_ft8_followup_text(texts, base);
        }

        if (state != nullptr &&
            state->ap_hints.my_call[0] != '\0' &&
            !is_contest_cq_call(entry.call_1) &&
            0 != std::strcmp(state->ap_hints.my_call, entry.call_2)) {
            const std::string reply_base = std::string(entry.call_2) + " " + state->ap_hints.my_call;
            const std::string my_base = std::string(state->ap_hints.my_call) + " " + entry.call_2;
            append_ft8_followup_exchange_texts(texts, reply_base, nullptr);
            append_ft8_followup_exchange_texts(texts, my_base, nullptr);
        }
        return;
    }

    const std::string reverse_base = std::string(entry.call_2) + " " + entry.call_1;
    append_ft8_followup_exchange_texts(texts, reverse_base, nullptr);
    append_ft8_followup_exchange_texts(texts, base, entry.grid4);
}

static int select_ft8_followup_candidates(const wsjtx_port_decoder_t *state,
                                          candidate_t candidates[kMax_candidates],
                                          int candidate_count,
                                          const ft8_followup_entry_t &entry,
                                          candidate_t out[kFt8FollowupMaxCandidatesPerEntry]) {
    if (state == nullptr || candidate_count <= 0 || out == nullptr) {
        return 0;
    }

    struct ranked_candidate_t {
        candidate_t candidate;
        float metric;
    };

    const followup_search_policy_t policy = ft8_followup_search_policy(state);
    std::vector<ranked_candidate_t> ranked;
    ranked.reserve(candidate_count);

    for (int idx = 0; idx < candidate_count; ++idx) {
        const float freq_delta = abs_diff(candidate_freq_hz(state, candidates[idx]), entry.frequency);
        if (freq_delta > policy.freq_window_hz) {
            continue;
        }

        const float time_delta = abs_diff(candidate_time_sec(state, candidates[idx]), entry.time_sec);
        if (time_delta > policy.time_window_sec) {
            continue;
        }

        const float freq_term = freq_delta / std::max(policy.freq_window_hz, 1.0f);
        const float time_term = time_delta / std::max(policy.time_window_sec, 0.1f);
        const float score_bonus = (float) candidates[idx].score * 0.025f;
        ranked.push_back({candidates[idx], freq_term + time_term - score_bonus});
    }

    std::stable_sort(ranked.begin(), ranked.end(),
                     [](const ranked_candidate_t &lhs, const ranked_candidate_t &rhs) {
                         if (lhs.metric != rhs.metric) {
                             return lhs.metric < rhs.metric;
                         }
                         if (lhs.candidate.score != rhs.candidate.score) {
                             return lhs.candidate.score > rhs.candidate.score;
                         }
                         if (lhs.candidate.time_offset != rhs.candidate.time_offset) {
                             return lhs.candidate.time_offset < rhs.candidate.time_offset;
                         }
                         return lhs.candidate.freq_offset < rhs.candidate.freq_offset;
                     });

    const int selected_count = std::min((int) ranked.size(),
                                        std::min(policy.max_candidates, kFt8FollowupMaxCandidatesPerEntry));
    for (int idx = 0; idx < selected_count; ++idx) {
        out[idx] = ranked[idx].candidate;
    }
    return selected_count;
}

static void run_ft8_followup_pass(wsjtx_port_decoder_t *state,
                                  const std::vector<subtract_job_t> &subtract_history,
                                  const std::vector<ft8_followup_entry_t> &history_entries) {
    if (state == nullptr || history_entries.empty()) {
        return;
    }

    rebuild_fullslot_waterfall(state);
    const int subtract_mode = wsjtx::ResolveModeDescriptor(state->mon_cfg.protocol).subtract_mode;
    if (!subtract_history.empty()) {
        apply_subtract_jobs(state, subtract_history, subtract_mode);
    }

    candidate_t followup_candidates[kMax_candidates];
    const int followup_min_sync = ft8_followup_min_sync_score(state);
    const int candidate_count = state_is_ft4(state)
                                ? collect_ft4_candidates(state, followup_min_sync, followup_candidates)
                                : collect_sorted_candidates(state, followup_min_sync, followup_candidates);
    if (candidate_count <= 0) {
        return;
    }

    int history_used = 0;
    for (const ft8_followup_entry_t &entry : history_entries) {
        if (history_used >= kFt8FollowupMaxEntries) {
            break;
        }
        if (ft8_followup_entry_is_covered(state, entry)) {
            continue;
        }

        std::vector<std::string> texts;
        build_ft8_followup_texts(state, entry, &texts);
        if (texts.empty()) {
            continue;
        }

        candidate_t nearby[kFt8FollowupMaxCandidatesPerEntry];
        const int nearby_count = select_ft8_followup_candidates(state,
                                                                followup_candidates,
                                                                candidate_count,
                                                                entry,
                                                                nearby);
        if (nearby_count <= 0) {
            continue;
        }

        ++history_used;
        for (int idx = 0; idx < nearby_count; ++idx) {
            ft8_message decoded;
            if (!decode_candidate_with_ap_texts(state,
                                                &state->mon.wf,
                                                nearby[idx],
                                                texts,
                                                state->ldpc_iterations,
                                                &decoded)) {
                continue;
            }

            if (remember_decode(state, decoded)) {
                subtract_ftx_signal(state,
                                    decoded.message.a91,
                                    state->mon_cfg.sample_rate,
                                    decoded.freq_hz,
                                    decoded.time_sec,
                                    subtract_mode);
            }
            break;
        }
    }
}

static float abs_diff(float lhs, float rhs) {
    float delta = lhs - rhs;
    return (delta < 0.0f) ? -delta : delta;
}

static int ft8_followup_mode_index(const wsjtx_port_decoder_t *state) {
    return (state != nullptr && state_is_ft4(state)) ? 1 : 0;
}

static int64_t ft8_slot_id(const wsjtx_port_decoder_t *state) {
    const int64_t utc_time = (state == nullptr) ? 0 : state->utc_time;
    const int64_t slot_milliseconds = (state != nullptr && state_is_ft4(state))
                                      ? kFt4SlotMilliseconds
                                      : kFt8SlotMilliseconds;
    if (utc_time >= 0) {
        return utc_time / slot_milliseconds;
    }
    return (utc_time - (slot_milliseconds - 1)) / slot_milliseconds;
}

static int ft8_followup_min_sync_score(const wsjtx_port_decoder_t *state) {
    return (state != nullptr && state_is_ft4(state)) ? kFt4FollowupMinSyncScore : kFt8FollowupMinSyncScore;
}

static followup_search_policy_t ft8_followup_search_policy(const wsjtx_port_decoder_t *state) {
    const float rx_span_hz = (state == nullptr)
                             ? 2900.0f
                             : std::max(1000.0f, state->mon_cfg.f_max - state->mon_cfg.f_min);
    const int sensitivity = (state == nullptr) ? 1 : state->options.qso_freq_sensitivity;

    switch (sensitivity) {
        case 0:
            return {80.0f, 0.60f, 2};
        case 2:
            return {rx_span_hz, 1.20f, kFt8FollowupMaxCandidatesPerEntry};
        default:
            return {rx_span_hz, 0.90f, 3};
    }
}

static bool is_plain_grid4(const char *grid) {
    return grid != nullptr && std::strlen(grid) == 4;
}

static bool is_cq_call_token(const char *call) {
    return call != nullptr &&
           call[0] == 'C' &&
           call[1] == 'Q' &&
           (call[2] == '\0' || call[2] == ' ');
}

static bool is_contest_cq_call(const char *call) {
    if (!is_cq_call_token(call)) {
        return false;
    }
    return 0 == std::strcmp(call, "CQ TEST") ||
           0 == std::strcmp(call, "CQ RU") ||
           0 == std::strcmp(call, "CQ FD") ||
           0 == std::strcmp(call, "CQ WW");
}

static bool has_followup_forbidden_char(const char *value) {
    return value != nullptr &&
           (std::strchr(value, '/') != nullptr ||
            std::strchr(value, '<') != nullptr ||
            std::strchr(value, '>') != nullptr);
}

static bool message_mentions_call(const message_t &message, const char *call) {
    if (call == nullptr || call[0] == '\0') {
        return false;
    }
    return 0 == std::strcmp(message.call_to, call) ||
           0 == std::strcmp(message.call_de, call) ||
           0 == std::strcmp(message.dx_call_to2, call);
}

static bool build_ft8_followup_entry(const ft8_message &decoded, ft8_followup_entry_t *entry) {
    if (entry == nullptr || !decoded.isValid) {
        return false;
    }

    const message_t &message = decoded.message;
    if (!((message.i3 == 1) || (message.i3 == 2))) {
        return false;
    }
    if (message.call_to[0] == '\0' || message.call_de[0] == '\0') {
        return false;
    }
    if (has_followup_forbidden_char(message.call_to) || has_followup_forbidden_char(message.call_de)) {
        return false;
    }

    std::memset(entry, 0, sizeof(*entry));
    std::snprintf(entry->call_1, sizeof(entry->call_1), "%s", message.call_to);
    std::snprintf(entry->call_2, sizeof(entry->call_2), "%s", message.call_de);
    if (is_plain_grid4(message.maidenGrid)) {
        std::snprintf(entry->grid4, sizeof(entry->grid4), "%s", message.maidenGrid);
    }
    entry->frequency = decoded.freq_hz;
    entry->time_sec = decoded.time_sec;
    return true;
}

static bool same_ft8_followup_entry(const ft8_followup_entry_t &lhs, const ft8_followup_entry_t &rhs) {
    return 0 == std::strcmp(lhs.call_1, rhs.call_1) &&
           0 == std::strcmp(lhs.call_2, rhs.call_2) &&
           0 == std::strcmp(lhs.grid4, rhs.grid4) &&
           abs_diff(lhs.frequency, rhs.frequency) <= kFt8FollowupHistoryMatchHz &&
           abs_diff(lhs.time_sec, rhs.time_sec) <= kFt8FollowupHistoryMatchTimeSec;
}

static void append_ft8_followup_entry_unique(std::vector<ft8_followup_entry_t> *entries,
                                             const ft8_followup_entry_t &entry) {
    if (entries == nullptr || (int) entries->size() >= kFt8FollowupMaxEntries) {
        return;
    }

    for (const ft8_followup_entry_t &existing : *entries) {
        if (same_ft8_followup_entry(existing, entry)) {
            return;
        }
    }
    entries->push_back(entry);
}

static std::vector<ft8_followup_entry_t> prepare_ft8_followup_history(const wsjtx_port_decoder_t *state) {
    if (state == nullptr || (!state_is_ft8(state) && !state_is_ft4(state))) {
        return {};
    }

    const int mode_index = ft8_followup_mode_index(state);
    const int64_t slot = ft8_slot_id(state);

    std::lock_guard<std::mutex> lock(g_ft8_followup_mutex);
    if (g_ft8_followup_slot_ids[mode_index] != slot) {
        g_ft8_followup_previous_slot[mode_index] = g_ft8_followup_current_slot[mode_index];
        g_ft8_followup_current_slot[mode_index].clear();
        g_ft8_followup_slot_ids[mode_index] = slot;
    }
    return g_ft8_followup_previous_slot[mode_index];
}

static bool ft8_followup_entry_is_covered(const wsjtx_port_decoder_t *state,
                                          const ft8_followup_entry_t &entry) {
    if (state == nullptr) {
        return true;
    }

    for (const ft8_message &decoded : state->session_results) {
        if (!decoded.isValid) {
            continue;
        }
        if (is_cq_call_token(entry.call_1)) {
            if (message_mentions_call(decoded.message, entry.call_2)) {
                return true;
            }
            continue;
        }
        if ((message_mentions_call(decoded.message, entry.call_1) &&
             message_mentions_call(decoded.message, entry.call_2))) {
            return true;
        }
    }
    return false;
}

static void commit_ft8_followup_history(const wsjtx_port_decoder_t *state) {
    if (state == nullptr || (!state_is_ft8(state) && !state_is_ft4(state))) {
        return;
    }

    std::vector<ft8_followup_entry_t> additions;
    additions.reserve(kFt8FollowupMaxEntries);
    for (const ft8_message &decoded : state->session_results) {
        ft8_followup_entry_t entry{};
        if (!build_ft8_followup_entry(decoded, &entry)) {
            continue;
        }
        append_ft8_followup_entry_unique(&additions, entry);
    }

    if (additions.empty()) {
        return;
    }

    const int mode_index = ft8_followup_mode_index(state);
    const int64_t slot = ft8_slot_id(state);

    std::lock_guard<std::mutex> lock(g_ft8_followup_mutex);
    if (g_ft8_followup_slot_ids[mode_index] != slot) {
        g_ft8_followup_previous_slot[mode_index] = g_ft8_followup_current_slot[mode_index];
        g_ft8_followup_current_slot[mode_index].clear();
        g_ft8_followup_slot_ids[mode_index] = slot;
    }

    for (const ft8_followup_entry_t &entry : additions) {
        append_ft8_followup_entry_unique(&g_ft8_followup_current_slot[mode_index], entry);
    }
}

static float ft4_peak_match_hz(const wsjtx_port_decoder_t *state) {
    if (state == nullptr) {
        return 35.0f;
    }

    switch (state->options.qso_freq_sensitivity) {
        case 0:
            return 24.0f;
        case 2:
            return 52.0f;
        default:
            return 35.0f;
    }
}

static void append_manual_ap_hint_texts(const wsjtx_port_decoder_t *state,
                                        std::vector<std::string> *texts) {
    if (state == nullptr || texts == nullptr) {
        return;
    }

    const char *my_call = state->ap_hints.my_call;
    const bool has_my_call = my_call[0] != '\0' && !has_followup_forbidden_char(my_call);
    for (int idx = 0; idx < state->ap_hints.hint_call_count && idx < FTX_AP_MAX_HINT_CALLS; ++idx) {
        const char *hint_call = state->ap_hints.hint_calls[idx];
        const char *hint_grid = state->ap_hints.hint_grids[idx];
        if (hint_call[0] == '\0' || has_followup_forbidden_char(hint_call)) {
            continue;
        }

        if (is_plain_grid4(hint_grid)) {
            append_ft8_followup_text(texts, std::string("CQ ") + hint_call + " " + hint_grid);
        }
        append_ft8_followup_text(texts, std::string("CQ ") + hint_call);

        if (has_my_call && 0 != std::strcmp(my_call, hint_call)) {
            const std::string my_to_dx = std::string(my_call) + " " + hint_call;
            const std::string dx_to_my = std::string(hint_call) + " " + my_call;
            append_ft8_followup_exchange_texts(texts, my_to_dx, hint_grid);
            append_ft8_followup_exchange_texts(texts, dx_to_my, nullptr);
        }
    }
}

static void build_ap_pass_texts(const wsjtx_port_decoder_t *state,
                                const std::vector<ft8_followup_entry_t> &history_entries,
                                std::vector<std::string> *texts) {
    if (state == nullptr || texts == nullptr) {
        return;
    }

    texts->clear();
    append_manual_ap_hint_texts(state, texts);

    int used_history = 0;
    for (const ft8_followup_entry_t &entry : history_entries) {
        if (used_history >= kApPassHistoryEntries || (int) texts->size() >= kApPassMaxTexts) {
            break;
        }
        build_ft8_followup_texts(state, entry, texts);
        ++used_history;
    }

    int used_session = 0;
    for (const ft8_message &decoded : state->session_results) {
        if (used_session >= kApPassHistoryEntries || (int) texts->size() >= kApPassMaxTexts) {
            break;
        }
        ft8_followup_entry_t entry{};
        if (!build_ft8_followup_entry(decoded, &entry)) {
            continue;
        }
        build_ft8_followup_texts(state, entry, texts);
        ++used_session;
    }
}

static int ft4_decode_sensitivity(const wsjtx_port_decoder_t *state) {
    if (state == nullptr) {
        return 1;
    }
    return std::max(0, std::min(state->options.decode_sensitivity, 2));
}

static bool ft4_is_deep_mode(const wsjtx_port_decoder_t *state) {
    return state != nullptr && state->ldpc_iterations > fast_kLDPC_iterations;
}

static void build_ft4_nuttall_window(std::vector<float> *window) {
    if (window == nullptr) {
        return;
    }

    window->assign(kFt4CoarseFftSize, 0.0f);
    const float denom = (float) (kFt4CoarseFftSize - 1);
    for (int idx = 0; idx < kFt4CoarseFftSize; ++idx) {
        const float phase = (2.0f * (float) M_PI * idx) / denom;
        (*window)[idx] = 0.355768f
                         - 0.487396f * std::cos(phase)
                         + 0.144232f * std::cos(2.0f * phase)
                         - 0.012604f * std::cos(3.0f * phase);
    }
}

static float compute_percentile(std::vector<float> values, float fraction) {
    if (values.empty()) {
        return 0.0f;
    }

    int nth = (int) (fraction * (float) (values.size() - 1));
    if (nth < 0) {
        nth = 0;
    }
    if (nth >= (int) values.size()) {
        nth = (int) values.size() - 1;
    }
    std::nth_element(values.begin(), values.begin() + nth, values.end());
    return values[nth];
}

static bool build_ft4_raw_average_spectrum(const wsjtx_port_decoder_t *state,
                                           std::vector<float> *avg_power,
                                           float *df_hz) {
    if (state == nullptr || avg_power == nullptr || df_hz == nullptr) {
        return false;
    }

    const int sample_count = std::min((int) state->raw_samples.size(), kFt4MaxWaveSamples);
    if (sample_count < kFt4CoarseFftSize) {
        return false;
    }

    std::vector<float> window;
    build_ft4_nuttall_window(&window);

    const int spectrum_bins = kFt4CoarseFftSize / 2;
    avg_power->assign(spectrum_bins, 0.0f);
    *df_hz = (float) state->mon_cfg.sample_rate / (float) kFt4CoarseFftSize;

    kiss_fftr_cfg fft_cfg = kiss_fftr_alloc(kFt4CoarseFftSize, 0, nullptr, nullptr);
    if (fft_cfg == nullptr) {
        avg_power->clear();
        return false;
    }

    std::vector<kiss_fft_scalar> timedata(kFt4CoarseFftSize, 0.0f);
    std::vector<kiss_fft_cpx> freqdata((kFt4CoarseFftSize / 2) + 1);

    int windows_used = 0;
    for (int start = 0; start + kFt4CoarseFftSize <= sample_count; start += kFt4CoarseHopSize) {
        for (int idx = 0; idx < kFt4CoarseFftSize; ++idx) {
            timedata[idx] = (state->raw_samples[start + idx] / 300.0f) * window[idx];
        }

        kiss_fftr(fft_cfg, timedata.data(), freqdata.data());
        for (int bin = 1; bin <= spectrum_bins; ++bin) {
            const float re = freqdata[bin].r;
            const float im = freqdata[bin].i;
            (*avg_power)[bin - 1] += (re * re) + (im * im);
        }
        ++windows_used;
    }

    kiss_fftr_free(fft_cfg);
    if (windows_used <= 0) {
        avg_power->clear();
        return false;
    }

    const float inv_windows = 1.0f / (float) windows_used;
    for (float &value : *avg_power) {
        value *= inv_windows;
    }
    return true;
}

static void build_ft4_baseline(const std::vector<float> &avg_power,
                               int min_bin,
                               int max_bin,
                               std::vector<float> *baseline) {
    if (baseline == nullptr) {
        return;
    }

    baseline->assign(avg_power.size(), 1.0f);
    if (avg_power.empty() || min_bin >= max_bin) {
        return;
    }

    std::vector<float> db(avg_power.size(), 0.0f);
    for (int idx = min_bin; idx <= max_bin && idx < (int) avg_power.size(); ++idx) {
        db[idx] = 10.0f * std::log10(std::max(avg_power[idx], 1e-12f));
    }

    struct baseline_point_t {
        int midpoint;
        float value_db;
    };

    std::vector<baseline_point_t> points;
    const int covered = max_bin - min_bin + 1;
    const int segment_len = std::max(1, covered / kFt4BaselineSegments);

    for (int seg = 0; seg < kFt4BaselineSegments; ++seg) {
        const int seg_lo = min_bin + seg * segment_len;
        if (seg_lo > max_bin) {
            break;
        }

        int seg_hi = (seg == kFt4BaselineSegments - 1) ? max_bin : std::min(max_bin, seg_lo + segment_len - 1);
        std::vector<float> slice;
        slice.reserve(seg_hi - seg_lo + 1);
        for (int idx = seg_lo; idx <= seg_hi; ++idx) {
            slice.push_back(db[idx]);
        }

        const float base_db = compute_percentile(std::move(slice), kFt4PeakPercentile) + 0.65f;
        points.push_back({(seg_lo + seg_hi) / 2, base_db});
    }

    if (points.empty()) {
        return;
    }

    for (int idx = 0; idx < min_bin; ++idx) {
        (*baseline)[idx] = std::pow(10.0f, points.front().value_db / 10.0f);
    }

    for (int point_idx = 0; point_idx < (int) points.size() - 1; ++point_idx) {
        const baseline_point_t &lhs = points[point_idx];
        const baseline_point_t &rhs = points[point_idx + 1];
        const int span = std::max(1, rhs.midpoint - lhs.midpoint);
        for (int idx = lhs.midpoint; idx <= rhs.midpoint && idx < (int) baseline->size(); ++idx) {
            const float t = (float) (idx - lhs.midpoint) / (float) span;
            const float interp_db = lhs.value_db + t * (rhs.value_db - lhs.value_db);
            (*baseline)[idx] = std::pow(10.0f, interp_db / 10.0f);
        }
    }

    for (int idx = points.back().midpoint; idx <= max_bin && idx < (int) baseline->size(); ++idx) {
        (*baseline)[idx] = std::pow(10.0f, points.back().value_db / 10.0f);
    }
}

static void smooth_ft4_average_spectrum(const std::vector<float> &avg_power, std::vector<float> *smoothed) {
    if (smoothed == nullptr) {
        return;
    }

    smoothed->assign(avg_power.size(), 0.0f);
    if (avg_power.empty()) {
        return;
    }

    for (int idx = 0; idx < (int) avg_power.size(); ++idx) {
        if (idx < 7 || idx >= (int) avg_power.size() - 7) {
            continue;
        }

        float sum = 0.0f;
        for (int pos = idx - 7; pos <= idx + 7; ++pos) {
            sum += avg_power[pos];
        }
        (*smoothed)[idx] = sum / 15.0f;
    }
}

static float ft4_sync_ratio_threshold(const wsjtx_port_decoder_t *state, int min_score) {
    float threshold;
    if (min_score >= 10) {
        threshold = 1.20f;
    } else if (min_score >= 9) {
        threshold = 1.15f;
    } else {
        threshold = 1.10f;
    }

    switch (ft4_decode_sensitivity(state)) {
        case 0:
            threshold += 0.04f;
            break;
        case 2:
            threshold -= 0.04f;
            break;
        default:
            break;
    }

    if (ft4_is_deep_mode(state)) {
        threshold -= 0.02f;
    }
    return std::max(1.04f, threshold);
}

static std::vector<ft4_peak_t> find_ft4_peak_frequencies(const wsjtx_port_decoder_t *state, int min_score) {
    std::vector<float> avg_power;
    float df_hz = 0.0f;
    if (!build_ft4_raw_average_spectrum(state, &avg_power, &df_hz) || avg_power.size() < 3) {
        return {};
    }

    std::vector<float> smoothed;
    std::vector<float> baseline;
    smooth_ft4_average_spectrum(avg_power, &smoothed);

    int min_bin = (int) (std::max(state->mon_cfg.f_min, 200.0f) / df_hz);
    int max_bin = (int) (std::min(state->mon_cfg.f_max, 4910.0f) / df_hz);
    min_bin = std::max(1, min_bin);
    max_bin = std::min((int) avg_power.size() - 2, max_bin);
    build_ft4_baseline(avg_power, min_bin, max_bin, &baseline);

    std::vector<ft4_peak_t> peaks;
    const float sync_min = ft4_sync_ratio_threshold(state, min_score);

    for (int bin = min_bin; bin <= max_bin; ++bin) {
        const float base = std::max(baseline[bin], 1e-6f);
        const float prev = smoothed[bin - 1] / std::max(baseline[bin - 1], 1e-6f);
        const float curr = smoothed[bin] / base;
        const float next = smoothed[bin + 1] / std::max(baseline[bin + 1], 1e-6f);
        if (curr < sync_min || curr < prev || curr < next) {
            continue;
        }

        const float den = prev - (2.0f * curr) + next;
        float delta = 0.0f;
        if (std::fabs(den) > 1e-6f) {
            delta = 0.5f * (prev - next) / den;
        }

        const float frequency = ((float) bin + delta) * df_hz + kFt4PeakOffsetHz;
        if (frequency < 200.0f || frequency > 4910.0f) {
            continue;
        }

        const float strength = curr - 0.25f * (prev - next) * delta;
        peaks.push_back({frequency, strength});
        if ((int) peaks.size() >= kMax_candidates) {
            break;
        }
    }

    std::stable_sort(peaks.begin(), peaks.end(), [](const ft4_peak_t &lhs, const ft4_peak_t &rhs) {
        if (lhs.strength != rhs.strength) {
            return lhs.strength > rhs.strength;
        }
        return lhs.frequency < rhs.frequency;
    });

    std::vector<ft4_peak_t> filtered;
    filtered.reserve(peaks.size());
    const float min_peak_spacing_hz = std::max(12.0f, ft4_peak_match_hz(state) * 0.55f);
    for (const ft4_peak_t &peak : peaks) {
        bool too_close = false;
        for (const ft4_peak_t &kept : filtered) {
            if (abs_diff(peak.frequency, kept.frequency) < min_peak_spacing_hz) {
                too_close = true;
                break;
            }
        }
        if (!too_close) {
            filtered.push_back(peak);
        }
        if ((int) filtered.size() >= kMax_candidates) {
            break;
        }
    }
    return filtered;
}

static float candidate_ft4_peak_strength(const wsjtx_port_decoder_t *state,
                                         const candidate_t &candidate,
                                         const std::vector<ft4_peak_t> &peaks) {
    const float frequency = candidate_freq_hz(state, candidate);
    const float peak_match_hz = ft4_peak_match_hz(state);
    float best_strength = -1.0f;
    for (const ft4_peak_t &peak : peaks) {
        const float delta = abs_diff(frequency, peak.frequency);
        if (delta <= peak_match_hz) {
            const float closeness = 1.0f - (delta / std::max(peak_match_hz, 1.0f));
            best_strength = std::max(best_strength, peak.strength + 0.12f * closeness);
        }
    }
    return best_strength;
}

static int collect_ft4_candidates(wsjtx_port_decoder_t *state,
                                  int min_score,
                                  candidate_t out[kMax_candidates]) {
    if (state == nullptr || out == nullptr) {
        return 0;
    }

    candidate_t generic_candidates[kMax_candidates];
    const int generic_count = collect_sorted_candidates(state, min_score, generic_candidates);
    if (generic_count <= 0) {
        return 0;
    }

    const std::vector<ft4_peak_t> peaks = find_ft4_peak_frequencies(state, min_score);
    struct ranked_candidate_t {
        candidate_t candidate;
        float peak_strength;
        bool matched_peak;
        float metric;
    };

    std::vector<ranked_candidate_t> ranked;
    ranked.reserve(generic_count);

    for (int idx = 0; idx < generic_count; ++idx) {
        const float peak_strength = candidate_ft4_peak_strength(state, generic_candidates[idx], peaks);
        const bool matched_peak = peak_strength > 0.0f;
        const int score_margin = (ft4_decode_sensitivity(state) == 2) ? 2 : 3;
        if (!matched_peak && generic_candidates[idx].score < min_score + score_margin) {
            continue;
        }
        const float metric = peak_strength
                             + 0.035f * (float) generic_candidates[idx].score
                             + 0.010f * (float) generic_candidates[idx].snr;
        ranked.push_back({generic_candidates[idx], peak_strength, matched_peak, metric});
    }

    if (ranked.empty()) {
        const int fallback = std::min(generic_count, (int) kMax_candidates);
        std::copy(generic_candidates, generic_candidates + fallback, out);
        return fallback;
    }

    std::stable_sort(ranked.begin(), ranked.end(),
                     [](const ranked_candidate_t &lhs, const ranked_candidate_t &rhs) {
                         if (lhs.matched_peak != rhs.matched_peak) {
                             return lhs.matched_peak > rhs.matched_peak;
                         }
                         if (lhs.metric != rhs.metric) {
                             return lhs.metric > rhs.metric;
                         }
                         if (lhs.peak_strength != rhs.peak_strength) {
                             return lhs.peak_strength > rhs.peak_strength;
                         }
                         if (lhs.candidate.score != rhs.candidate.score) {
                             return lhs.candidate.score > rhs.candidate.score;
                         }
                         if (lhs.candidate.snr != rhs.candidate.snr) {
                             return lhs.candidate.snr > rhs.candidate.snr;
                         }
                         if (lhs.candidate.time_offset != rhs.candidate.time_offset) {
                             return lhs.candidate.time_offset < rhs.candidate.time_offset;
                         }
                         return lhs.candidate.freq_offset < rhs.candidate.freq_offset;
                     });

    int filtered_count = 0;
    for (const ranked_candidate_t &entry : ranked) {
        if (filtered_count >= kMax_candidates) {
            break;
        }
        out[filtered_count++] = entry.candidate;
    }

    return filtered_count;
}

static int collect_candidates_for_pass(wsjtx_port_decoder_t *state,
                                       const wsjtx::SessionPass &pass,
                                       candidate_t out[kMax_candidates],
                                       int *raw_count_out) {
    int count;

    switch (pass.candidate_source) {
        case wsjtx::CandidateSource::kFt4RawFft:
            count = collect_ft4_candidates(state, pass.min_sync_score, out);
            break;
        case wsjtx::CandidateSource::kWaterfall:
        default:
            count = collect_sorted_candidates(state, pass.min_sync_score, out);
            break;
    }

    if (raw_count_out != nullptr) {
        *raw_count_out = count;
    }

    count = clamp_candidate_count(count);
    if (pass.max_candidates > 0 && count > pass.max_candidates) {
        count = pass.max_candidates;
    }
    return count;
}

static void append_subtract_history(std::vector<subtract_job_t> *history,
                                    const std::vector<subtract_job_t> &pass_jobs) {
    if (history == nullptr || pass_jobs.empty()) {
        return;
    }
    history->insert(history->end(), pass_jobs.begin(), pass_jobs.end());
    if ((int) history->size() > kSubtractHistoryBudget) {
        history->erase(history->begin(),
                       history->begin() + ((int) history->size() - kSubtractHistoryBudget));
    }
}

static bool rebuild_for_pass(wsjtx_port_decoder_t *state, const wsjtx::SessionPass &pass) {
    if (state == nullptr) {
        return false;
    }

    if (pass.phase_ticks <= 0) {
        return has_full_slot_samples(state);
    }

    if (pass.phase_ticks >= kFt8PhaseTicksFull) {
        const int sample_count = sample_count_for_phase(state, pass.phase_ticks);
        if (sample_count <= 0) {
            return false;
        }
        rebuild_waterfall(state, sample_count);
        return true;
    }

    const int sample_count = sample_count_for_phase(state, pass.phase_ticks);
    if (sample_count <= 0) {
        return false;
    }
    rebuild_waterfall(state, sample_count);
    return true;
}

static bool should_continue_after_empty_pass(const wsjtx::SessionPass &pass) {
    return pass.role == wsjtx::PassRole::kEarly
           || pass.role == wsjtx::PassRole::kLate
           || pass.role == wsjtx::PassRole::kAp;
}

static void run_session_passes(wsjtx_port_decoder_t *state) {
    if (state == nullptr) {
        return;
    }

    state->session_results.clear();
    reset_dedupe_state(state);

    const wsjtx::ModeDescriptor mode = wsjtx::ResolveModeDescriptor(state->mon_cfg.protocol);
    const bool deep_mode = state->ldpc_iterations > fast_kLDPC_iterations;
    const bool has_ap_hints = wsjtx::HasApHints(state->ap_hints.my_call, state->ap_hints.hint_call_count);
    const bool enable_ft8_followup = deep_mode &&
                                     (state_is_ft8(state) || state_is_ft4(state)) &&
                                     mode.supports_ap_followup &&
                                     state->options.enable_wideband_dx_search;
    const std::vector<ft8_followup_entry_t> followup_history = enable_ft8_followup
                                                               ? prepare_ft8_followup_history(state)
                                                               : std::vector<ft8_followup_entry_t>{};
    const wsjtx::SessionPlan plan = wsjtx::BuildSessionPlan(mode,
                                                            deep_mode,
                                                            has_ap_hints,
                                                            state->options);

    __android_log_print(ANDROID_LOG_INFO,
                        kWsjtLogTag,
                        "session start mode=%s deep=%d passes=%d rounds=%d apHints=%d wideband=%d followupHistory=%d planPasses=%d ldpc=%d",
                        state_is_ft4(state) ? "FT4" : "FT8",
                        deep_mode ? 1 : 0,
                        state->options.decode_pass_count,
                        state->options.multi_decode_round_count,
                        has_ap_hints ? 1 : 0,
                        state->options.enable_wideband_dx_search ? 1 : 0,
                        (int) followup_history.size(),
                        (int) plan.passes.size(),
                        state->ldpc_iterations);

    if (plan.passes.empty()) {
        state->num_candidates = 0;
        return;
    }

    std::vector<subtract_job_t> subtract_history;
    subtract_history.reserve((size_t) std::max(4, state->options.multi_decode_round_count * 8));

    for (const wsjtx::SessionPass &pass : plan.passes) {
        if (!rebuild_for_pass(state, pass)) {
            __android_log_print(ANDROID_LOG_INFO,
                                kWsjtLogTag,
                                "pass skip role=%s reason=rebuild_failed phaseTicks=%d",
                                pass_role_name(pass.role),
                                pass.phase_ticks);
            continue;
        }
        if (pass.apply_subtract_history && !subtract_history.empty()) {
            apply_subtract_jobs(state, subtract_history, plan.mode.subtract_mode);
        }

        int raw_candidate_count = 0;
        const int candidate_count = collect_candidates_for_pass(state,
                                                                pass,
                                                                state->candidate_list,
                                                                &raw_candidate_count);
        __android_log_print(ANDROID_LOG_INFO,
                            kWsjtLogTag,
                            "pass collect role=%s source=%s minSync=%d maxCandidates=%d phaseTicks=%d subtractHistory=%d raw=%d collected=%d",
                            pass_role_name(pass.role),
                            candidate_source_name(pass.candidate_source),
                            pass.min_sync_score,
                            pass.max_candidates,
                            pass.phase_ticks,
                            (int) subtract_history.size(),
                            raw_candidate_count,
                            candidate_count);
        __android_log_print(ANDROID_LOG_INFO,
                            kWsjtLogTag,
                            "pass collect raw=%d clipped=%d role=%s",
                            raw_candidate_count,
                            candidate_count,
                            pass_role_name(pass.role));
        if (candidate_count <= 0) {
            if (should_continue_after_empty_pass(pass)) {
                __android_log_print(ANDROID_LOG_INFO,
                                    kWsjtLogTag,
                                    "pass continue role=%s reason=empty_optional_pass",
                                    pass_role_name(pass.role));
                continue;
            }
            __android_log_print(ANDROID_LOG_INFO,
                                kWsjtLogTag,
                                "pass break role=%s reason=empty_required_pass",
                                pass_role_name(pass.role));
            break;
        }

        std::vector<subtract_job_t> pass_jobs;
        pass_jobs.reserve((size_t) std::max(4, pass.max_candidates));
        std::vector<std::string> ap_pass_texts;
        const std::vector<std::string> *ap_texts = nullptr;
        if (pass.role == wsjtx::PassRole::kAp) {
            build_ap_pass_texts(state, followup_history, &ap_pass_texts);
            if (!ap_pass_texts.empty()) {
                ap_texts = &ap_pass_texts;
            }
            __android_log_print(ANDROID_LOG_INFO,
                                kWsjtLogTag,
                                "pass ap role=%s history=%d sessionResults=%d apTexts=%d",
                                pass_role_name(pass.role),
                                (int) followup_history.size(),
                                (int) state->session_results.size(),
                                (int) ap_pass_texts.size());
            __android_log_print(ANDROID_LOG_INFO,
                                kWsjtLogTag,
                                "pass ap texts=%d role=%s",
                                (int) ap_pass_texts.size(),
                                pass_role_name(pass.role));
        }
        pass_decode_stats_t pass_stats{};
        const int new_results = decode_candidates(state,
                                                  state->candidate_list,
                                                  candidate_count,
                                                  pass.iterations,
                                                  ap_texts,
                                                  &pass_jobs,
                                                  &pass_stats);
        __android_log_print(ANDROID_LOG_INFO,
                            kWsjtLogTag,
                            "pass decode role=%s iterations=%d apTexts=%d decoded=%d apDecoded=%d duplicates=%d new=%d subtractJobs=%d sessionResults=%d",
                            pass_role_name(pass.role),
                            pass.iterations,
                            (int) ap_pass_texts.size(),
                            pass_stats.decode_success_count,
                            pass_stats.ap_success_count,
                            pass_stats.duplicate_count,
                            new_results,
                            (int) pass_jobs.size(),
                            (int) state->session_results.size());
        if (new_results == 0) {
            if (should_continue_after_empty_pass(pass)) {
                __android_log_print(ANDROID_LOG_INFO,
                                    kWsjtLogTag,
                                    "pass continue role=%s reason=no_new_results",
                                    pass_role_name(pass.role));
                continue;
            }
            __android_log_print(ANDROID_LOG_INFO,
                                kWsjtLogTag,
                                "pass break role=%s reason=no_new_results",
                                pass_role_name(pass.role));
            break;
        }

        append_subtract_history(&subtract_history, pass_jobs);
        if (pass.subtract_after_decode) {
            apply_subtract_jobs(state, pass_jobs, plan.mode.subtract_mode);
        }
    }

    if (enable_ft8_followup && has_full_slot_samples(state) && !followup_history.empty()) {
        __android_log_print(ANDROID_LOG_INFO,
                            kWsjtLogTag,
                            "followup start history=%d subtractHistory=%d",
                            (int) followup_history.size(),
                            (int) subtract_history.size());
        run_ft8_followup_pass(state, subtract_history, followup_history);
    }

    if (state_is_ft8(state) || state_is_ft4(state)) {
        commit_ft8_followup_history(state);
    }

    state->num_candidates = (int) state->session_results.size();
    __android_log_print(ANDROID_LOG_INFO,
                        kWsjtLogTag,
                        "session end results=%d",
                        state->num_candidates);
}

}  // namespace

bool wsjtx_port_init_decoder(decoder_t *decoder,
                             int64_t utcTime,
                             int sample_rate,
                             int num_samples,
                             bool is_ft8) {
    if (decoder == nullptr) {
        return false;
    }

    auto *state = new (std::nothrow) wsjtx_port_decoder_t{};
    if (state == nullptr) {
        return false;
    }

    state->utc_time = utcTime;
    state->slot_samples = num_samples;
    state->num_samples = num_samples;
    state->ldpc_iterations = fast_kLDPC_iterations;
    state->mon_cfg.f_min = 100;
    state->mon_cfg.f_max = 3000;
    state->mon_cfg.sample_rate = sample_rate;
    state->mon_cfg.time_osr = kTime_osr;
    state->mon_cfg.freq_osr = kFreq_osr;
    state->mon_cfg.protocol = is_ft8 ? PROTO_FT8 : PROTO_FT4;
    state->options = wsjtx::DefaultDecoderOptions();
    monitor_init(&state->mon, &state->mon_cfg);
    state->raw_samples.reserve(std::max(num_samples, 0));
    state->session_results.reserve(kMax_decoded_messages);
    decoder->backend_state = state;
    return true;
}

void wsjtx_port_free_decoder(decoder_t *decoder) {
    auto *state = get_state(decoder);
    if (state == nullptr) {
        return;
    }

    monitor_free(&state->mon);
    delete state;
    decoder->backend_state = nullptr;
}

void wsjtx_port_monitor_press(decoder_t *decoder, const float *signal, int sample_count) {
    auto *state = get_state(decoder);
    if (state == nullptr || signal == nullptr || sample_count <= 0) {
        return;
    }

    state->num_samples = sample_count;
    state->num_candidates = 0;
    state->session_results.clear();
    state->raw_samples.assign(signal, signal + sample_count);
    rebuild_fullslot_waterfall(state);
}

int wsjtx_port_find_sync(decoder_t *decoder) {
    auto *state = get_state(decoder);
    if (state == nullptr) {
        return 0;
    }

    if (state_owns_session_flow(state)) {
        run_session_passes(state);
        return state->num_candidates;
    }

    return 0;
}

ft8_message wsjtx_port_analyze(decoder_t *decoder, int idx) {
    ft8_message ft8Message;
    std::memset(&ft8Message, 0, sizeof(ft8Message));

    auto *state = get_state(decoder);
    if (state == nullptr) {
        return ft8Message;
    }

    if (state_owns_session_flow(state)) {
        if (idx < 0 || idx >= (int) state->session_results.size()) {
            return ft8Message;
        }

        ft8Message = state->session_results[idx];
        std::memcpy(state->current_a91, ft8Message.message.a91, FTX_LDPC_K_BYTES);
        return ft8Message;
    }

    ft8Message.utcTime = state->utc_time;
    if (idx < 0 || idx >= state->num_candidates) {
        return ft8Message;
    }

    if (!decode_candidate_with_waterfall(state,
                                         &state->mon.wf,
                                         state->candidate_list[idx],
                                         state->ldpc_iterations,
                                         &ft8Message)) {
        return ft8Message;
    }

    remember_decode(state, ft8Message);
    std::memcpy(state->current_a91, ft8Message.message.a91, FTX_LDPC_K_BYTES);
    return ft8Message;
}

void wsjtx_port_reset(decoder_t *decoder, long utcTime, int num_samples) {
    auto *state = get_state(decoder);
    if (state == nullptr) {
        return;
    }

    state->utc_time = utcTime;
    state->slot_samples = num_samples;
    state->num_samples = num_samples;
    state->num_candidates = 0;
    state->raw_samples.clear();
    state->session_results.clear();
    std::memset(state->current_a91, 0, sizeof(state->current_a91));
    reset_dedupe_state(state);
    monitor_reset(&state->mon);
}

void wsjtx_port_get_a91(decoder_t *decoder, uint8_t out[FTX_LDPC_K_BYTES]) {
    auto *state = get_state(decoder);
    if (state == nullptr || out == nullptr) {
        return;
    }
    std::memcpy(out, state->current_a91, FTX_LDPC_K_BYTES);
}

void wsjtx_port_set_ldpc_iterations(decoder_t *decoder, int iterations) {
    auto *state = get_state(decoder);
    if (state == nullptr) {
        return;
    }
    state->ldpc_iterations = iterations;
}

void wsjtx_port_set_ap_hints(decoder_t *decoder, const ap_hints_t *ap_hints) {
    auto *state = get_state(decoder);
    if (state == nullptr) {
        return;
    }

    if (ap_hints == nullptr) {
        std::memset(&state->ap_hints, 0, sizeof(state->ap_hints));
        return;
    }
    std::memcpy(&state->ap_hints, ap_hints, sizeof(state->ap_hints));
}

void wsjtx_port_set_options(decoder_t *decoder, const wsjtx_decoder_options_t *options) {
    auto *state = get_state(decoder);
    if (state == nullptr) {
        return;
    }
    state->options = convert_decoder_options(options);
}

bool wsjtx_port_owns_session_flow(decoder_t *decoder) {
    auto *state = get_state(decoder);
    if (state == nullptr) {
        return false;
    }
    return state_owns_session_flow(state);
}

void wsjtx_port_subtract_signal(decoder_t *decoder,
                                const uint8_t *payload,
                                int sample_rate,
                                float frequency,
                                float time_sec,
                                int mode) {
    auto *state = get_state(decoder);
    if (state == nullptr || payload == nullptr) {
        return;
    }

    subtract_ftx_signal(state, payload, sample_rate, frequency, time_sec, mode);
}

