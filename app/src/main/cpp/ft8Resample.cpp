#include <jni.h>

#include <android/log.h>
#include <climits>
#include <cstddef>

extern "C" {
#include "common/resampler.h"
}

namespace {

constexpr size_t kMaxDecoderOutputSamples = 12000U * 300U;

}  // namespace

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_bg7yoz_ft8cn_wave_FT8Resample_get32Resample32(JNIEnv *env,
                                                       jclass,
                                                       jfloatArray inputData,
                                                       jint inputRate,
                                                       jint outputRate,
                                                       jint channels) {
    if (inputData == nullptr || channels != 1) {
        return nullptr;
    }

    const jsize input_count = env->GetArrayLength(inputData);
    size_t output_count = 0;
    if (input_count <= 0
        || ftx_resample_required_output((size_t) input_count,
                                        (int) inputRate,
                                        (int) outputRate,
                                        &output_count) != FTX_RESAMPLE_OK
        || output_count > kMaxDecoderOutputSamples
        || output_count > (size_t) INT_MAX) {
        __android_log_print(ANDROID_LOG_WARN,
                            "FT8Resample",
                            "unsupported resample request inputRate=%d outputRate=%d channels=%d input=%d",
                            (int) inputRate,
                            (int) outputRate,
                            (int) channels,
                            (int) input_count);
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray((jsize) output_count);
    if (result == nullptr) {
        return nullptr;
    }
    jfloat *input = env->GetFloatArrayElements(inputData, nullptr);
    jfloat *output = env->GetFloatArrayElements(result, nullptr);
    if (input == nullptr || output == nullptr) {
        if (input != nullptr) {
            env->ReleaseFloatArrayElements(inputData, input, JNI_ABORT);
        }
        if (output != nullptr) {
            env->ReleaseFloatArrayElements(result, output, JNI_ABORT);
        }
        env->DeleteLocalRef(result);
        return nullptr;
    }

    size_t written = 0;
    const int status = ftx_resample_float_mono(
            input,
            (size_t) input_count,
            (int) inputRate,
            (int) outputRate,
            output,
            output_count,
            &written);
    env->ReleaseFloatArrayElements(inputData, input, JNI_ABORT);
    env->ReleaseFloatArrayElements(result, output, status == FTX_RESAMPLE_OK ? 0 : JNI_ABORT);
    if (status != FTX_RESAMPLE_OK || written != output_count) {
        env->DeleteLocalRef(result);
        return nullptr;
    }
    return result;
}
