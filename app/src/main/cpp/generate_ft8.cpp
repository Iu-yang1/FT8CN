//
// 由 jmsmf 创建于 2022/6/1。
// 已增加：GenerateFTx.generateFtXNative(...) 的 JNI 导出
// 说明：
// 1. 保留原 GenerateFT8 相关 JNI，兼容旧代码
// 2. 新增 FT8 / FT4 统一发射入口
// 3. 需要 ft8/constants.h 中存在 FT4_NN / FT4_SYMBOL_PERIOD / FT4_SYMBOL_BT
// 4. 需要 ft8/encode.h 中存在 ft4_encode(...)
//

#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <climits>
#include <algorithm>
#include <cstdint>
#include <new>

extern "C" {
#include "common/debug.h"
#include "common/q65_wave_size.h"
#include "ft8Encoder.h"
#include "ft8/pack.h"
#include "ft8/encode.h"
#include "ft8/hash22.h"
#include "ft8/constants.h"
#include "wsjtx3/wsjtx3_backend.h"
}

#define GFSK_CONST_K 5.336446f ///< 等于 pi * sqrt(2 / log(2))

static constexpr jint SIGNAL_MODE_FT8 = 0;
static constexpr jint SIGNAL_MODE_FT4 = 1;
static constexpr jint SIGNAL_MODE_Q65 = 2;
static int normalizeQ65Submode(int q65Submode) {
    if (q65Submode < 0 || q65Submode > 4) {
        return 0;
    }
    return q65Submode;
}

static int normalizeQ65TrPeriodSeconds(int q65TrPeriodSeconds) {
    switch (q65TrPeriodSeconds) {
        case 15:
        case 30:
        case 60:
        case 120:
        case 300:
            return q65TrPeriodSeconds;
        default:
            return 60;
    }
}

static constexpr int Q65_TONE_COUNT = 85;
static constexpr int Q65_TX_STREAM_CHUNK = 4096;
static constexpr double Q65_TWO_PI = 6.283185307179586476925286766559;

struct Q65TxStreamState {
    int tones[Q65_TONE_COUNT]{};
    int samplesPerSymbol = 0;
    int sampleRate = 0;
    size_t totalSamples = 0;
    size_t generatedSamples = 0;
    double baseFrequencyHz = 0.0;
    double toneSpacingHz = 0.0;
    double phase = 0.0;
    double phaseScale = 0.0;
    float scratch[Q65_TX_STREAM_CHUNK]{};
};

static Q65TxStreamState *q65TxStreamFromHandle(jlong handle) {
    return reinterpret_cast<Q65TxStreamState *>(static_cast<uintptr_t>(handle));
}

char *Jstring2CStr(JNIEnv *env, jstring jstr) {
    char *rtn = nullptr;
    jclass clsstring = env->FindClass("java/lang/String");
    jstring strencode = env->NewStringUTF("GB2312");
    jmethodID mid = env->GetMethodID(clsstring, "getBytes", "(Ljava/lang/String;)[B");
    auto barr = (jbyteArray) env->CallObjectMethod(jstr, mid, strencode);
    int alen = env->GetArrayLength(barr);
    jbyte *ba = env->GetByteArrayElements(barr, JNI_FALSE);
    if (alen > 0) {
        rtn = (char *) malloc(alen + 1);
        memcpy(rtn, ba, alen);
        rtn[alen] = 0;
    }
    env->ReleaseByteArrayElements(barr, ba, 0);
    return rtn;
}

/**
 * 根据 Java Ft8Message 组装待编码文本
 * 直接复用 Java 层 Ft8Message.getMessageText()，避免双端格式分叉
 */
static void buildMessageText(JNIEnv *env, jobject msgObj, char *outText, int outSize) {
    memset(outText, 0, outSize);
    jclass cls = env->GetObjectClass(msgObj);
    jmethodID mid = env->GetMethodID(cls, "getMessageText", "()Ljava/lang/String;");
    if (mid == nullptr) {
        return;
    }

    jstring textObj = (jstring) env->CallObjectMethod(msgObj, mid);
    if (textObj == nullptr) {
        return;
    }

    const char *text = env->GetStringUTFChars(textObj, 0);
    snprintf(outText, outSize, "%s", text == nullptr ? "" : text);
    if (text != nullptr) {
        env->ReleaseStringUTFChars(textObj, text);
    }
}

