#ifndef FT8CN_WSJTX_SESSION_PLAN_H
#define FT8CN_WSJTX_SESSION_PLAN_H

#include "../ft8/constants.h"

#include <vector>

namespace wsjtx {

enum class ModeId {
    kUnknown = 0,
    kFt8,
    kFt4,
    kQ65,
    kFst4,
};

enum class CandidateSource {
    kWaterfall = 0,
    kFt4RawFft,
};

enum class PassRole {
    kBase = 0,
    kSubtract,
    kAp,
    kEarly,
    kLate,
};

struct ModeDescriptor {
    ModeId mode_id;
    ftx_protocol_t protocol;
    int subtract_mode;
    int base_sync_score;
    bool owns_session_flow;
    bool supports_partial_slot;
    bool supports_ap_followup;
};

struct SessionPass {
    PassRole role;
    CandidateSource candidate_source;
    int min_sync_score;
    int iterations;
    int phase_ticks;
    bool apply_subtract_history;
    bool subtract_after_decode;
    bool limit_early_dt;
};

struct SessionPlan {
    ModeDescriptor mode;
    std::vector<SessionPass> passes;
};

ModeDescriptor ResolveModeDescriptor(ftx_protocol_t protocol);
bool HasApHints(const char *my_call, int hint_call_count);
SessionPlan BuildSessionPlan(const ModeDescriptor &mode, bool deep_mode, bool has_ap_hints);

}  // namespace wsjtx

#endif
