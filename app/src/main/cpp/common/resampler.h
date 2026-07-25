#ifndef FT8CN_COMMON_RESAMPLER_H
#define FT8CN_COMMON_RESAMPLER_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    FTX_RESAMPLE_OK = 0,
    FTX_RESAMPLE_INVALID_ARGUMENT = -1,
    FTX_RESAMPLE_UNSUPPORTED_RATE = -2,
    FTX_RESAMPLE_OUTPUT_TOO_SMALL = -3
};

int ftx_resample_required_output(size_t input_count,
                                 int input_rate,
                                 int output_rate,
                                 size_t *output_count);

int ftx_resample_float_mono(const float *input,
                            size_t input_count,
                            int input_rate,
                            int output_rate,
                            float *output,
                            size_t output_capacity,
                            size_t *output_count);

#ifdef __cplusplus
}
#endif

#endif
