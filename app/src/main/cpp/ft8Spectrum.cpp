#include <jni.h>
#include <vector>

extern "C" {
#include "common/debug.h"
#include "spectrum_data.h"
}

using FftRunner = void (*)(float *, int, jint *);

static void runFftWithIntInput(JNIEnv *env, jintArray data, jintArray fft_data, FftRunner runner) {
    if (data == nullptr || fft_data == nullptr || runner == nullptr) {
        return;
    }

    jsize arrLen = env->GetArrayLength(data);
    if (arrLen <= 1) {
        return;
    }

    jint *input = env->GetIntArrayElements(data, nullptr);
    if (input == nullptr) {
        return;
    }

    std::vector<float> rawData(arrLen);
    for (jsize i = 0; i < arrLen; ++i) {
        rawData[i] = input[i] / 32768.0f;
    }
    env->ReleaseIntArrayElements(data, input, JNI_ABORT);

    const jsize outLen = arrLen / 2;
    std::vector<jint> output(outLen);
    runner(rawData.data(), static_cast<int>(arrLen), output.data());
    env->SetIntArrayRegion(fft_data, 0, outLen, output.data());
}

static void runFftWithFloatInput(JNIEnv *env, jfloatArray data, jintArray fft_data, FftRunner runner) {
    if (data == nullptr || fft_data == nullptr || runner == nullptr) {
        return;
    }

    jsize arrLen = env->GetArrayLength(data);
    if (arrLen <= 1) {
        return;
    }

    jfloat *input = env->GetFloatArrayElements(data, nullptr);
    if (input == nullptr) {
        return;
    }

    const jsize outLen = arrLen / 2;
    std::vector<jint> output(outLen);
    runner(input, static_cast<int>(arrLen), output.data());
    env->ReleaseFloatArrayElements(data, input, JNI_ABORT);
    env->SetIntArrayRegion(fft_data, 0, outLen, output.data());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ui_SpectrumFragment_getFFTData(JNIEnv *env, jobject thiz, jintArray data,
                                                     jintArray fft_data) {
    runFftWithIntInput(env, data, fft_data, do_fftr);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ui_SpectrumFragment_getFFTDataRaw(JNIEnv *env, jobject thiz, jintArray data,
                                                        jintArray fft_data) {
    runFftWithIntInput(env, data, fft_data, do_fftr_raw);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ui_SpectrumView_getFFTData(JNIEnv *env, jobject thiz, jintArray data,
                                                 jintArray fft_data) {
    runFftWithIntInput(env, data, fft_data, do_fftr);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ui_SpectrumView_getFFTDataRaw(JNIEnv *env, jobject thiz, jintArray data,
                                                    jintArray fft_data) {
    runFftWithIntInput(env, data, fft_data, do_fftr_raw);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ui_SpectrumFragment_getFFTDataFloat(JNIEnv *env, jobject thiz,
                                                          jfloatArray data, jintArray fft_data) {
    runFftWithFloatInput(env, data, fft_data, do_fftr);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ui_SpectrumFragment_getFFTDataRawFloat(JNIEnv *env, jobject thiz,
                                                             jfloatArray data, jintArray fft_data) {
    runFftWithFloatInput(env, data, fft_data, do_fftr_raw);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ui_SpectrumView_getFFTDataFloat(JNIEnv *env, jobject thiz, jfloatArray data,
                                                      jintArray fft_data) {
    runFftWithFloatInput(env, data, fft_data, do_fftr);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ui_SpectrumView_getFFTDataRawFloat(JNIEnv *env, jobject thiz,
                                                         jfloatArray data, jintArray fft_data) {
    runFftWithFloatInput(env, data, fft_data, do_fftr_raw);
}
