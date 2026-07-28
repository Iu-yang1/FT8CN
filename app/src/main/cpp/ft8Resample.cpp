#include <jni.h>

#include <android/log.h>
#include <algorithm>
#include <climits>
#include <cstddef>
#include <cstdint>
#include <new>

extern "C" {
#include "common/resampler.h"
}

namespace {

constexpr size_t kMaxDecoderOutputSamples = 12000U * 300U;
constexpr size_t kStreamScratchSamples = 4096U;

struct JavaResamplerStream {
    ftx_resampler_stream_t *stream = nullptr;
    float input_scratch[kStreamScratchSamples]{};
    float output_scratch[kStreamScratchSamples]{};
};

JavaResamplerStream *stream_from_handle(jlong handle) {
    return reinterpret_cast<JavaResamplerStream *>(static_cast<uintptr_t>(handle));
}

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

extern "C"
JNIEXPORT jlong JNICALL
Java_com_bg7yoz_ft8cn_wave_FT8Resample_createFloatStream(JNIEnv *,
                                                         jclass,
                                                         jint inputRate,
                                                         jint outputRate) {
    auto *wrapper = new (std::nothrow) JavaResamplerStream();
    if (wrapper == nullptr) {
        return 0;
    }
    wrapper->stream = ftx_resampler_stream_create((int) inputRate, (int) outputRate);
    if (wrapper->stream == nullptr) {
        delete wrapper;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(wrapper));
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_wave_FT8Resample_processFloatStream(JNIEnv *env,
                                                          jclass,
                                                          jlong handle,
                                                          jfloatArray inputData,
                                                          jint inputOffset,
                                                          jint inputCount,
                                                          jfloatArray outputData,
                                                          jint outputOffset,
                                                          jint outputCapacity) {
    JavaResamplerStream *wrapper = stream_from_handle(handle);
    if (wrapper == nullptr || wrapper->stream == nullptr
        || inputData == nullptr || outputData == nullptr
        || inputOffset < 0 || inputCount <= 0
        || outputOffset < 0 || outputCapacity < 0) {
        return FTX_RESAMPLE_INVALID_ARGUMENT;
    }
    const jsize input_length = env->GetArrayLength(inputData);
    const jsize output_length = env->GetArrayLength(outputData);
    if ((int64_t) inputOffset + inputCount > input_length
        || (int64_t) outputOffset + outputCapacity > output_length) {
        return FTX_RESAMPLE_INVALID_ARGUMENT;
    }

    size_t consumed = 0;
    size_t total_written = 0;
    while (consumed < (size_t) inputCount) {
        const size_t chunk = std::min(kStreamScratchSamples,
                                      (size_t) inputCount - consumed);
        env->GetFloatArrayRegion(inputData,
                                 inputOffset + (jsize) consumed,
                                 (jsize) chunk,
                                 wrapper->input_scratch);
        if (env->ExceptionCheck()) {
            return FTX_RESAMPLE_INVALID_ARGUMENT;
        }

        size_t written = 0;
        const size_t remaining_capacity = (size_t) outputCapacity - total_written;
        const int status = ftx_resampler_stream_process(
                wrapper->stream,
                wrapper->input_scratch,
                chunk,
                wrapper->output_scratch,
                std::min(kStreamScratchSamples, remaining_capacity),
                &written);
        if (status != FTX_RESAMPLE_OK) {
            return status;
        }
        if (written > remaining_capacity) {
            return FTX_RESAMPLE_OUTPUT_TOO_SMALL;
        }
        if (written > 0) {
            env->SetFloatArrayRegion(outputData,
                                     outputOffset + (jsize) total_written,
                                     (jsize) written,
                                     wrapper->output_scratch);
            if (env->ExceptionCheck()) {
                return FTX_RESAMPLE_INVALID_ARGUMENT;
            }
        }
        consumed += chunk;
        total_written += written;
    }
    return total_written <= (size_t) INT_MAX
           ? (jint) total_written
           : FTX_RESAMPLE_INVALID_ARGUMENT;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_wave_FT8Resample_finishFloatStream(JNIEnv *env,
                                                         jclass,
                                                         jlong handle,
                                                         jfloatArray outputData,
                                                         jint outputOffset,
                                                         jint outputCapacity) {
    JavaResamplerStream *wrapper = stream_from_handle(handle);
    if (wrapper == nullptr || wrapper->stream == nullptr || outputData == nullptr
        || outputOffset < 0 || outputCapacity < 0) {
        return FTX_RESAMPLE_INVALID_ARGUMENT;
    }
    const jsize output_length = env->GetArrayLength(outputData);
    if ((int64_t) outputOffset + outputCapacity > output_length) {
        return FTX_RESAMPLE_INVALID_ARGUMENT;
    }
    size_t written = 0;
    const int status = ftx_resampler_stream_finish(
            wrapper->stream,
            wrapper->output_scratch,
            std::min(kStreamScratchSamples, (size_t) outputCapacity),
            &written);
    if (status != FTX_RESAMPLE_OK) {
        return status;
    }
    if (written > 0) {
        env->SetFloatArrayRegion(outputData,
                                 outputOffset,
                                 (jsize) written,
                                 wrapper->output_scratch);
        if (env->ExceptionCheck()) {
            return FTX_RESAMPLE_INVALID_ARGUMENT;
        }
    }
    return (jint) written;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_wave_FT8Resample_destroyFloatStream(JNIEnv *,
                                                          jclass,
                                                          jlong handle) {
    JavaResamplerStream *wrapper = stream_from_handle(handle);
    if (wrapper != nullptr) {
        ftx_resampler_stream_destroy(wrapper->stream);
        wrapper->stream = nullptr;
        delete wrapper;
    }
}
