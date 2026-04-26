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

static SessionPlan BuildFt8Plan(const ModeDescriptor &mode, bool deep_mode, bool has_ap_hints) {
    SessionPlan plan{};
    plan.mode = mode;

    if (!deep_mode) {
        plan.passes.push_back(MakePass(PassRole::kBase,
                                       CandidateSource::kWaterfall,
                                       mode.base_sync_score,
                                       fast_kLDPC_iterations,
                                       kFt8PhaseTicksFull,
                                       false,
                                       false,
                                       false));
        return plan;
    }

    plan.passes.push_back(MakePass(PassRole::kEarly,
                                   CandidateSource::kWaterfall,
                                   std::max(mode.base_sync_score, 12),
                                   fast_kLDPC_iterations,
                                   kFt8PhaseTicksEarly,
                                   false,
                                   false,
                                   true));
    plan.passes.push_back(MakePass(PassRole::kLate,
                                   CandidateSource::kWaterfall,
                                   std::max(mode.base_sync_score, 11),
                                   fast_kLDPC_iterations,
                                   kFt8PhaseTicksLate,
                                   true,
                                   false,
                                   false));
    plan.passes.push_back(MakePass(PassRole::kBase,
                                   CandidateSource::kWaterfall,
                                   mode.base_sync_score,
                                   deep_kLDPC_iterations,
                                   kFt8PhaseTicksFull,
                                   true,
                                   true,
                                   false));
    plan.passes.push_back(MakePass(PassRole::kSubtract,
                                   CandidateSource::kWaterfall,
                                   std::max(8, mode.base_sync_score - 1),
                                   deep_kLDPC_iterations,
                                   0,
                                   false,
                                   true,
                                   false));

    if (has_ap_hints && mode.supports_ap_followup) {
        plan.passes.push_back(MakePass(PassRole::kAp,
                                       CandidateSource::kWaterfall,
                                       std::max(7, mode.base_sync_score - 2),
                                       deep_kLDPC_iterations,
                                       0,
                                       false,
                                       false,
                                       false));
    }

    return plan;
}

static SessionPlan BuildFt4Plan(const ModeDescriptor &mode, bool deep_mode, bool has_ap_hints) {
    SessionPlan plan{};
    plan.mode = mode;

    plan.passes.push_back(MakePass(PassRole::kBase,
                                   CandidateSource::kFt4RawFft,
                                   std::max(mode.base_sync_score, 10),
                                   fast_kLDPC_iterations,
                                   kFt8PhaseTicksFull,
                                   false,
                                   deep_mode,
                                   false));

    if (!deep_mode) {
        return plan;
    }

    plan.passes.push_back(MakePass(PassRole::kSubtract,
                                   CandidateSource::kFt4RawFft,
                                   std::max(mode.base_sync_score, 9),
                                   deep_kLDPC_iterations,
                                   0,
                                   false,
                                   true,
                                   false));

    if (has_ap_hints && mode.supports_ap_followup) {
        plan.passes.push_back(MakePass(PassRole::kAp,
                                       CandidateSource::kFt4RawFft,
                                       std::max(7, mode.base_sync_score),
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

SessionPlan BuildSessionPlan(const ModeDescriptor &mode, bool deep_mode, bool has_ap_hints) {
    switch (mode.mode_id) {
        case ModeId::kFt8:
            return BuildFt8Plan(mode, deep_mode, has_ap_hints);
        case ModeId::kFt4:
            return BuildFt4Plan(mode, deep_mode, has_ap_hints);
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
