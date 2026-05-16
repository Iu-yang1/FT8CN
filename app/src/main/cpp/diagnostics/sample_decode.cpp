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
}

namespace {

constexpr int kMaxSampleSeconds = 20;
constexpr int kMaxSupportedRate = 12000;

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
JNIEXPORT jstring JNICALL
Java_com_bg7yoz_ft8cn_diagnostics_NativeSampleDecode_inspectWavFile(JNIEnv *env,
                                                                    jclass,
                                                                    jstring wavPath,
                                                                    jboolean isFt8,
                                                                    jlong utcTime) {
    std::string output;
    const std::string path = copy_jstring(env, wavPath);
    std::vector<float> samples((size_t) kMaxSupportedRate * kMaxSampleSeconds, 0.0f);
    int sample_count = (int) samples.size();
    int sample_rate = 0;
    int load_result;
    decoder_t *decoder;

    if (path.empty()) {
        append_line(&output, "error: wav path is empty");
        return env->NewStringUTF(output.c_str());
    }

    load_result = load_wav(samples.data(), &sample_count, &sample_rate, path.c_str());
    append_line(&output,
                "inspect path=%s load=%d sampleRate=%d sampleCount=%d",
                path.c_str(),
                load_result,
                sample_rate,
                sample_count);
    if (load_result != 0) {
        return env->NewStringUTF(output.c_str());
    }

    decoder = (decoder_t *) init_decoder((int64_t) utcTime,
                                         sample_rate,
                                         sample_count,
                                         isFt8 == JNI_TRUE);
    if (decoder == nullptr) {
        append_line(&output, "inspect backend=init_failed");
        return env->NewStringUTF(output.c_str());
    }

    append_line(&output,
                "inspect backend=%s ldpc=%d sampleRate=%d expectedSamples=%d",
                backend_name(decoder),
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
                                                                   jboolean isFt8,
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

    const int capacity = kMaxSupportedRate * kMaxSampleSeconds;
    std::vector<float> samples((size_t) capacity, 0.0f);
    int sample_count = capacity;
    int sample_rate = 0;
    const int load_result = load_wav(samples.data(), &sample_count, &sample_rate, path.c_str());
    append_line(&output,
                "input path=%s load=%d sampleRate=%d sampleCount=%d",
                path.c_str(),
                load_result,
                sample_rate,
                sample_count);

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
                                                    isFt8 == JNI_TRUE);
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
    append_line(&output,
                "decode mode=%s utc=%lld candidates=%d passes=%d rounds=%d early=%d deep=%d myCall=%s",
                (isFt8 == JNI_TRUE) ? "FT8" : "FT4",
                (long long) utcTime,
                candidate_count,
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

    append_line(&output, "summary valid=%d text=%d", valid_count, text_count);
    delete_decoder(decoder);
    return env->NewStringUTF(output.c_str());
}
