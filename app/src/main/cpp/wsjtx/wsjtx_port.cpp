#include "wsjtx_port.h"

#include <algorithm>
#include <cstring>
#include <new>
#include <vector>

extern "C" {
#include "../ft8/encode.h"
}

namespace {

constexpr int kFt8PhaseTicksFull = 50;
constexpr int kFt8PhaseTicksEarly = 41;
constexpr int kFt8PhaseTicksLate = 47;
constexpr float kFt8EarlySubtractDtSec = 0.40f;

struct subtract_job_t {
    uint8_t a91[FTX_LDPC_K_BYTES];
    float frequency;
    float time_sec;
};

struct wsjtx_port_decoder_t {
    int64_t utc_time;
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
};

static wsjtx_port_decoder_t *get_state(decoder_t *decoder) {
    return (decoder == nullptr) ? nullptr : static_cast<wsjtx_port_decoder_t *>(decoder->backend_state);
}

static bool state_is_ft4(const wsjtx_port_decoder_t *state) {
    return state->mon_cfg.protocol == PROTO_FT4;
}

static bool state_is_ft8(const wsjtx_port_decoder_t *state) {
    return state->mon_cfg.protocol == PROTO_FT8;
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
        if (existing.message.hash == candidate.message.hash &&
            0 == std::strcmp(existing.message.text, candidate.message.text)) {
            return true;
        }
    }
    return false;
}

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
    if ((int) state->session_results.size() >= kMax_decoded_messages) {
        return false;
    }
    if (is_duplicate_result(state, decoded)) {
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

static int sample_count_for_phase(const wsjtx_port_decoder_t *state, int phase_ticks) {
    if (state == nullptr || phase_ticks <= 0 || state->raw_samples.empty()) {
        return 0;
    }

    const int total_samples = (int) state->raw_samples.size();
    if (phase_ticks >= kFt8PhaseTicksFull) {
        return total_samples;
    }

    int sample_count = (total_samples * phase_ticks) / kFt8PhaseTicksFull;
    if (sample_count < state->mon.block_size) {
        return 0;
    }
    return sample_count;
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

    int count = ft8_find_sync(&state->mon.wf, kMax_candidates, out, min_score);
    if (count <= 1) {
        return std::max(count, 0);
    }

    std::sort(out, out + count, [](const candidate_t &lhs, const candidate_t &rhs) {
        if (lhs.time_offset != rhs.time_offset) {
            return lhs.time_offset < rhs.time_offset;
        }
        if (lhs.time_sub != rhs.time_sub) {
            return lhs.time_sub < rhs.time_sub;
        }
        if (lhs.score != rhs.score) {
            return lhs.score > rhs.score;
        }
        if (lhs.freq_offset != rhs.freq_offset) {
            return lhs.freq_offset < rhs.freq_offset;
        }
        return lhs.freq_sub < rhs.freq_sub;
    });
    return count;
}

static int decode_candidates(wsjtx_port_decoder_t *state,
                             candidate_t candidates[kMax_candidates],
                             int candidate_count,
                             int iterations,
                             float max_time_sec,
                             std::vector<subtract_job_t> *jobs) {
    if (state == nullptr || candidate_count <= 0) {
        return 0;
    }

    int new_results = 0;
    for (int idx = 0; idx < candidate_count; ++idx) {
        ft8_message decoded;
        if (!decode_candidate_with_waterfall(state,
                                             &state->mon.wf,
                                             candidates[idx],
                                             iterations,
                                             &decoded)) {
            continue;
        }

        if (max_time_sec >= 0.0f && decoded.time_sec > max_time_sec) {
            continue;
        }

        if (!remember_decode(state, decoded)) {
            continue;
        }

        ++new_results;
        remember_subtract_job(decoded, jobs);
    }

    return new_results;
}

static void run_ft8_session_passes(wsjtx_port_decoder_t *state) {
    if (state == nullptr) {
        return;
    }

    state->session_results.clear();
    reset_dedupe_state(state);

    const bool deep_mode = state->ldpc_iterations > fast_kLDPC_iterations;
    const int base_score = base_min_sync_score(state);
    const int fast_threshold = std::max(base_score, 12);

    if (!deep_mode) {
        rebuild_fullslot_waterfall(state);
        state->num_candidates = collect_sorted_candidates(state, base_score, state->candidate_list);
        decode_candidates(state,
                          state->candidate_list,
                          state->num_candidates,
                          fast_kLDPC_iterations,
                          -1.0f,
                          nullptr);
        state->num_candidates = (int) state->session_results.size();
        return;
    }

    std::vector<subtract_job_t> fullslot_jobs;

    const int early_samples = sample_count_for_phase(state, kFt8PhaseTicksEarly);
    if (early_samples > 0) {
        rebuild_waterfall(state, early_samples);
        const int early_count = collect_sorted_candidates(state, fast_threshold, state->candidate_list);
        std::vector<subtract_job_t> early_jobs;
        decode_candidates(state,
                          state->candidate_list,
                          early_count,
                          fast_kLDPC_iterations,
                          kFt8EarlySubtractDtSec,
                          &early_jobs);
        fullslot_jobs.insert(fullslot_jobs.end(), early_jobs.begin(), early_jobs.end());
    }

    const int late_samples = sample_count_for_phase(state, kFt8PhaseTicksLate);
    if (late_samples > 0) {
        rebuild_waterfall(state, late_samples);
        apply_subtract_jobs(state, fullslot_jobs, 0);
        const int late_count = collect_sorted_candidates(state, std::max(base_score, 11), state->candidate_list);
        decode_candidates(state,
                          state->candidate_list,
                          late_count,
                          fast_kLDPC_iterations,
                          -1.0f,
                          &fullslot_jobs);
    }

    rebuild_fullslot_waterfall(state);
    apply_subtract_jobs(state, fullslot_jobs, 0);

    const int pass_thresholds[3] = {
            base_score,
            std::max(8, base_score - 1),
            std::max(7, base_score - 2)
    };

    for (int pass = 0; pass < 3; ++pass) {
        const int candidate_count = collect_sorted_candidates(state,
                                                              pass_thresholds[pass],
                                                              state->candidate_list);
        if (candidate_count <= 0) {
            break;
        }

        std::vector<subtract_job_t> pass_jobs;
        const int new_results = decode_candidates(state,
                                                  state->candidate_list,
                                                  candidate_count,
                                                  state->ldpc_iterations,
                                                  -1.0f,
                                                  &pass_jobs);
        if (new_results == 0) {
            break;
        }

        if (pass + 1 < 3) {
            apply_subtract_jobs(state, pass_jobs, 0);
        }
    }

    state->num_candidates = (int) state->session_results.size();
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
    state->num_samples = num_samples;
    state->ldpc_iterations = fast_kLDPC_iterations;
    state->mon_cfg.f_min = 100;
    state->mon_cfg.f_max = 3000;
    state->mon_cfg.sample_rate = sample_rate;
    state->mon_cfg.time_osr = kTime_osr;
    state->mon_cfg.freq_osr = kFreq_osr;
    state->mon_cfg.protocol = is_ft8 ? PROTO_FT8 : PROTO_FT4;
    monitor_init(&state->mon, &state->mon_cfg);
    state->raw_samples.reserve(std::max(num_samples, 0));
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

    if (state_is_ft8(state)) {
        run_ft8_session_passes(state);
        return state->num_candidates;
    }

    state->session_results.clear();
    reset_dedupe_state(state);
    rebuild_fullslot_waterfall(state);
    state->num_candidates = ft8_find_sync(&state->mon.wf,
                                          kMax_candidates,
                                          state->candidate_list,
                                          base_min_sync_score(state));
    return state->num_candidates;
}

ft8_message wsjtx_port_analyze(decoder_t *decoder, int idx) {
    ft8_message ft8Message;
    std::memset(&ft8Message, 0, sizeof(ft8Message));

    auto *state = get_state(decoder);
    if (state == nullptr) {
        return ft8Message;
    }

    if (state_is_ft8(state)) {
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

bool wsjtx_port_owns_session_flow(decoder_t *decoder) {
    auto *state = get_state(decoder);
    if (state == nullptr) {
        return false;
    }
    return state_is_ft8(state);
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
