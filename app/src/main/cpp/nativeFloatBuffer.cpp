#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <new>

extern "C" {
#include "common/native_float_buffer.h"
}

struct ftx_native_float_buffer {
    float *data = nullptr;
    size_t capacity = 0;
    size_t size = 0;
};

extern "C" ftx_native_float_buffer_t *ftx_native_float_buffer_create(size_t capacity) {
    if (capacity == 0 || capacity > static_cast<size_t>(12000) * 300U
        || capacity > std::numeric_limits<size_t>::max() / sizeof(float)) {
        return nullptr;
    }
    auto *buffer = new (std::nothrow) ftx_native_float_buffer_t();
    if (buffer == nullptr) {
        return nullptr;
    }
    buffer->data = static_cast<float *>(calloc(capacity, sizeof(float)));
    if (buffer->data == nullptr) {
        delete buffer;
        return nullptr;
    }
    buffer->capacity = capacity;
    return buffer;
}

extern "C" void ftx_native_float_buffer_destroy(ftx_native_float_buffer_t *buffer) {
    if (buffer == nullptr) {
        return;
    }
    free(buffer->data);
    buffer->data = nullptr;
    buffer->capacity = 0;
    buffer->size = 0;
    delete buffer;
}

extern "C" float *ftx_native_float_buffer_data(ftx_native_float_buffer_t *buffer) {
    return buffer == nullptr ? nullptr : buffer->data;
}

extern "C" size_t ftx_native_float_buffer_capacity(const ftx_native_float_buffer_t *buffer) {
    return buffer == nullptr ? 0U : buffer->capacity;
}

extern "C" size_t ftx_native_float_buffer_size(const ftx_native_float_buffer_t *buffer) {
    return buffer == nullptr ? 0U : buffer->size;
}

extern "C" int ftx_native_float_buffer_set_size(ftx_native_float_buffer_t *buffer, size_t size) {
    if (buffer == nullptr || size > buffer->capacity) {
        return -1;
    }
    buffer->size = size;
    return 0;
}

namespace {

ftx_native_float_buffer_t *from_handle(jlong handle) {
    return reinterpret_cast<ftx_native_float_buffer_t *>(static_cast<uintptr_t>(handle));
}

}  // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_com_bg7yoz_ft8cn_wave_NativeFloatBuffer_createNative(JNIEnv *, jclass, jint capacity) {
    if (capacity <= 0) {
        return 0L;
    }
    auto *buffer = ftx_native_float_buffer_create(static_cast<size_t>(capacity));
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(buffer));
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_wave_NativeFloatBuffer_appendNative(JNIEnv *env,
                                                          jclass,
                                                          jlong handle,
                                                          jfloatArray input,
                                                          jint offset,
                                                          jint count) {
    auto *buffer = from_handle(handle);
    if (buffer == nullptr || input == nullptr || offset < 0 || count < 0) {
        return -1;
    }
    const jsize input_length = env->GetArrayLength(input);
    const size_t current_size = ftx_native_float_buffer_size(buffer);
    if (static_cast<int64_t>(offset) + count > input_length
        || current_size + static_cast<size_t>(count) > ftx_native_float_buffer_capacity(buffer)) {
        return -1;
    }
    env->GetFloatArrayRegion(
            input,
            offset,
            count,
            ftx_native_float_buffer_data(buffer) + current_size);
    if (env->ExceptionCheck()) {
        return -1;
    }
    return ftx_native_float_buffer_set_size(buffer, current_size + static_cast<size_t>(count)) == 0
           ? count
           : -1;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_wave_NativeFloatBuffer_sizeNative(JNIEnv *, jclass, jlong handle) {
    const size_t size = ftx_native_float_buffer_size(from_handle(handle));
    return size <= static_cast<size_t>(INT32_MAX) ? static_cast<jint>(size) : -1;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_wave_NativeFloatBuffer_capacityNative(JNIEnv *, jclass, jlong handle) {
    const size_t capacity = ftx_native_float_buffer_capacity(from_handle(handle));
    return capacity <= static_cast<size_t>(INT32_MAX) ? static_cast<jint>(capacity) : -1;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_bg7yoz_ft8cn_wave_NativeFloatBuffer_copyNative(JNIEnv *env, jclass, jlong handle) {
    auto *buffer = from_handle(handle);
    const size_t size = ftx_native_float_buffer_size(buffer);
    if (buffer == nullptr || size > static_cast<size_t>(INT32_MAX)) {
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(size));
    if (result != nullptr && size > 0) {
        env->SetFloatArrayRegion(
                result,
                0,
                static_cast<jsize>(size),
                ftx_native_float_buffer_data(buffer));
    }
    return env->ExceptionCheck() ? nullptr : result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_wave_NativeFloatBuffer_destroyNative(JNIEnv *, jclass, jlong handle) {
    ftx_native_float_buffer_destroy(from_handle(handle));
}
