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
    int max_candidates;
    int iterations;
    int phase_ticks;
    bool apply_subtract_history;
    bool subtract_after_decode;
};

struct DecoderOptions {
    int decode_pass_count;
    int multi_decode_round_count;
    int qso_freq_sensitivity;
    int decode_sensitivity;
    bool enable_early_decode;
    bool enable_wideband_dx_search;
};

struct SessionPlan {
    ModeDescriptor mode;
    std::vector<SessionPass> passes;
};

ModeDescriptor ResolveModeDescriptor(ftx_protocol_t protocol);
bool HasApHints(const char *my_call, int hint_call_count);
DecoderOptions DefaultDecoderOptions();
SessionPlan BuildSessionPlan(const ModeDescriptor &mode,
                             bool deep_mode,
                             bool has_ap_hints,
                             const DecoderOptions &options);

}  // namespace wsjtx

#endif

