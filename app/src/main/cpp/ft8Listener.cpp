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

    jclass objectClass = env->FindClass("com/bg7yoz/ft8cn/Ft8Message");

    jfieldID utcTime = env->GetFieldID(objectClass, "utcTime", "J");
    jfieldID isValid = env->GetFieldID(objectClass, "isValid", "Z");
    jfieldID time_sec = env->GetFieldID(objectClass, "time_sec", "F");
    jfieldID freq_hz = env->GetFieldID(objectClass, "freq_hz", "F");
    jfieldID score = env->GetFieldID(objectClass, "score", "I");
    jfieldID snr = env->GetFieldID(objectClass, "snr", "I");
    jfieldID messageHash = env->GetFieldID(objectClass, "messageHash", "I");

    jfieldID signalFormat = env->GetFieldID(objectClass, "signalFormat", "I");

    env->SetBooleanField(ft8Message, isValid, message.isValid);

    jfieldID i3 = env->GetFieldID(objectClass, "i3", "I");
    jfieldID n3 = env->GetFieldID(objectClass, "n3", "I");
    jfieldID callsignFrom = env->GetFieldID(objectClass, "callsignFrom", "Ljava/lang/String;");
    jfieldID callsignTo = env->GetFieldID(objectClass, "callsignTo", "Ljava/lang/String;");
    jfieldID dxCallTo2 = env->GetFieldID(objectClass, "dx_call_to2", "Ljava/lang/String;");
    jfieldID extraInfo = env->GetFieldID(objectClass, "extraInfo", "Ljava/lang/String;");
    jfieldID maidenGrid = env->GetFieldID(objectClass, "maidenGrid", "Ljava/lang/String;");
    jfieldID report = env->GetFieldID(objectClass, "report", "I");
    jfieldID rttyState = env->GetFieldID(objectClass, "rtty_state", "Ljava/lang/String;");
    jfieldID rFlag = env->GetFieldID(objectClass, "r_flag", "I");
    jfieldID rttyTu = env->GetFieldID(objectClass, "rtty_tu", "I");
    jfieldID euSerial = env->GetFieldID(objectClass, "eu_serial", "I");
    jfieldID arrlRac = env->GetFieldID(objectClass, "arrl_rac", "Ljava/lang/String;");
    jfieldID arrlClass = env->GetFieldID(objectClass, "arrl_class", "Ljava/lang/String;");
    jfieldID callFromHash10 = env->GetFieldID(objectClass, "callFromHash10", "J");
    jfieldID callFromHash12 = env->GetFieldID(objectClass, "callFromHash12", "J");
    jfieldID callFromHash22 = env->GetFieldID(objectClass, "callFromHash22", "J");
    jfieldID callToHash10 = env->GetFieldID(objectClass, "callToHash10", "J");
    jfieldID callToHash12 = env->GetFieldID(objectClass, "callToHash12", "J");
    jfieldID callToHash22 = env->GetFieldID(objectClass, "callToHash22", "J");

    if (message.isValid) {
        env->SetLongField(ft8Message, utcTime, message.utcTime);
        env->SetFloatField(ft8Message, time_sec, message.time_sec);
        env->SetFloatField(ft8Message, freq_hz, message.freq_hz);
        env->SetIntField(ft8Message, score, message.candidate.score);

        int mode = SIGNAL_MODE_FT8;
        if (signalFormat != nullptr) {
            mode = env->GetIntField(ft8Message, signalFormat);
        }

        int displaySnr = normalize_decode_snr_for_display(message.snr, mode);
        env->SetIntField(ft8Message, snr, displaySnr);

        env->SetIntField(ft8Message, messageHash, message.message.hash);

        env->SetIntField(ft8Message, i3, message.message.i3);
        env->SetIntField(ft8Message, n3, message.message.n3);
        env->SetObjectField(ft8Message, callsignFrom, env->NewStringUTF(message.message.call_de));
        env->SetObjectField(ft8Message, callsignTo, env->NewStringUTF(message.message.call_to));
        env->SetObjectField(ft8Message, dxCallTo2, env->NewStringUTF(message.message.dx_call_to2));
        env->SetObjectField(ft8Message, extraInfo, env->NewStringUTF(message.message.extra));
        env->SetObjectField(ft8Message, maidenGrid, env->NewStringUTF(message.message.maidenGrid));
        env->SetIntField(ft8Message, report, message.message.report);
        env->SetObjectField(ft8Message, rttyState, env->NewStringUTF(message.message.rtty_state));
        env->SetIntField(ft8Message, rFlag, message.message.r_flag);
        env->SetIntField(ft8Message, rttyTu, message.message.rtty_tu);
        env->SetIntField(ft8Message, euSerial, message.message.eu_serial);
        env->SetObjectField(ft8Message, arrlRac, env->NewStringUTF(message.message.arrl_rac));
        env->SetObjectField(ft8Message, arrlClass, env->NewStringUTF(message.message.arrl_class));

        env->SetLongField(ft8Message, callFromHash10,
                          (jlong) message.message.call_de_hash.hash10);
        env->SetLongField(ft8Message, callFromHash12,
                          (jlong) message.message.call_de_hash.hash12);
        env->SetLongField(ft8Message, callFromHash22,
                          (jlong) message.message.call_de_hash.hash22);
        env->SetLongField(ft8Message, callToHash10,
                          (jlong) message.message.call_to_hash.hash10);
        env->SetLongField(ft8Message, callToHash12,
                          (jlong) message.message.call_to_hash.hash12);
        env->SetLongField(ft8Message, callToHash22,
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
