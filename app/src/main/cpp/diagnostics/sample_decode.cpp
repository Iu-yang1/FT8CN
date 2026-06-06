#include <jni.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <cstdarg>
#include <chrono>

extern "C" {
#include "../common/wave.h"
#include "../ft8Decoder.h"
#include "../ftx_core/include/ftx_types.h"
#include "../wsjtx3/wsjtx3_bridge.h"
}

namespace {

constexpr int kMaxSupportedRate = 12000;
constexpr int kMaxQ65SampleSeconds = 320;
constexpr int kMaxFt8LikeSampleSeconds = 20;

static bool is_supported_mode(int decode_mode) {
    return decode_mode == FTX_MODE_FT8
           || decode_mode == FTX_MODE_FT4
           || decode_mode == FTX_MODE_Q65;
}

static const char *mode_label(int decode_mode) {
    switch (decode_mode) {
        case FTX_MODE_FT8:
            return "FT8";
        case FTX_MODE_FT4:
            return "FT4";
        case FTX_MODE_Q65:
            return "Q65";
        default:
            return "UNKNOWN";
    }
}

static std::string mode_config_label(int decode_mode, int q65_submode, int q65_tr_period_seconds) {
    if (decode_mode != FTX_MODE_Q65) {
        return mode_label(decode_mode);
    }
    const int normalized_submode = std::max(0, std::min(q65_submode, 5));
    char buffer[32];
    std::snprintf(buffer,
                  sizeof(buffer),
                  "Q65%c/%ds",
                  'A' + normalized_submode,
                  q65_tr_period_seconds);
    return buffer;
}

static int max_sample_seconds_for_mode(int decode_mode) {
    return decode_mode == FTX_MODE_Q65 ? kMaxQ65SampleSeconds : kMaxFt8LikeSampleSeconds;
}

static int expected_samples_for_mode(int decode_mode, int q65_tr_period_seconds) {
    if (decode_mode == FTX_MODE_Q65) {
        return kMaxSupportedRate * std::max(1, q65_tr_period_seconds);
    }
    return kMaxSupportedRate * (decode_mode == FTX_MODE_FT4 ? 8 : 15);
}

static std::string copy_jstring(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char *text = env->GetStringUTFChars(value, nullptr);
    if (text == nullptr) {
        return {};
    }
    std::string result(text);
    env->ReleaseStringUTFChars(value, text);
    return result;
}

static void append_line(std::string *out, const char *fmt, ...) {
    if (out == nullptr || fmt == nullptr) {
        return;
    }

    char buffer[512];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    out->append(buffer);
    out->push_back('\n');
}

static long long elapsed_ms(std::chrono::steady_clock::time_point start_time) {
    return (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start_time).count();
}

static bool has_visible_text(const char *text) {
    if (text == nullptr) {
        return false;
    }
    while (*text != '\0') {
        if (!std::isspace((unsigned char) *text)) {
            return true;
        }
        ++text;
    }
    return false;
}

static const char *backend_name(const decoder_t *decoder) {
    if (decoder == nullptr) {
        return "null";
    }
    switch (decoder->backend) {
        case DECODER_BACKEND_WSJTX3_OFFICIAL:
            return "wsjtx3_official";
        default:
            return "unknown";
    }
}

}  // namespace

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_diagnostics_NativeSampleDecode_configureRuntimeDirectories(JNIEnv *env,
                                                                                 jclass,
                                                                                 jstring tempDir,
                                                                                 jstring dataDir) {
    const std::string temp_dir = copy_jstring(env, tempDir);
    const std::string data_dir = copy_jstring(env, dataDir);
    decoder_configure_runtime_dirs(temp_dir.c_str(), data_dir.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bg7yoz_ft8cn_diagnostics_NativeSampleDecode_inspectWavFile(JNIEnv *env,
                                                                    jclass,
                                                                    jstring wavPath,
                                                                    jint decodeMode,
                                                                    jlong utcTime) {
    std::string output;
    const std::string path = copy_jstring(env, wavPath);
    const int max_sample_seconds = max_sample_seconds_for_mode((int) decodeMode);
    std::vector<float> samples((size_t) kMaxSupportedRate * max_sample_seconds, 0.0f);
    int sample_count = (int) samples.size();
    int sample_rate = 0;
    int load_result;
    decoder_t *decoder;

    if (path.empty()) {
        append_line(&output, "error: wav path is empty");
        return env->NewStringUTF(output.c_str());
    }

    if (!is_supported_mode((int) decodeMode)) {
        append_line(&output, "error: unsupported decode mode %d", (int) decodeMode);
        return env->NewStringUTF(output.c_str());
    }

    load_result = load_wav(samples.data(), &sample_count, &sample_rate, path.c_str());
    append_line(&output,
                "inspect mode=%s path=%s load=%d sampleRate=%d sampleCount=%d maxSeconds=%d",
                mode_label((int) decodeMode),
                path.c_str(),
                load_result,
                sample_rate,
                sample_count,
                max_sample_seconds);
    if (load_result != 0) {
        return env->NewStringUTF(output.c_str());
    }

    decoder = (decoder_t *) init_decoder((int64_t) utcTime,
                                         sample_rate,
                                         sample_count,
                                         (int) decodeMode);
    if (decoder == nullptr) {
        append_line(&output, "inspect backend=init_failed");
        return env->NewStringUTF(output.c_str());
    }

    append_line(&output,
                "inspect backend=%s mode=%s ldpc=%d sampleRate=%d expectedSamples=%d",
                backend_name(decoder),
                mode_label((int) decodeMode),
                decoder->kLDPC_iterations,
                sample_rate,
                sample_count);
    delete_decoder(decoder);
    return env->NewStringUTF(output.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bg7yoz_ft8cn_diagnostics_NativeSampleDecode_decodeWavFile(JNIEnv *env,
                                                                   jclass,
                                                                   jstring wavPath,
                                                                   jint decodeMode,
                                                                   jlong utcTime,
                                                                   jstring myCall,
                                                                   jint decodePassCount,
                                                                   jint multiDecodeRoundCount,
                                                                   jint qsoFreqSensitivity,
                                                                   jint decodeSensitivity,
                                                                   jboolean enableEarlyDecode,
                                                                   jboolean enableWidebandDxSearch,
                                                                   jboolean deepDecodeEnabled,
                                                                   jint q65Submode,
                                                                   jint q65TrPeriodSeconds) {
    const auto start_time = std::chrono::steady_clock::now();
    std::string output;
    const std::string path = copy_jstring(env, wavPath);
    const std::string my_call = copy_jstring(env, myCall);
    const std::string mode_config = mode_config_label((int) decodeMode,
                                                      (int) q65Submode,
                                                      (int) q65TrPeriodSeconds);
    const int expected_samples = expected_samples_for_mode((int) decodeMode,
                                                           (int) q65TrPeriodSeconds);
    long long load_ms = 0;
    long long init_ms = 0;
    long long setup_ms = 0;
    long long core_ms = 0;
    long long result_ms = 0;

    if (path.empty()) {
        append_line(&output, "error: wav path is empty");
        append_line(&output,
                    "smoke summary mode=%s sampleCount=0 bridgeRawCount=0 mergedCount=0 nativeBatchCount=0 javaPublishedCount=0 durationMs=%lld failureReason=empty-wav-path",
                    mode_config.c_str(),
                    elapsed_ms(start_time));
        return env->NewStringUTF(output.c_str());
    }

    if (!is_supported_mode((int) decodeMode)) {
        append_line(&output, "error: unsupported decode mode %d", (int) decodeMode);
        append_line(&output,
                    "smoke summary mode=%s sampleCount=0 bridgeRawCount=0 mergedCount=0 nativeBatchCount=0 javaPublishedCount=0 durationMs=%lld failureReason=unsupported-mode",
                    mode_config.c_str(),
                    elapsed_ms(start_time));
        return env->NewStringUTF(output.c_str());
    }

    const int capacity = kMaxSupportedRate * max_sample_seconds_for_mode((int) decodeMode);
    std::vector<float> samples((size_t) capacity, 0.0f);
    int sample_count = capacity;
    int sample_rate = 0;
    const auto load_started_at = std::chrono::steady_clock::now();
    const int load_result = load_wav(samples.data(), &sample_count, &sample_rate, path.c_str());
    load_ms = elapsed_ms(load_started_at);
    append_line(&output,
                "input mode=%s path=%s load=%d sourceSampleRate=%d expectedSamples=%d actualSamples=%d maxSeconds=%d",
                mode_label((int) decodeMode),
                path.c_str(),
                load_result,
                sample_rate,
                expected_samples,
                sample_count,
                max_sample_seconds_for_mode((int) decodeMode));

    if (load_result != 0) {
        append_line(&output,
                    "smoke summary mode=%s sampleCount=%d bridgeRawCount=0 mergedCount=0 nativeBatchCount=0 javaPublishedCount=0 durationMs=%lld failureReason=wav-load-failed",
                    mode_config.c_str(),
                    sample_count,
                    elapsed_ms(start_time));
        return env->NewStringUTF(output.c_str());
    }

    if (sample_rate != kMaxSupportedRate) {
        append_line(&output,
                    "error: unsupported sample rate %d, current debug decoder expects %d Hz input",
                    sample_rate,
                    kMaxSupportedRate);
        append_line(&output,
                    "smoke summary mode=%s sampleCount=%d bridgeRawCount=0 mergedCount=0 nativeBatchCount=0 javaPublishedCount=0 durationMs=%lld failureReason=unsupported-sample-rate",
                    mode_config.c_str(),
                    sample_count,
                    elapsed_ms(start_time));
        return env->NewStringUTF(output.c_str());
    }

    samples.resize((size_t) sample_count);

    const auto init_started_at = std::chrono::steady_clock::now();
    decoder_t *decoder = (decoder_t *) init_decoder((int64_t) utcTime,
                                                    sample_rate,
                                                    sample_count,
                                                    (int) decodeMode);
    init_ms = elapsed_ms(init_started_at);
    if (decoder == nullptr) {
        append_line(&output, "error: init_decoder failed");
        append_line(&output,
                    "smoke summary mode=%s sampleCount=%d bridgeRawCount=0 mergedCount=0 nativeBatchCount=0 javaPublishedCount=0 durationMs=%lld failureReason=init-decoder-failed",
                    mode_config.c_str(),
                    sample_count,
                    elapsed_ms(start_time));
        return env->NewStringUTF(output.c_str());
    }
    append_line(&output,
                "decoder backend=%s ldpc=%d",
                backend_name(decoder),
                decoder->kLDPC_iterations);

    const auto setup_started_at = std::chrono::steady_clock::now();
    wsjtx_decoder_options_t options{};
    options.decode_pass_count = decodePassCount;
    options.multi_decode_round_count = multiDecodeRoundCount;
    options.qso_freq_sensitivity = qsoFreqSensitivity;
    options.decode_sensitivity = decodeSensitivity;
    options.enable_early_decode = enableEarlyDecode == JNI_TRUE;
    options.enable_wideband_dx_search = enableWidebandDxSearch == JNI_TRUE;
    decoder_set_wsjtx_options(decoder, &options);
    decoder_set_q65_config(decoder, (int) q65Submode, (int) q65TrPeriodSeconds);
    decoder_set_ldpc_iterations(decoder, deepDecodeEnabled == JNI_TRUE);

    ap_hints_t hints{};
    bool use_hints = false;
    if (!my_call.empty()) {
        std::snprintf(hints.my_call, sizeof(hints.my_call), "%s", my_call.c_str());
        use_hints = true;
    }
    decoder_set_ap_hints(decoder, use_hints ? &hints : nullptr);

    decoder_monitor_press_samples(samples.data(), decoder, sample_count);
    setup_ms = elapsed_ms(setup_started_at);
    wsjtx3_parallel_experiment_reset((int) decodeMode);
    const auto core_started_at = std::chrono::steady_clock::now();
    const int candidate_count = decoder_ft8_find_sync(decoder);
    core_ms = elapsed_ms(core_started_at);
    const int bridge_raw_count = decoder_get_last_bridge_raw_count(decoder);
    const int merged_count = decoder_get_last_merged_count(decoder);
    wsjtx3_parallel_experiment_snapshot_t parallel_snapshot{};
    wsjtx3_parallel_experiment_get_snapshot(&parallel_snapshot);
    append_line(&output,
                "decode mode=%s utc=%lld sampleCount=%d candidates=%d bridgeRawCount=%d mergedCount=%d nativeBatchCount=%d passes=%d rounds=%d early=%d deep=%d q65Submode=%d q65TrPeriod=%d myCall=%s",
                mode_config.c_str(),
                (long long) utcTime,
                sample_count,
                candidate_count,
                bridge_raw_count,
                merged_count,
                candidate_count,
                decodePassCount,
                multiDecodeRoundCount,
                options.enable_early_decode ? 1 : 0,
                deepDecodeEnabled == JNI_TRUE ? 1 : 0,
                (int) q65Submode,
                (int) q65TrPeriodSeconds,
                my_call.empty() ? "-" : my_call.c_str());

    int valid_count = 0;
    int text_count = 0;
    const auto result_started_at = std::chrono::steady_clock::now();
    for (int idx = 0; idx < candidate_count; ++idx) {
        ft8_message message = decoder_ft8_analysis(idx, decoder);
        if (!message.isValid) {
            continue;
        }
        ++valid_count;
        if (!has_visible_text(message.message.text)) {
            continue;
        }
        ++text_count;
        append_line(&output,
                    "#%02d snr=%d dt=%.2f freq=%.1f score=%d text=%s",
                    idx,
                    message.snr,
                    message.time_sec,
                    message.freq_hz,
                    message.candidate.score,
                    message.message.text);
    }
    result_ms = elapsed_ms(result_started_at);

    const char *failure_reason = "none";
    if (bridge_raw_count == 0) {
        failure_reason = "no-bridge-candidates";
    } else if (merged_count == 0) {
        failure_reason = "no-merged-candidates";
    } else if (text_count == 0) {
        failure_reason = "no-published-text";
    }
    append_line(&output,
                "summary mode=%s valid=%d text=%d bridgeRawCount=%d mergedCount=%d",
                mode_config.c_str(),
                valid_count,
                text_count,
                bridge_raw_count,
                merged_count);
    append_line(&output,
                "parallel summary candidateParallelEnabled=%d candidateParallelActuallyUsed=%d "
                "candidateParallelThreads=%d osdParallelEnabled=%d osdParallelActuallyUsed=%d "
                "nativeParallelEnabled=%d nativeParallelActuallyUsed=%d downgradeReason=%s "
                "resultRegression=%d callbackMismatch=%d fallbackCount=%d",
                parallel_snapshot.candidate_parallel_enabled,
                parallel_snapshot.candidate_parallel_actually_used,
                parallel_snapshot.candidate_parallel_threads,
                parallel_snapshot.osd_parallel_enabled,
                parallel_snapshot.osd_parallel_actually_used,
                parallel_snapshot.native_parallel_enabled,
                parallel_snapshot.native_parallel_actually_used,
                parallel_snapshot.downgrade_reason,
                parallel_snapshot.result_regression,
                parallel_snapshot.callback_mismatch,
                parallel_snapshot.fallback_count);
    append_line(&output,
                "smoke summary path=%s mode=%s stage=diagnostic-sample "
                "profile[pass=%d round=%d qso=%d sens=%d wide=%d deep=%d] "
                "sourceSampleRate=%d expectedSamples=%d actualSamples=%d "
                "bridgeRawCount=%d mergedCount=%d nativeBatchCount=%d javaPublishedCount=%d "
                "timing[loadMs=%lld initMs=%lld setupMs=%lld coreMs=%lld resultMs=%lld totalMs=%lld] "
                "durationMs=%lld scheduler=direct-native-diagnostic failureReason=%s",
                path.c_str(),
                mode_config.c_str(),
                decodePassCount,
                multiDecodeRoundCount,
                qsoFreqSensitivity,
                decodeSensitivity,
                enableWidebandDxSearch == JNI_TRUE ? 1 : 0,
                deepDecodeEnabled == JNI_TRUE ? 1 : 0,
                sample_rate,
                expected_samples,
                sample_count,
                bridge_raw_count,
                merged_count,
                candidate_count,
                text_count,
                load_ms,
                init_ms,
                setup_ms,
                core_ms,
                result_ms,
                elapsed_ms(start_time),
                elapsed_ms(start_time),
                failure_reason);
    delete_decoder(decoder);
    return env->NewStringUTF(output.c_str());
}
