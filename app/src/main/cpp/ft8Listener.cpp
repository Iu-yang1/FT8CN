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
    jfieldID extraInfo = env->GetFieldID(objectClass, "extraInfo", "Ljava/lang/String;");
    jfieldID maidenGrid = env->GetFieldID(objectClass, "maidenGrid", "Ljava/lang/String;");
    jfieldID report = env->GetFieldID(objectClass, "report", "I");
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
        env->SetObjectField(ft8Message, extraInfo, env->NewStringUTF(message.message.extra));
        env->SetObjectField(ft8Message, maidenGrid, env->NewStringUTF(message.message.maidenGrid));
        env->SetIntField(ft8Message, report, message.message.report);

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
    decoder_monitor_press(c_array, dd);
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
    memcpy(buf, dd->a91, FTX_LDPC_K_BYTES);

    env->SetByteArrayRegion(array, 0, FTX_LDPC_K_BYTES, buf);
    return array;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8listener_FT8SignalListener_setDecodeMode(JNIEnv *env, jobject thiz,
                                                                  jlong decoder, jboolean is_deep) {
    decoder_t *dd;
    dd = (decoder_t *) decoder;
    if (is_deep) {
        dd->kLDPC_iterations = deep_kLDPC_iterations;
    } else {
        dd->kLDPC_iterations = fast_kLDPC_iterations;
    }
}

/**
 * 把频率减�? */
static inline void setMagToZero(decoder_t *dd, int index, int max_block_size) {
    if (index > 0 && index < max_block_size) {
        dd->mon.wf.mag[index] = 0;
    }
}

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

    int nn;
    float symbol_period;
    float slot_time;

    if (mode == SIGNAL_MODE_FT4) {
        nn = FT4_NN;
        symbol_period = FT4_SYMBOL_PERIOD;
        slot_time = FT4_SLOT_TIME;
    } else {
        nn = FT8_NN;
        symbol_period = FT8_SYMBOL_PERIOD;
        slot_time = FT8_SLOT_TIME;
    }

    auto *tones = (uint8_t *) malloc(nn);
    memset(tones, 0, nn);

    if (mode == SIGNAL_MODE_FT4) {
        ft4_encode((uint8_t *) c_array, tones);
    } else {
        ft8_encode((uint8_t *) c_array, tones);
    }

    int max_block_size = (int) (slot_time / symbol_period) * kTime_osr * kFreq_osr
                         * (int) (sample_rate * symbol_period / 2);
    int block_size = (int) (symbol_period * dd->mon_cfg.sample_rate);
    int freq_offset = (int) (frequency * symbol_period) * kFreq_osr;
    int time_offset = (int) ((time_sec / symbol_period) * kTime_osr + 0.5f);

    LOG_PRINTF("subtractSignal mode=%d nn=%d symbol_period=%f slot_time=%f",
               mode, nn, symbol_period, slot_time);
    LOG_PRINTF("max_block_size=%d block_size=%d freq_offset=%d time_offset=%d",
               max_block_size, block_size, freq_offset, time_offset);

    for (int i = 0; i < nn; ++i) {
        int index = (i + time_offset) * 2;
        int index1 = index * block_size + freq_offset + tones[i];
        int index2 = (index + 1) * block_size + freq_offset + tones[i];
        int index3 = index1 + 1;
        int index4 = index2 + 1;
        int index5 = index1 - 1;
        int index6 = index2 - 1;
        int index7 = index1 - 2;
        int index8 = index2 - 2;
        int index9 = index1 + 2;
        int index10 = index2 + 2;

        setMagToZero(dd, index1, max_block_size);
        setMagToZero(dd, index2, max_block_size);
        setMagToZero(dd, index3, max_block_size);
        setMagToZero(dd, index4, max_block_size);
        setMagToZero(dd, index5, max_block_size);
        setMagToZero(dd, index6, max_block_size);
        setMagToZero(dd, index7, max_block_size);
        setMagToZero(dd, index8, max_block_size);
        setMagToZero(dd, index9, max_block_size);
        setMagToZero(dd, index10, max_block_size);
    }

    free(tones);
    free(c_array);
}