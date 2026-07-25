#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include "../ft8Decoder.h"
#include "../ft8Encoder.h"
#include "../ft8/constants.h"
#include "../ft8/encode.h"
#include "../ft8/pack.h"
}

namespace {

constexpr int kSampleRate = 12000;
constexpr float kSeedAmplitude = 1.0f;
constexpr float kSeedNoise = 0.01f;
constexpr float kWeakAmplitude = 0.18f;
constexpr float kWeakNoise = 0.16f;
constexpr float kRegressionFrequency = 1500.0f;
constexpr float kFollowupFrequency = kRegressionFrequency + 180.0f;
constexpr const char *kMyCall = "N0CALL";
constexpr const char *kOtherCall = "K1ABC";
constexpr const char *kOtherGrid = "FN20";

enum class RegressionMode {
    kFt8,
    kFt4
};

struct DecodeSummary {
    int candidate_count = 0;
    int valid_count = 0;
    int blank_count = 0;
    bool expected_found = false;
    std::vector<std::string> texts;
};

struct RegressionCase {
    RegressionMode mode;
    const char *name;
    const char *seed_text;
    const char *target_text;
    const char *expected_text;
};

static int slot_samples_for_mode(RegressionMode mode) {
    return (mode == RegressionMode::kFt4)
           ? (int) std::lround(FT4_SLOT_TIME * kSampleRate)
           : (int) std::lround(FT8_SLOT_TIME * kSampleRate);
}

static int slot_milliseconds_for_mode(RegressionMode mode) {
    return (mode == RegressionMode::kFt4) ? 7500 : 15000;
}

static const char *mode_name(RegressionMode mode) {
    return (mode == RegressionMode::kFt4) ? "FT4" : "FT8";
}

static uint32_t next_random(uint32_t *state) {
    uint32_t x = *state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *state = x;
    return x;
}

static float deterministic_noise(uint32_t *state) {
    const uint32_t value = next_random(state);
    const float normalized = (float) (value & 0xFFFFu) / 32767.5f - 1.0f;
    return normalized;
}

static bool synthesize_message(const char *text,
                               RegressionMode mode,
                               float frequency,
                               float amplitude,
                               float noise_amplitude,
                               uint32_t noise_seed,
                               std::vector<float> *slot) {
    if (text == nullptr || slot == nullptr) {
        return false;
    }

    uint8_t packed[FTX_LDPC_K_BYTES];
    std::memset(packed, 0, sizeof(packed));
    if (pack77(text, packed) != 0) {
        return false;
    }

    const int nn = (mode == RegressionMode::kFt4) ? FT4_NN : FT8_NN;
    const float symbol_period = (mode == RegressionMode::kFt4) ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
    const float symbol_bt = (mode == RegressionMode::kFt4) ? 1.0f : FT8_SYMBOL_BT;
    std::vector<uint8_t> tones((size_t) nn);
    if (mode == RegressionMode::kFt4) {
        ft4_encode(packed, tones.data());
    } else {
        ft8_encode(packed, tones.data());
    }

    const int wave_samples = (int) std::lround(nn * symbol_period * kSampleRate);
    std::vector<float> wave((size_t) wave_samples, 0.0f);
    if (synth_gfsk(tones.data(), nn, frequency, symbol_bt, symbol_period, kSampleRate, wave.data()) !=
        wave_samples) {
        return false;
    }

    slot->assign((size_t) slot_samples_for_mode(mode), 0.0f);
    const int copy_count = std::min((int) slot->size(), wave_samples);
    for (int i = 0; i < copy_count; ++i) {
        (*slot)[(size_t) i] += amplitude * wave[(size_t) i];
    }

    uint32_t rng = noise_seed;
    for (float &sample : *slot) {
        sample += noise_amplitude * deterministic_noise(&rng);
    }
    return true;
}

static ap_hints_t make_ap_hints(void) {
    ap_hints_t hints;
    std::memset(&hints, 0, sizeof(hints));
    std::snprintf(hints.my_call, sizeof(hints.my_call), "%s", kMyCall);
    hints.hint_call_count = 1;
    std::snprintf(hints.hint_calls[0], sizeof(hints.hint_calls[0]), "%s", kOtherCall);
    std::snprintf(hints.hint_grids[0], sizeof(hints.hint_grids[0]), "%s", kOtherGrid);
    return hints;
}

static wsjtx_decoder_options_t make_decoder_options(bool enable_ap) {
    wsjtx_decoder_options_t options;
    options.decode_pass_count = 3;
    options.multi_decode_round_count = 3;
    options.qso_freq_sensitivity = 1;
    options.decode_sensitivity = 1;
    options.enable_early_decode = true;
    options.enable_wideband_dx_search = enable_ap;
    return options;
}

static bool is_blank_text(const char *text) {
    if (text == nullptr) {
        return true;
    }
    while (*text != '\0') {
        if (!std::isspace((unsigned char) *text)) {
            return false;
        }
        ++text;
    }
    return true;
}

static DecodeSummary decode_slot(const std::vector<float> &slot,
                                 RegressionMode mode,
                                 int64_t utc_time,
                                 bool enable_ap,
                                 const char *expected_text) {
    DecodeSummary summary;
    decoder_t *decoder = (decoder_t *) init_decoder(utc_time,
                                                    kSampleRate,
                                                    (int) slot.size(),
                                                    mode == RegressionMode::kFt8);
    if (decoder == nullptr) {
        return summary;
    }

    const ap_hints_t hints = make_ap_hints();
    const wsjtx_decoder_options_t options = make_decoder_options(enable_ap);
    decoder_set_ap_hints(decoder, enable_ap ? &hints : nullptr);
    decoder_set_wsjtx_options(decoder, &options);
    decoder_set_ldpc_iterations(decoder, true);
    decoder_monitor_press(const_cast<float *>(slot.data()), decoder);

    summary.candidate_count = decoder_ft8_find_sync(decoder);
    for (int idx = 0; idx < summary.candidate_count; ++idx) {
        ft8_message message = decoder_ft8_analysis(idx, decoder);
        if (!message.isValid) {
            continue;
        }

        ++summary.valid_count;
        if (is_blank_text(message.message.text)) {
            ++summary.blank_count;
            continue;
        }

        summary.texts.emplace_back(message.message.text);
        if (expected_text != nullptr && std::strcmp(message.message.text, expected_text) == 0) {
            summary.expected_found = true;
        }
    }

    delete_decoder(decoder);
    return summary;
}

static void append_texts(std::string *out, const DecodeSummary &summary) {
    if (out == nullptr) {
        return;
    }
    if (summary.texts.empty()) {
        out->append(" texts=[]");
        return;
    }

    out->append(" texts=[");
    const int limit = std::min((int) summary.texts.size(), 4);
    for (int i = 0; i < limit; ++i) {
        if (i > 0) {
            out->append("; ");
        }
        out->append(summary.texts[(size_t) i]);
    }
    if ((int) summary.texts.size() > limit) {
        out->append("; ...");
    }
    out->append("]");
}

static void append_summary_line(std::string *out,
                                const RegressionCase &test_case,
                                const DecodeSummary &baseline,
                                const DecodeSummary &with_ap) {
    char line[256];
    std::snprintf(line,
                  sizeof(line),
                  "%s %-18s baseline:%s/%d blank=%d ap:%s/%d blank=%d",
                  mode_name(test_case.mode),
                  test_case.name,
                  baseline.expected_found ? "hit" : "miss",
                  baseline.valid_count,
                  baseline.blank_count,
                  with_ap.expected_found ? "hit" : "miss",
                  with_ap.valid_count,
                  with_ap.blank_count);
    out->append(line);
    append_texts(out, with_ap);
    out->push_back('\n');
}

static std::string run_case(const RegressionCase &test_case, int case_index) {
    const int64_t base_utc = 1800000000000LL +
                             (int64_t) case_index * 8LL * slot_milliseconds_for_mode(test_case.mode);
    const int64_t follow_utc = base_utc + slot_milliseconds_for_mode(test_case.mode);

    std::vector<float> seed_slot;
    std::vector<float> target_slot;
    const bool seed_ok = synthesize_message(test_case.seed_text,
                                            test_case.mode,
                                            kRegressionFrequency,
                                            kSeedAmplitude,
                                            kSeedNoise,
                                            0x1000u + (uint32_t) case_index,
                                            &seed_slot);
    const bool target_ok = synthesize_message(test_case.target_text,
                                              test_case.mode,
                                              kFollowupFrequency,
                                              kWeakAmplitude,
                                              kWeakNoise,
                                              0x2000u + (uint32_t) case_index,
                                              &target_slot);
    if (!seed_ok || !target_ok) {
        char failure[192];
        std::snprintf(failure,
                      sizeof(failure),
                      "%s %-18s synth-failed seed=%s target=%s\n",
                      mode_name(test_case.mode),
                      test_case.name,
                      seed_ok ? "ok" : "fail",
                      target_ok ? "ok" : "fail");
        return failure;
    }

    const DecodeSummary baseline = decode_slot(target_slot,
                                               test_case.mode,
                                               follow_utc,
                                               false,
                                               test_case.expected_text);
    (void) decode_slot(seed_slot, test_case.mode, base_utc, true, test_case.seed_text);
    const DecodeSummary with_ap = decode_slot(target_slot,
                                             test_case.mode,
                                             follow_utc,
                                             true,
                                             test_case.expected_text);

    std::string line;
    append_summary_line(&line, test_case, baseline, with_ap);
    return line;
}

static std::string run_synthetic_suite(void) {
    const RegressionCase cases[] = {
            {RegressionMode::kFt8, "CQ -> reply", "CQ K1ABC FN20", "K1ABC N0CALL -12", "K1ABC N0CALL -12"},
            {RegressionMode::kFt8, "report -> R", "N0CALL K1ABC -12", "K1ABC N0CALL R-10", "K1ABC N0CALL R-10"},
            {RegressionMode::kFt8, "RR73/73", "N0CALL K1ABC R-10", "K1ABC N0CALL RR73", "K1ABC N0CALL RR73"},
            {RegressionMode::kFt4, "CQ -> reply", "CQ K1ABC FN20", "K1ABC N0CALL -12", "K1ABC N0CALL -12"},
            {RegressionMode::kFt4, "report -> R", "N0CALL K1ABC -12", "K1ABC N0CALL R-10", "K1ABC N0CALL R-10"},
            {RegressionMode::kFt4, "RR73/73", "N0CALL K1ABC R-10", "K1ABC N0CALL 73", "K1ABC N0CALL 73"},
    };

    std::string out;
    out.append("AP synthetic regression weakAmp=0.18 weakNoise=0.16 followOffsetHz=180\n");
    for (int i = 0; i < (int) (sizeof(cases) / sizeof(cases[0])); ++i) {
        out.append(run_case(cases[i], i));
    }
    return out;
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bg7yoz_ft8cn_diagnostics_NativeApRegression_runSyntheticSuite(JNIEnv *env, jclass) {
    const std::string result = run_synthetic_suite();
    return env->NewStringUTF(result.c_str());
}

