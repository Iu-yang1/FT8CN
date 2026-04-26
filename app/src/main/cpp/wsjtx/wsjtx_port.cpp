#include "wsjtx_port.h"
#include "session_plan.h"

#include <algorithm>
#include <cmath>
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

constexpr float kFt4PeakMatchHz = 35.0f;
constexpr int kFt4CoarseFftSize = 2304;
constexpr int kFt4CoarseHopSize = 576;
constexpr int kFt4DownsampleFactor = 18;
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

static float abs_diff(float lhs, float rhs) {
    float delta = lhs - rhs;
    return (delta < 0.0f) ? -delta : delta;
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

static float ft4_sync_ratio_threshold(int min_score) {
    if (min_score >= 10) {
        return 1.20f;
    }
    if (min_score >= 9) {
        return 1.15f;
    }
    return 1.10f;
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
    const float sync_min = ft4_sync_ratio_threshold(min_score);

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

    std::sort(peaks.begin(), peaks.end(), [](const ft4_peak_t &lhs, const ft4_peak_t &rhs) {
        if (lhs.strength != rhs.strength) {
            return lhs.strength > rhs.strength;
        }
        return lhs.frequency < rhs.frequency;
    });
    return peaks;
}

static float candidate_ft4_peak_strength(const wsjtx_port_decoder_t *state,
                                         const candidate_t &candidate,
                                         const std::vector<ft4_peak_t> &peaks) {
    const float frequency = candidate_freq_hz(state, candidate);
    float best_strength = -1.0f;
    for (const ft4_peak_t &peak : peaks) {
        if (abs_diff(frequency, peak.frequency) <= kFt4PeakMatchHz) {
            best_strength = std::max(best_strength, peak.strength);
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
    };

    std::vector<ranked_candidate_t> ranked;
    ranked.reserve(generic_count);

    for (int idx = 0; idx < generic_count; ++idx) {
        const float peak_strength = candidate_ft4_peak_strength(state, generic_candidates[idx], peaks);
        const bool matched_peak = peak_strength > 0.0f;
        if (!matched_peak && generic_candidates[idx].score < min_score + 3) {
            continue;
        }
        ranked.push_back({generic_candidates[idx], peak_strength, matched_peak});
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
                         if (lhs.peak_strength != rhs.peak_strength) {
                             return lhs.peak_strength > rhs.peak_strength;
                         }
                         if (lhs.candidate.score != rhs.candidate.score) {
                             return lhs.candidate.score > rhs.candidate.score;
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
                                       candidate_t out[kMax_candidates]) {
    switch (pass.candidate_source) {
        case wsjtx::CandidateSource::kFt4RawFft:
            return collect_ft4_candidates(state, pass.min_sync_score, out);
        case wsjtx::CandidateSource::kWaterfall:
        default:
            return collect_sorted_candidates(state, pass.min_sync_score, out);
    }
}

static void append_subtract_history(std::vector<subtract_job_t> *history,
                                    const std::vector<subtract_job_t> &pass_jobs) {
    if (history == nullptr || pass_jobs.empty()) {
        return;
    }
    history->insert(history->end(), pass_jobs.begin(), pass_jobs.end());
}

static void rebuild_for_pass(wsjtx_port_decoder_t *state, const wsjtx::SessionPass &pass) {
    if (state == nullptr) {
        return;
    }

    if (pass.phase_ticks <= 0) {
        return;
    }

    if (pass.phase_ticks >= kFt8PhaseTicksFull) {
        rebuild_fullslot_waterfall(state);
        return;
    }
    rebuild_waterfall(state, sample_count_for_phase(state, pass.phase_ticks));
}

static bool should_continue_after_empty_pass(const wsjtx::SessionPass &pass) {
    return pass.role == wsjtx::PassRole::kEarly || pass.role == wsjtx::PassRole::kLate;
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
    const wsjtx::SessionPlan plan = wsjtx::BuildSessionPlan(mode, deep_mode, has_ap_hints);

    if (plan.passes.empty()) {
        state->num_candidates = 0;
        return;
    }

    std::vector<subtract_job_t> subtract_history;

    for (const wsjtx::SessionPass &pass : plan.passes) {
        rebuild_for_pass(state, pass);
        if (pass.apply_subtract_history && !subtract_history.empty()) {
            apply_subtract_jobs(state, subtract_history, plan.mode.subtract_mode);
        }

        const int candidate_count = collect_candidates_for_pass(state, pass, state->candidate_list);
        if (candidate_count <= 0) {
            if (should_continue_after_empty_pass(pass)) {
                continue;
            }
            break;
        }

        std::vector<subtract_job_t> pass_jobs;
        const int new_results = decode_candidates(state,
                                                  state->candidate_list,
                                                  candidate_count,
                                                  pass.iterations,
                                                  pass.limit_early_dt ? kFt8EarlySubtractDtSec : -1.0f,
                                                  &pass_jobs);
        if (new_results == 0) {
            if (should_continue_after_empty_pass(pass)) {
                continue;
            }
            break;
        }

        append_subtract_history(&subtract_history, pass_jobs);
        if (pass.subtract_after_decode) {
            apply_subtract_jobs(state, pass_jobs, plan.mode.subtract_mode);
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
