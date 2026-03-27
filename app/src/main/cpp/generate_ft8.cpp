//
// Created by jmsmf on 2022/6/1.
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

extern "C" {
#include "common/debug.h"
#include "ft8Encoder.h"
#include "ft8/pack.h"
#include "ft8/encode.h"
#include "ft8/hash22.h"
#include "ft8/constants.h"
}

#define GFSK_CONST_K 5.336446f ///< == pi * sqrt(2 / log(2))

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

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_FT8TransmitSignal_GenerateFt8(JNIEnv *env, jobject,
                                                                jstring message,
                                                                jfloat frequency,
                                                                jshortArray buffer) {
    jshort *_buffer;
    _buffer = (*env).GetShortArrayElements(buffer, nullptr);
    char *str = Jstring2CStr(env, message);
    generateFt8ToBuffer(str, frequency, _buffer);
    (*env).ReleaseShortArrayElements(buffer, _buffer, JNI_COMMIT);
    free(str);
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
    synth_gfsk((uint8_t *) _symbols, n_sym, f0, symbol_bt, symbol_period, signal_rate, _signal + offset);

    (*env).ReleaseByteArrayElements(symbols, _symbols, JNI_COMMIT);
    (*env).ReleaseFloatArrayElements(signal, _signal, JNI_COMMIT);
}

/**
 * 新增：统一 FT8 / FT4 发射入口
 * Java:
 * GenerateFTx.generateFtXNative(Ft8Message msg, float frequency, int sampleRate, int mode)
 *
 * mode:
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
        jint mode) {

    if (msgObj == nullptr) {
        return nullptr;
    }

    char text[128];
    buildMessageText(env, msgObj, text, sizeof(text));

    if (strlen(text) == 0) {
        return nullptr;
    }

    // 打包 77 bit 消息
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

    if (mode == 1) {
        nn = FT4_NN;
        symbolPeriod = FT4_SYMBOL_PERIOD;
        symbolBt = 1.0f;
    } else {
        nn = FT8_NN;
        symbolPeriod = FT8_SYMBOL_PERIOD;
        symbolBt = FT8_SYMBOL_BT;
    }

    // 编码 tone
    uint8_t *tones = (uint8_t *) malloc(nn);
    if (tones == nullptr) {
        return nullptr;
    }
    memset(tones, 0, nn);

    if (mode == 1) {
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

    synth_gfsk(
            tones,
            nn,
            frequency,
            symbolBt,
            symbolPeriod,
            sampleRate,
            signal
    );

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