static jfloatArray generateQ65Wave(JNIEnv *env,
                                   const char *messageText,
                                   jfloat frequency,
                                   jint sampleRate,
                                   jint q65Submode,
                                   jint q65TrPeriodSeconds) {
    if (messageText == nullptr || messageText[0] == '\0' || sampleRate <= 0) {
        LOGE("Q65 TX waveform generation failed: reason=invalid-input mode=Q65 submode=%c trPeriod=%d sampleRate=%d freq=%.1f text=%s",
             'A' + normalizeQ65Submode(q65Submode),
             normalizeQ65TrPeriodSeconds(q65TrPeriodSeconds),
             sampleRate,
             frequency,
             messageText == nullptr ? "<null>" : messageText);
        return nullptr;
    }

    q65Submode = normalizeQ65Submode(q65Submode);
    q65TrPeriodSeconds = normalizeQ65TrPeriodSeconds(q65TrPeriodSeconds);

    size_t capacitySize = 0;
    if (!ftx_q65_required_samples(q65TrPeriodSeconds, sampleRate, &capacitySize)
        || capacitySize > static_cast<size_t>(INT_MAX)) {
        LOGE("Q65 TX waveform generation failed: reason=invalid-capacity submode=%c trPeriod=%d sampleRate=%d",
             'A' + q65Submode, q65TrPeriodSeconds, sampleRate);
        return nullptr;
    }

    const int capacity = static_cast<int>(capacitySize);
    const int scaledNsps = capacity / 85;
    const int modeFactor = 1 << q65Submode;
    const double toneSpacing = static_cast<double>(sampleRate)
                               / static_cast<double>(scaledNsps) * modeFactor;
    const double highestToneHz = static_cast<double>(frequency) + 64.0 * toneSpacing;
    if (!std::isfinite(frequency) || frequency < 0.0f
        || highestToneHz >= static_cast<double>(sampleRate) * 0.5) {
        LOGE("Q65 TX waveform generation failed: reason=nyquist submode=%c trPeriod=%d sampleRate=%d freq=%.1f highestTone=%.1f",
             'A' + q65Submode, q65TrPeriodSeconds, sampleRate, frequency, highestToneHz);
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(capacity);
    if (result == nullptr) {
        LOGE("Q65 TX waveform generation failed: reason=new-float-array-null mode=Q65 submode=%c trPeriod=%d sampleRate=%d capacity=%d",
             'A' + q65Submode, q65TrPeriodSeconds, sampleRate, capacity);
        return nullptr;
    }
    jfloat *signal = env->GetFloatArrayElements(result, nullptr);
    if (signal == nullptr) {
        env->DeleteLocalRef(result);
        return nullptr;
    }

    const int generated = wsjtx3_backend_generate_q65_wave(
            messageText,
            q65Submode,
            q65TrPeriodSeconds,
            sampleRate,
            frequency,
            signal,
            capacity
    );
    if (generated != capacity) {
        LOGE("Q65 TX waveform generation failed: reason=backend-generate-failed mode=Q65 submode=%c trPeriod=%d sampleRate=%d freq=%.1f text=%s generated=%d capacity=%d",
             'A' + q65Submode, q65TrPeriodSeconds, sampleRate, frequency, messageText, generated, capacity);
        env->ReleaseFloatArrayElements(result, signal, JNI_ABORT);
        env->DeleteLocalRef(result);
        return nullptr;
    }

    float peak = 0.0f;
    double energy = 0.0;
    for (int i = 0; i < generated; ++i) {
        float abs = std::fabs(signal[i]);
        if (abs > peak) {
            peak = abs;
        }
        energy += static_cast<double>(signal[i]) * static_cast<double>(signal[i]);
    }
    double rms = generated > 0 ? std::sqrt(energy / static_cast<double>(generated)) : 0.0;
    double durationMs = sampleRate > 0
                        ? static_cast<double>(generated) * 1000.0 / static_cast<double>(sampleRate)
                        : 0.0;

    env->ReleaseFloatArrayElements(result, signal, 0);
    LOGI("Q65 TX waveform generated: submode=%c trPeriod=%ds sampleRate=%d freq=%.1f samples=%d durationMs=%.1f peak=%.6f rms=%.6f text=%s",
         'A' + q65Submode, q65TrPeriodSeconds, sampleRate, frequency, generated, durationMs, peak, rms, messageText);
    return result;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_Q65WaveStream_requiredSamplesNative(
        JNIEnv *, jclass, jint q65TrPeriodSeconds, jint sampleRate) {
    size_t required = 0;
    if (!ftx_q65_required_samples(
            normalizeQ65TrPeriodSeconds(q65TrPeriodSeconds),
            sampleRate,
            &required)
        || required > static_cast<size_t>(INT64_MAX)) {
        return 0;
    }
    return static_cast<jlong>(required);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_Q65WaveStream_createNative(
        JNIEnv *env,
        jclass,
        jstring message,
        jfloat frequency,
        jint sampleRate,
        jint q65Submode,
        jint q65TrPeriodSeconds) {
    if (message == nullptr || sampleRate <= 0 || !std::isfinite(frequency)) {
        return 0;
    }
    q65Submode = normalizeQ65Submode(q65Submode);
    q65TrPeriodSeconds = normalizeQ65TrPeriodSeconds(q65TrPeriodSeconds);
    size_t required = 0;
    if (!ftx_q65_required_samples(q65TrPeriodSeconds, sampleRate, &required)
        || required == 0 || required % Q65_TONE_COUNT != 0) {
        return 0;
    }

    auto *state = new (std::nothrow) Q65TxStreamState();
    if (state == nullptr) {
        return 0;
    }
    const char *text = env->GetStringUTFChars(message, nullptr);
    if (text == nullptr) {
        delete state;
        return 0;
    }
    const int toneCount = wsjtx3_backend_generate_q65_tones(
            text, state->tones, Q65_TONE_COUNT);
    env->ReleaseStringUTFChars(message, text);
    if (toneCount != Q65_TONE_COUNT) {
        delete state;
        return 0;
    }

    state->samplesPerSymbol = static_cast<int>(required / Q65_TONE_COUNT);
    state->sampleRate = sampleRate;
    state->totalSamples = required;
    state->baseFrequencyHz = frequency;
    state->toneSpacingHz = static_cast<double>(sampleRate)
                           / static_cast<double>(state->samplesPerSymbol)
                           * static_cast<double>(1 << q65Submode);
    const double highestToneHz = state->baseFrequencyHz
                                 + 64.0 * state->toneSpacingHz;
    if (state->baseFrequencyHz < 0.0
        || highestToneHz >= static_cast<double>(sampleRate) * 0.5) {
        delete state;
        return 0;
    }
    state->phaseScale = Q65_TWO_PI / static_cast<double>(sampleRate);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(state));
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_Q65WaveStream_readNative(
        JNIEnv *env,
        jclass,
        jlong handle,
        jfloatArray output,
        jint outputOffset,
        jint requestedSamples) {
    Q65TxStreamState *state = q65TxStreamFromHandle(handle);
    if (state == nullptr || output == nullptr
        || outputOffset < 0 || requestedSamples < 0) {
        return -1;
    }
    const jsize outputLength = env->GetArrayLength(output);
    if ((int64_t) outputOffset + requestedSamples > outputLength) {
        return -1;
    }
    const size_t remaining = state->totalSamples - state->generatedSamples;
    const size_t requested = std::min((size_t) requestedSamples, remaining);
    size_t produced = 0;
    while (produced < requested) {
        const size_t chunk = std::min(
                (size_t) Q65_TX_STREAM_CHUNK, requested - produced);
        for (size_t index = 0; index < chunk; ++index) {
            const size_t absoluteIndex = state->generatedSamples + produced + index;
            const size_t symbolIndex = absoluteIndex / (size_t) state->samplesPerSymbol;
            const double frequencyHz = state->baseFrequencyHz
                                       + state->tones[symbolIndex] * state->toneSpacingHz;
            state->scratch[index] = static_cast<float>(std::sin(state->phase));
            state->phase += state->phaseScale * frequencyHz;
            if (state->phase > Q65_TWO_PI) {
                state->phase -= Q65_TWO_PI;
            }
        }
        env->SetFloatArrayRegion(output,
                                 outputOffset + (jsize) produced,
                                 (jsize) chunk,
                                 state->scratch);
        if (env->ExceptionCheck()) {
            return -1;
        }
        produced += chunk;
    }
    state->generatedSamples += produced;
    return static_cast<jint>(produced);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_Q65WaveStream_destroyNative(
        JNIEnv *, jclass, jlong handle) {
    Q65TxStreamState *state = q65TxStreamFromHandle(handle);
    if (state != nullptr) {
        std::memset(state, 0, sizeof(*state));
        delete state;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_FT8TransmitSignal_GenerateFt8(JNIEnv *env, jobject,
                                                                jstring message,
                                                                jfloat frequency,
                                                                jshortArray buffer) {
    jshort *_buffer;
    _buffer = (*env).GetShortArrayElements(buffer, nullptr);
    char *str = Jstring2CStr(env, message);
    const int generated = generateFt8ToBuffer(str, frequency, _buffer);
    (*env).ReleaseShortArrayElements(buffer, _buffer, JNI_COMMIT);
    free(str);
    if (generated <= 0) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, "FT8 waveform generation failed");
        }
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_GenerateFT8_pack77(JNIEnv *env, jclass, jstring msg,
                                                     jbyteArray c77) {
    jbyte *_buffer;
    _buffer = (*env).GetByteArrayElements(c77, nullptr);
    char *str = Jstring2CStr(env, msg);
    int result = pack77(str, (uint8_t *) _buffer);
    (*env).ReleaseByteArrayElements(c77, _buffer, JNI_COMMIT);
    free(str);
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_GenerateFT8_ft8_1encode(JNIEnv *env, jclass clazz,
                                                          jbyteArray payload, jbyteArray tones) {
    jbyte *_payload;
    jbyte *_tones;
    _payload = (*env).GetByteArrayElements(payload, nullptr);
    _tones = (*env).GetByteArrayElements(tones, nullptr);
    ft8_encode((uint8_t *) _payload, (uint8_t *) _tones);
    (*env).ReleaseByteArrayElements(payload, _payload, JNI_COMMIT);
    (*env).ReleaseByteArrayElements(tones, _tones, JNI_COMMIT);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_GenerateFT8_gfsk_1pulse(JNIEnv *env, jclass clazz, jint n_spsym,
                                                          jfloat symbol_bt, jfloatArray pulse) {
    jfloat *_pulse;
    _pulse = (*env).GetFloatArrayElements(pulse, nullptr);

    for (int i = 0; i < 3 * n_spsym; ++i) {
        float t = i / (float) n_spsym - 1.5f;
        float arg1 = GFSK_CONST_K * symbol_bt * (t + 0.5f);
        float arg2 = GFSK_CONST_K * symbol_bt * (t - 0.5f);
        _pulse[i] = (erff(arg1) - erff(arg2)) / 2;
    }
    (*env).ReleaseFloatArrayElements(pulse, _pulse, JNI_COMMIT);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_GenerateFT8_synth_1gfsk(JNIEnv *env, jclass clazz,
                                                          jbyteArray symbols, jint n_sym, jfloat f0,
                                                          jfloat symbol_bt, jfloat symbol_period,
                                                          jint signal_rate, jfloatArray signal,
                                                          jint offset) {
    jbyte *_symbols;
    jfloat *_signal;
    _symbols = (*env).GetByteArrayElements(symbols, nullptr);
    _signal = (*env).GetFloatArrayElements(signal, nullptr);
    const int generated = synth_gfsk((uint8_t *) _symbols,
                                     n_sym,
                                     f0,
                                     symbol_bt,
                                     symbol_period,
                                     signal_rate,
                                     _signal + offset);

    (*env).ReleaseByteArrayElements(symbols, _symbols, JNI_COMMIT);
    (*env).ReleaseFloatArrayElements(signal, _signal, JNI_COMMIT);
    if (generated <= 0) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, "GFSK waveform generation failed");
        }
    }
}

/**
 * 新增：统一 FT8 / FT4 发射入口
 * Java 调用：
 * GenerateFTx.generateFtXNative(Ft8Message msg, float frequency, int sampleRate, int mode)
 *
 * mode 参数：
 * 0 = FT8
 * 1 = FT4
 */
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_GenerateFTx_generateFtXNative(
        JNIEnv *env,
        jclass clazz,
        jobject msgObj,
        jfloat frequency,
        jint sampleRate,
        jint mode,
        jint q65Submode,
        jint q65TrPeriodSeconds) {

    if (msgObj == nullptr) {
        return nullptr;
    }

    char text[128];
    buildMessageText(env, msgObj, text, sizeof(text));

    if (strlen(text) == 0) {
        return nullptr;
    }

    if (mode == SIGNAL_MODE_Q65) {
        return generateQ65Wave(env, text, frequency, sampleRate, q65Submode, q65TrPeriodSeconds);
    }

    if (mode != SIGNAL_MODE_FT8 && mode != SIGNAL_MODE_FT4) {
        LOGE("Unsupported TX mode in generateFtXNative: mode=%d text=%s", mode, text);
        return nullptr;
    }

    // 打包 77 位消息
    uint8_t packed[FTX_LDPC_K_BYTES];
    memset(packed, 0, sizeof(packed));

    int rc = pack77(text, packed);
    if (rc < 0) {
        return nullptr;
    }

    // 根据模式选择参数
    int nn;
    float symbolPeriod;
    float symbolBt;

    if (mode == SIGNAL_MODE_FT4) {
        nn = FT4_NN;
        symbolPeriod = FT4_SYMBOL_PERIOD;
        symbolBt = 1.0f;
    } else {
        nn = FT8_NN;
        symbolPeriod = FT8_SYMBOL_PERIOD;
        symbolBt = FT8_SYMBOL_BT;
    }

    // 编码音调序列
    uint8_t *tones = (uint8_t *) malloc(nn);
    if (tones == nullptr) {
        return nullptr;
    }
    memset(tones, 0, nn);

    if (mode == SIGNAL_MODE_FT4) {
        ft4_encode(packed, tones);
    } else {
        ft8_encode(packed, tones);
    }

    // 生成音频
    int numSamples = (int) (0.5f + nn * symbolPeriod * sampleRate);
    float *signal = (float *) malloc(sizeof(float) * numSamples);
    if (signal == nullptr) {
        free(tones);
        return nullptr;
    }
    memset(signal, 0, sizeof(float) * numSamples);

    const int generated = synth_gfsk(
            tones,
            nn,
            frequency,
            symbolBt,
            symbolPeriod,
            sampleRate,
            signal
    );
    if (generated != numSamples) {
        LOGE("FTX waveform generation failed: mode=%d sampleRate=%d text=%s rc=%d",
             mode, sampleRate, text, generated);
        free(tones);
        free(signal);
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(numSamples);
    if (result == nullptr) {
        free(tones);
        free(signal);
        return nullptr;
    }

    env->SetFloatArrayRegion(result, 0, numSamples, signal);

    free(tones);
    free(signal);

    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_ft8signal_FT8Package_getHash12(JNIEnv *env, jclass clazz, jstring callsign) {
    char *str = Jstring2CStr(env, callsign);
    uint32_t hash = hashcall_12(str);
    free(str);
    return hash;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_GenerateFT8_packFreeTextTo77(JNIEnv *env, jclass clazz,
                                                               jstring msg, jbyteArray c77) {

    jbyte *_buffer;
    _buffer = (*env).GetByteArrayElements(c77, nullptr);
    char *str = Jstring2CStr(env, msg);
    packtext77(str, (uint8_t *) _buffer);
    (*env).ReleaseByteArrayElements(c77, _buffer, JNI_COMMIT);
    free(str);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_ft8signal_FT8Package_getHash10(JNIEnv *env, jclass clazz, jstring callsign) {
    char *str = Jstring2CStr(env, callsign);
    uint32_t hash = (hashcall_10(str));
    free(str);
    return hash;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_ft8signal_FT8Package_getHash22(JNIEnv *env, jclass clazz, jstring callsign) {
    char *str = Jstring2CStr(env, callsign);
    u_int32_t hash = hashcall_22(str);
    free(str);
    return hash;
}

