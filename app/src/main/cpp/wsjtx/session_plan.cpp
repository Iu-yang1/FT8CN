#include "session_plan.h"

#include "../ft8Decoder.h"

#include <algorithm>
#include <cstring>

namespace wsjtx {

namespace {

constexpr int kFt8PhaseTicksFull = 50;
constexpr int kFt8PhaseTicksEarly = 41;
constexpr int kFt8PhaseTicksLate = 47;

static SessionPass MakePass(PassRole role,
                            CandidateSource candidate_source,
                            int min_sync_score,
                            int iterations,
                            int phase_ticks,
                            bool apply_subtract_history,
                            bool subtract_after_decode,
                            bool limit_early_dt) {
    SessionPass pass{};
    pass.role = role;
    pass.candidate_source = candidate_source;
    pass.min_sync_score = min_sync_score;
    pass.iterations = iterations;
    pass.phase_ticks = phase_ticks;
    pass.apply_subtract_history = apply_subtract_history;
    pass.subtract_after_decode = subtract_after_decode;
    pass.limit_early_dt = limit_early_dt;
    return pass;
}

static int ClampDecodePassCount(int count) {
    return std::max(1, std::min(count, 3));
}

static int ClampRoundCount(int count) {
    return std::max(1, std::min(count, 3));
}

static int SyncBiasFromSensitivity(int sensitivity) {
    switch (sensitivity) {
        case 0:
            return 1;
        case 2:
            return -1;
        default:
            return 0;
    }
}

static SessionPlan BuildFt8Plan(const ModeDescriptor &mode,
                                bool deep_mode,
                                bool has_ap_hints,
                                const DecoderOptions &options) {
    SessionPlan plan{};
    plan.mode = mode;
    const int sync_bias = SyncBiasFromSensitivity(options.decode_sensitivity);
    const int pass_count = ClampDecodePassCount(options.decode_pass_count);
    const int round_count = ClampRoundCount(options.multi_decode_round_count);
    const bool enable_ap_pass = has_ap_hints && mode.supports_ap_followup && options.enable_wideband_dx_search;
    const int base_sync = std::max(6, mode.base_sync_score + sync_bias);

    if (!deep_mode) {
        plan.passes.push_back(MakePass(PassRole::kBase,
                                       CandidateSource::kWaterfall,
                                       base_sync,
                                       fast_kLDPC_iterations,
                                       kFt8PhaseTicksFull,
                                       false,
                                       false,
                                       false));
        return plan;
    }

    if (options.enable_early_decode && mode.supports_partial_slot) {
        plan.passes.push_back(MakePass(PassRole::kEarly,
                                       CandidateSource::kWaterfall,
                                       std::max(base_sync, 12 + sync_bias),
                                       fast_kLDPC_iterations,
                                       kFt8PhaseTicksEarly,
                                       false,
                                       false,
                                       true));
        plan.passes.push_back(MakePass(PassRole::kLate,
                                       CandidateSource::kWaterfall,
                                       std::max(base_sync, 11 + sync_bias),
                                       fast_kLDPC_iterations,
                                       kFt8PhaseTicksLate,
                                       true,
                                       false,
                                       false));
    }
    plan.passes.push_back(MakePass(PassRole::kBase,
                                   CandidateSource::kWaterfall,
                                   base_sync,
                                   deep_kLDPC_iterations,
                                   kFt8PhaseTicksFull,
                                   true,
                                   true,
                                   false));

    if (round_count >= 2 && pass_count >= 2 && enable_ap_pass) {
        plan.passes.push_back(MakePass(PassRole::kAp,
                                       CandidateSource::kWaterfall,
                                       std::max(7, base_sync - 1),
                                       deep_kLDPC_iterations,
                                       0,
                                       false,
                                       false,
                                       false));
    }

    if (round_count >= 2 && pass_count >= 2) {
        plan.passes.push_back(MakePass(PassRole::kSubtract,
                                       CandidateSource::kWaterfall,
                                       std::max(7, base_sync - 1),
                                       deep_kLDPC_iterations,
                                       0,
                                       false,
                                       true,
                                       false));
    }

    if (round_count >= 3 && pass_count >= 3 && enable_ap_pass) {
        plan.passes.push_back(MakePass(PassRole::kAp,
                                       CandidateSource::kWaterfall,
                                       std::max(6, base_sync - 2),
                                       deep_kLDPC_iterations,
                                       0,
                                       false,
                                       false,
                                       false));
    }

    return plan;
}

static SessionPlan BuildFt4Plan(const ModeDescriptor &mode,
                                bool deep_mode,
                                bool has_ap_hints,
                                const DecoderOptions &options) {
    SessionPlan plan{};
    plan.mode = mode;
    const int sync_bias = SyncBiasFromSensitivity(options.decode_sensitivity);
    const int pass_count = ClampDecodePassCount(options.decode_pass_count);
    const int round_count = ClampRoundCount(options.multi_decode_round_count);
    const bool enable_ap_pass = has_ap_hints && mode.supports_ap_followup && options.enable_wideband_dx_search;
    const int base_sync = std::max(6, mode.base_sync_score + sync_bias);

    plan.passes.push_back(MakePass(PassRole::kBase,
                                   CandidateSource::kFt4RawFft,
                                   std::max(base_sync, 10 + sync_bias),
                                   fast_kLDPC_iterations,
                                   kFt8PhaseTicksFull,
                                   false,
                                   deep_mode && round_count >= 2,
                                   false));

    if (!deep_mode) {
        return plan;
    }

    if (round_count >= 2 && pass_count >= 2 && enable_ap_pass) {
        plan.passes.push_back(MakePass(PassRole::kAp,
                                       CandidateSource::kFt4RawFft,
                                       std::max(8, base_sync - 1),
                                       deep_kLDPC_iterations,
                                       0,
                                       false,
                                       false,
                                       false));
    }

    if (round_count >= 2 && pass_count >= 2) {
        plan.passes.push_back(MakePass(PassRole::kSubtract,
                                       CandidateSource::kFt4RawFft,
                                       std::max(base_sync, 9 + sync_bias),
                                       deep_kLDPC_iterations,
                                       0,
                                       false,
                                       true,
                                       false));
    }

    if (round_count >= 3 && pass_count >= 3 && enable_ap_pass) {
        plan.passes.push_back(MakePass(PassRole::kAp,
                                       CandidateSource::kFt4RawFft,
                                       std::max(6, base_sync + sync_bias),
                                       deep_kLDPC_iterations,
                                       0,
                                       false,
                                       false,
                                       false));
    }

    return plan;
}

}  // namespace

ModeDescriptor ResolveModeDescriptor(ftx_protocol_t protocol) {
    switch (protocol) {
        case PROTO_FT8:
            return {ModeId::kFt8, PROTO_FT8, 0, kMin_score, true, true, true};
        case PROTO_FT4:
            return {ModeId::kFt4, PROTO_FT4, 1, 8, true, false, true};
        default:
            return {ModeId::kUnknown, PROTO_FT8, 0, kMin_score, false, false, false};
    }
}

bool HasApHints(const char *my_call, int hint_call_count) {
    return (my_call != nullptr && my_call[0] != '\0') || hint_call_count > 0;
}

DecoderOptions DefaultDecoderOptions() {
    return {3, 3, 1, 1, true, true};
}

SessionPlan BuildSessionPlan(const ModeDescriptor &mode,
                             bool deep_mode,
                             bool has_ap_hints,
                             const DecoderOptions &options) {
    switch (mode.mode_id) {
        case ModeId::kFt8:
            return BuildFt8Plan(mode, deep_mode, has_ap_hints, options);
        case ModeId::kFt4:
            return BuildFt4Plan(mode, deep_mode, has_ap_hints, options);
        case ModeId::kQ65:
        case ModeId::kFst4:
        case ModeId::kUnknown:
        default: {
            SessionPlan plan{};
            plan.mode = mode;
            return plan;
        }
    }
}

}  // namespace wsjtx
