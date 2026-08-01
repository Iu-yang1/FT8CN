#ifndef FTX_NATIVE_FLOAT_BUFFER_H
#define FTX_NATIVE_FLOAT_BUFFER_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ftx_native_float_buffer ftx_native_float_buffer_t;

ftx_native_float_buffer_t *ftx_native_float_buffer_create(size_t capacity);
void ftx_native_float_buffer_destroy(ftx_native_float_buffer_t *buffer);
float *ftx_native_float_buffer_data(ftx_native_float_buffer_t *buffer);
size_t ftx_native_float_buffer_capacity(const ftx_native_float_buffer_t *buffer);
size_t ftx_native_float_buffer_size(const ftx_native_float_buffer_t *buffer);
int ftx_native_float_buffer_set_size(ftx_native_float_buffer_t *buffer, size_t size);

#ifdef __cplusplus
}
#endif

#endif
