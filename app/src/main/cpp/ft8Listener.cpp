//
// Created by jmsmf on 2022/6/2.
// 已修改：
// 1. ReBuildSignal_doSubtractSignal 增加 mode 参数，支持FT8 / FT4
//

#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>
#include <cmath>

extern "C" {
#include "common/debug.h"
#include "ft8Decoder.h"
#include "ft8Encoder.h"
#include "ft8/constants.h"
#include "ft8/encode.h"
}

static const int SIGNAL_MODE_FT8 = 0;
static const int SIGNAL_MODE_FT4 = 1;

typedef struct {
    jclass messageClass;
    jfieldID utcTime;
    jfieldID isValid;
    jfieldID time_sec;
    jfieldID freq_hz;
    jfieldID score;
    jfieldID snr;
    jfieldID messageHash;
    jfieldID signalFormat;
    jfieldID i3;
    jfieldID n3;
    jfieldID callsignFrom;
    jfieldID callsignTo;
    jfieldID dxCallTo2;
    jfieldID extraInfo;
    jfieldID maidenGrid;
    jfieldID report;
    jfieldID rttyState;
    jfieldID rFlag;
    jfieldID rttyTu;
    jfieldID euSerial;
    jfieldID arrlRac;
    jfieldID arrlClass;
    jfieldID callFromHash10;
    jfieldID callFromHash12;
    jfieldID callFromHash22;
    jfieldID callToHash10;
    jfieldID callToHash12;
    jfieldID callToHash22;
} ft8_message_jni_cache_t;

static ft8_message_jni_cache_t g_ft8_message_jni_cache = {};

static inline int normalize_decode_snr_for_display(int rawSnr, int signalMode) {
    (void)signalMode;
    int snr = rawSnr;

    if (snr > 32) snr = 32;
    if (snr < -32) snr = -32;
    return snr;
}

static void copyJStringToBuffer(JNIEnv *env, jstring source, char *dest, size_t destSize) {
    if (dest == nullptr || destSize == 0) {
        return;
    }
    dest[0] = '\0';

    if (source == nullptr) {
        return;
    }

    const char *value = env->GetStringUTFChars(source, nullptr);
    if (value == nullptr) {
        return;
    }

    snprintf(dest, destSize, "%s", value);
    env->ReleaseStringUTFChars(source, value);
    // JNI strings are copied into fixed buffers so the decoder never keeps stale AP hints by pointer.
}

