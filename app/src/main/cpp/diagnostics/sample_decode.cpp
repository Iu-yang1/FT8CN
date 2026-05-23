#include <jni.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <cstdarg>

extern "C" {
#include "../common/wave.h"
#include "../ft8Decoder.h"
#include "../ftx_core/include/ftx_types.h"
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

static int max_sample_seconds_for_mode(int decode_mode) {
    return decode_mode == FTX_MODE_Q65 ? kMaxQ65SampleSeconds : kMaxFt8LikeSampleSeconds;
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
                                                                   jboolean deepDecodeEnabled) {
    std::string output;
    const std::string path = copy_jstring(env, wavPath);
    const std::string my_call = copy_jstring(env, myCall);

    if (path.empty()) {
        append_line(&output, "error: wav path is empty");
        return env->NewStringUTF(output.c_str());
    }

    if (!is_supported_mode((int) decodeMode)) {
        append_line(&output, "error: unsupported decode mode %d", (int) decodeMode);
        return env->NewStringUTF(output.c_str());
    }

    const int capacity = kMaxSupportedRate * max_sample_seconds_for_mode((int) decodeMode);
    std::vector<float> samples((size_t) capacity, 0.0f);
    int sample_count = capacity;
    int sample_rate = 0;
    const int load_result = load_wav(samples.data(), &sample_count, &sample_rate, path.c_str());
    append_line(&output,
                "input mode=%s path=%s load=%d sampleRate=%d sampleCount=%d maxSeconds=%d",
                mode_label((int) decodeMode),
                path.c_str(),
                load_result,
                sample_rate,
                sample_count,
                max_sample_seconds_for_mode((int) decodeMode));

    if (load_result != 0) {
        return env->NewStringUTF(output.c_str());
    }

    if (sample_rate != kMaxSupportedRate) {
        append_line(&output,
                    "error: unsupported sample rate %d, current debug decoder expects %d Hz input",
                    sample_rate,
                    kMaxSupportedRate);
        return env->NewStringUTF(output.c_str());
    }

    samples.resize((size_t) sample_count);

    decoder_t *decoder = (decoder_t *) init_decoder((int64_t) utcTime,
                                                    sample_rate,
                                                    sample_count,
                                                    (int) decodeMode);
    if (decoder == nullptr) {
        append_line(&output, "error: init_decoder failed");
        return env->NewStringUTF(output.c_str());
    }
    append_line(&output,
                "decoder backend=%s ldpc=%d",
                backend_name(decoder),
                decoder->kLDPC_iterations);

    wsjtx_decoder_options_t options{};
    options.decode_pass_count = decodePassCount;
    options.multi_decode_round_count = multiDecodeRoundCount;
    options.qso_freq_sensitivity = qsoFreqSensitivity;
    options.decode_sensitivity = decodeSensitivity;
    options.enable_early_decode = enableEarlyDecode == JNI_TRUE;
    options.enable_wideband_dx_search = enableWidebandDxSearch == JNI_TRUE;
    decoder_set_wsjtx_options(decoder, &options);
    decoder_set_ldpc_iterations(decoder, deepDecodeEnabled == JNI_TRUE);

    ap_hints_t hints{};
    bool use_hints = false;
    if (!my_call.empty()) {
        std::snprintf(hints.my_call, sizeof(hints.my_call), "%s", my_call.c_str());
        use_hints = true;
    }
    decoder_set_ap_hints(decoder, use_hints ? &hints : nullptr);

    decoder_monitor_press_samples(samples.data(), decoder, sample_count);
    const int candidate_count = decoder_ft8_find_sync(decoder);
    const int bridge_raw_count = decoder_get_last_bridge_raw_count(decoder);
    const int merged_count = decoder_get_last_merged_count(decoder);
    append_line(&output,
                "decode mode=%s utc=%lld candidates=%d bridgeRawCount=%d mergedCount=%d passes=%d rounds=%d early=%d deep=%d myCall=%s",
                mode_label((int) decodeMode),
                (long long) utcTime,
                candidate_count,
                bridge_raw_count,
                merged_count,
                decodePassCount,
                multiDecodeRoundCount,
                options.enable_early_decode ? 1 : 0,
                deepDecodeEnabled == JNI_TRUE ? 1 : 0,
                my_call.empty() ? "-" : my_call.c_str());

    int valid_count = 0;
    int text_count = 0;
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

    append_line(&output,
                "summary mode=%s valid=%d text=%d bridgeRawCount=%d mergedCount=%d",
                mode_label((int) decodeMode),
                valid_count,
                text_count,
                bridge_raw_count,
                merged_count);
    delete_decoder(decoder);
    return env->NewStringUTF(output.c_str());
}
