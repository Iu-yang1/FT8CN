#include <jni.h>

extern "C" {
#include "experimental/experimental_modem.h"
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_bg7yoz_ft8cn_experimental_ExperimentalCodecBridge_analyzeFirstSymbolEnergies(
        JNIEnv *env,
        jclass,
        jfloatArray samples,
        jint sampleRate,
        jint symbolSamples,
        jintArray toneHz
) {
    if (samples == nullptr || toneHz == nullptr) {
        return nullptr;
    }

    jsize sampleCount = env->GetArrayLength(samples);
    jsize toneCount = env->GetArrayLength(toneHz);
    if (toneCount < 4 || sampleCount <= 0) {
        return nullptr;
    }

    jfloat *samplePtr = env->GetFloatArrayElements(samples, nullptr);
    jint *tonePtr = env->GetIntArrayElements(toneHz, nullptr);

    exp_symbol_result_t result;
    int ok = exp_analyze_first_symbol(
            samplePtr,
            (int) sampleCount,
            (int) sampleRate,
            (int) symbolSamples,
            (const int *) tonePtr,
            (int) toneCount,
            &result
    );

    env->ReleaseFloatArrayElements(samples, samplePtr, JNI_ABORT);
    env->ReleaseIntArrayElements(toneHz, tonePtr, JNI_ABORT);

    if (!ok) {
        return nullptr;
    }

    jfloatArray output = env->NewFloatArray(5);
    if (output == nullptr) {
        return nullptr;
    }

    jfloat values[5];
    values[0] = result.energies[0];
    values[1] = result.energies[1];
    values[2] = result.energies[2];
    values[3] = result.energies[3];
    values[4] = (jfloat) result.best_index;
    env->SetFloatArrayRegion(output, 0, 5, values);
    return output;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_bg7yoz_ft8cn_experimental_ExperimentalCodecBridge_getNativeVersion(
        JNIEnv *env,
        jclass
) {
    return env->NewStringUTF("experimental-codec-0.1");
}