static bool ensure_ft8_message_jni_cache(JNIEnv *env) {
    if (g_ft8_message_jni_cache.messageClass != nullptr) {
        return true;
    }

    jclass localClass = env->FindClass("com/bg7yoz/ft8cn/Ft8Message");
    if (localClass == nullptr) {
        return false;
    }

    g_ft8_message_jni_cache.messageClass = (jclass) env->NewGlobalRef(localClass);
    env->DeleteLocalRef(localClass);
    if (g_ft8_message_jni_cache.messageClass == nullptr) {
        return false;
    }

    g_ft8_message_jni_cache.utcTime = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "utcTime", "J");
    g_ft8_message_jni_cache.isValid = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "isValid", "Z");
    g_ft8_message_jni_cache.time_sec = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "time_sec", "F");
    g_ft8_message_jni_cache.freq_hz = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "freq_hz", "F");
    g_ft8_message_jni_cache.score = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "score", "I");
    g_ft8_message_jni_cache.snr = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "snr", "I");
    g_ft8_message_jni_cache.messageHash = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "messageHash", "I");
    g_ft8_message_jni_cache.signalFormat = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "signalFormat", "I");
    g_ft8_message_jni_cache.i3 = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "i3", "I");
    g_ft8_message_jni_cache.n3 = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "n3", "I");
    g_ft8_message_jni_cache.callsignFrom = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "callsignFrom", "Ljava/lang/String;");
    g_ft8_message_jni_cache.callsignTo = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "callsignTo", "Ljava/lang/String;");
    g_ft8_message_jni_cache.dxCallTo2 = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "dx_call_to2", "Ljava/lang/String;");
    g_ft8_message_jni_cache.extraInfo = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "extraInfo", "Ljava/lang/String;");
    g_ft8_message_jni_cache.maidenGrid = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "maidenGrid", "Ljava/lang/String;");
    g_ft8_message_jni_cache.report = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "report", "I");
    g_ft8_message_jni_cache.rttyState = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "rtty_state", "Ljava/lang/String;");
    g_ft8_message_jni_cache.rFlag = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "r_flag", "I");
    g_ft8_message_jni_cache.rttyTu = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "rtty_tu", "I");
    g_ft8_message_jni_cache.euSerial = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "eu_serial", "I");
    g_ft8_message_jni_cache.arrlRac = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "arrl_rac", "Ljava/lang/String;");
    g_ft8_message_jni_cache.arrlClass = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "arrl_class", "Ljava/lang/String;");
    g_ft8_message_jni_cache.callFromHash10 = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "callFromHash10", "J");
    g_ft8_message_jni_cache.callFromHash12 = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "callFromHash12", "J");
    g_ft8_message_jni_cache.callFromHash22 = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "callFromHash22", "J");
    g_ft8_message_jni_cache.callToHash10 = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "callToHash10", "J");
    g_ft8_message_jni_cache.callToHash12 = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "callToHash12", "J");
    g_ft8_message_jni_cache.callToHash22 = env->GetFieldID(g_ft8_message_jni_cache.messageClass, "callToHash22", "J");
    return true;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_DecoderFt8Reset(JNIEnv *env, jobject thiz,
                                                                    jlong decoder, jlong utcTime,
                                                                    jint num_samples) {
    decoder_t *dd;
    dd = (decoder_t *) decoder;
    decoder_ft8_reset(dd, utcTime, num_samples);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_DeleteDecoder(JNIEnv *env, jobject,
                                                                  jlong decoder) {
    decoder_t *dd;
    dd = (decoder_t *) decoder;
    delete_decoder(dd);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_DecoderFt8Analysis(JNIEnv *env, jobject thiz,
                                                                       jint idx,
                                                                       jlong decoder,
                                                                       jobject ft8Message) {
    decoder_t *dd;
    dd = (decoder_t *) decoder;

    ft8_message message = decoder_ft8_analysis(idx, dd);
    if (!ensure_ft8_message_jni_cache(env)) {
        return JNI_FALSE;
    }

    env->SetBooleanField(ft8Message, g_ft8_message_jni_cache.isValid, message.isValid);

    if (message.isValid) {
        env->SetLongField(ft8Message, g_ft8_message_jni_cache.utcTime, message.utcTime);
        env->SetFloatField(ft8Message, g_ft8_message_jni_cache.time_sec, message.time_sec);
        env->SetFloatField(ft8Message, g_ft8_message_jni_cache.freq_hz, message.freq_hz);
        env->SetIntField(ft8Message, g_ft8_message_jni_cache.score, message.candidate.score);

        int mode = SIGNAL_MODE_FT8;
        if (g_ft8_message_jni_cache.signalFormat != nullptr) {
            mode = env->GetIntField(ft8Message, g_ft8_message_jni_cache.signalFormat);
        }

        int displaySnr = normalize_decode_snr_for_display(message.snr, mode);
        env->SetIntField(ft8Message, g_ft8_message_jni_cache.snr, displaySnr);

        env->SetIntField(ft8Message, g_ft8_message_jni_cache.messageHash, message.message.hash);

        env->SetIntField(ft8Message, g_ft8_message_jni_cache.i3, message.message.i3);
        env->SetIntField(ft8Message, g_ft8_message_jni_cache.n3, message.message.n3);
        env->SetObjectField(ft8Message, g_ft8_message_jni_cache.callsignFrom, env->NewStringUTF(message.message.call_de));
        env->SetObjectField(ft8Message, g_ft8_message_jni_cache.callsignTo, env->NewStringUTF(message.message.call_to));
        env->SetObjectField(ft8Message, g_ft8_message_jni_cache.dxCallTo2, env->NewStringUTF(message.message.dx_call_to2));
        env->SetObjectField(ft8Message, g_ft8_message_jni_cache.extraInfo, env->NewStringUTF(message.message.extra));
        env->SetObjectField(ft8Message, g_ft8_message_jni_cache.maidenGrid, env->NewStringUTF(message.message.maidenGrid));
        env->SetIntField(ft8Message, g_ft8_message_jni_cache.report, message.message.report);
        env->SetObjectField(ft8Message, g_ft8_message_jni_cache.rttyState, env->NewStringUTF(message.message.rtty_state));
        env->SetIntField(ft8Message, g_ft8_message_jni_cache.rFlag, message.message.r_flag);
        env->SetIntField(ft8Message, g_ft8_message_jni_cache.rttyTu, message.message.rtty_tu);
        env->SetIntField(ft8Message, g_ft8_message_jni_cache.euSerial, message.message.eu_serial);
        env->SetObjectField(ft8Message, g_ft8_message_jni_cache.arrlRac, env->NewStringUTF(message.message.arrl_rac));
        env->SetObjectField(ft8Message, g_ft8_message_jni_cache.arrlClass, env->NewStringUTF(message.message.arrl_class));

        env->SetLongField(ft8Message, g_ft8_message_jni_cache.callFromHash10,
                          (jlong) message.message.call_de_hash.hash10);
        env->SetLongField(ft8Message, g_ft8_message_jni_cache.callFromHash12,
                          (jlong) message.message.call_de_hash.hash12);
        env->SetLongField(ft8Message, g_ft8_message_jni_cache.callFromHash22,
                          (jlong) message.message.call_de_hash.hash22);
        env->SetLongField(ft8Message, g_ft8_message_jni_cache.callToHash10,
                          (jlong) message.message.call_to_hash.hash10);
        env->SetLongField(ft8Message, g_ft8_message_jni_cache.callToHash12,
                          (jlong) message.message.call_to_hash.hash12);
        env->SetLongField(ft8Message, g_ft8_message_jni_cache.callToHash22,
                          (jlong) message.message.call_to_hash.hash22);
    }
    return message.isValid;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_DecoderFt8FindSync(JNIEnv *env, jobject,
                                                                       jlong decoder) {
    decoder_t *dd;
    dd = (decoder_t *) decoder;
    return decoder_ft8_find_sync(dd);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_DecoderMonitorPress(JNIEnv *env, jobject,
                                                                        jintArray buffer,
                                                                        jlong decoder) {
    decoder_t *dd;
    dd = (decoder_t *) decoder;

    int arr_len = env->GetArrayLength(buffer);
    auto *c_array = (jint *) malloc(arr_len * sizeof(jint));
    env->GetIntArrayRegion(buffer, 0, arr_len, c_array);

    auto *raw_data = (float_t *) malloc(sizeof(float_t) * arr_len);
    for (int i = 0; i < arr_len; i++) {
        raw_data[i] = c_array[i] / 32768.0f;
    }

    decoder_monitor_press(raw_data, dd);
    free(raw_data);
    free(c_array);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_InitDecoder(JNIEnv *env, jobject thiz, jlong utcTime,
                                                                jint sampleRate, jint num_samples,
                                                                jboolean isFt8) {
    return (jlong) init_decoder(utcTime, sampleRate, num_samples, isFt8);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_DecoderMonitorPressFloat(JNIEnv *env,
                                                                             jobject thiz,
                                                                             jfloatArray buffer,
                                                                             jlong decoder) {
    decoder_t *dd;
    dd = (decoder_t *) decoder;

    int arr_len = env->GetArrayLength(buffer);
    auto *c_array = (jfloat *) malloc(arr_len * sizeof(jfloat));
    env->GetFloatArrayRegion(buffer, 0, arr_len, c_array);
    decoder_monitor_press_samples(c_array, dd, arr_len);
    free(c_array);
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_DecoderGetA91(JNIEnv *env, jobject thiz,
                                                                  jlong decoder) {
    decoder_t *dd;
    dd = (decoder_t *) decoder;

    jbyteArray array = env->NewByteArray(FTX_LDPC_K_BYTES);

    jbyte buf[FTX_LDPC_K_BYTES];
    memset(buf, 0, sizeof(buf));
    decoder_get_a91(dd, (uint8_t *) buf);

    env->SetByteArrayRegion(array, 0, FTX_LDPC_K_BYTES, buf);
    return array;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_setDecodeMode(JNIEnv *env, jobject thiz,
                                                                  jlong decoder, jboolean is_deep) {
    decoder_t *dd;
    dd = (decoder_t *) decoder;
    decoder_set_ldpc_iterations(dd, is_deep);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_DecoderOwnsSessionFlow(JNIEnv *env,
                                                                            jobject thiz,
                                                                            jlong decoder) {
    decoder_t *dd;
    dd = (decoder_t *) decoder;
    return decoder_owns_session_flow(dd);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_DecoderSetWsjtOptions(JNIEnv *env,
                                                                          jobject thiz,
                                                                          jlong decoder,
                                                                          jint decode_pass_count,
                                                                          jint multi_decode_round_count,
                                                                          jint qso_freq_sensitivity,
                                                                          jint decode_sensitivity,
                                                                          jboolean enable_early_decode,
                                                                          jboolean enable_wideband_dx_search) {
    (void) env;
    (void) thiz;

    decoder_t *dd;
    dd = (decoder_t *) decoder;
    if (dd == nullptr) {
        return;
    }

    wsjtx_decoder_options_t options{};
    options.decode_pass_count = decode_pass_count;
    options.multi_decode_round_count = multi_decode_round_count;
    options.qso_freq_sensitivity = qso_freq_sensitivity;
    options.decode_sensitivity = decode_sensitivity;
    options.enable_early_decode = enable_early_decode;
    options.enable_wideband_dx_search = enable_wideband_dx_search;
    decoder_set_wsjtx_options(dd, &options);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_DecoderSetApHints(JNIEnv *env, jobject thiz,
                                                                      jlong decoder,
                                                                      jstring my_call,
                                                                      jobjectArray hint_calls,
                                                                      jobjectArray hint_grids) {
    decoder_t *dd;
    dd = (decoder_t *) decoder;
    if (dd == nullptr) {
        return;
    }

    ap_hints_t hints;
    memset(&hints, 0, sizeof(hints));
    copyJStringToBuffer(env, my_call, hints.my_call, sizeof(hints.my_call));
    // Each decode cycle replaces the full hint set so native state tracks the latest Java view.

    jsize callCount = (hint_calls == nullptr) ? 0 : env->GetArrayLength(hint_calls);
    jsize gridCount = (hint_grids == nullptr) ? 0 : env->GetArrayLength(hint_grids);
    jsize count = callCount < gridCount ? callCount : gridCount;
    if (count > FTX_AP_MAX_HINT_CALLS) {
        count = FTX_AP_MAX_HINT_CALLS;
    }

    for (jsize i = 0; i < count; ++i) {
        jstring call = (jstring) env->GetObjectArrayElement(hint_calls, i);
        jstring grid = (jstring) env->GetObjectArrayElement(hint_grids, i);

        copyJStringToBuffer(env, call,
                            hints.hint_calls[i],
                            sizeof(hints.hint_calls[i]));
        copyJStringToBuffer(env, grid,
                            hints.hint_grids[i],
                            sizeof(hints.hint_grids[i]));

        if (hints.hint_calls[i][0] != '\0') {
            hints.hint_call_count = (int) i + 1;
        }

        if (call != nullptr) {
            env->DeleteLocalRef(call);
        }
        if (grid != nullptr) {
            env->DeleteLocalRef(grid);
        }
    }

    decoder_set_ap_hints(dd, &hints);
}

/**
 * 把频率幅度置零
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_ReBuildSignal_doSubtractSignal(JNIEnv *env, jclass clazz,
                                                                 jlong decoder,
                                                                 jbyteArray payload,
                                                                 jint sample_rate,
                                                                 jfloat frequency,
                                                                 jfloat time_sec,
                                                                 jint mode) {
    decoder_t *dd;
    dd = (decoder_t *) decoder;

    int arr_len = env->GetArrayLength(payload);
    auto *c_array = (jbyte *) malloc(arr_len * sizeof(jbyte));
    env->GetByteArrayRegion(payload, 0, arr_len, c_array);

    decoder_subtract_signal(dd,
                            (uint8_t *) c_array,
                            sample_rate,
                            frequency,
                            time_sec,
                            mode);
    free(c_array);
}

