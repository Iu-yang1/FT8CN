#include "resampler.h"

#include <math.h>
#include <string.h>

enum {
    kTargetSampleRate = 12000,
    kMaxTaps = 129
};

static int decimation_factor(int input_rate, int output_rate) {
    if (output_rate != kTargetSampleRate) {
        return 0;
    }
    if (input_rate == output_rate) {
        return 1;
    }
    if (input_rate == 24000) {
        return 2;
    }
    if (input_rate == 48000) {
        return 4;
    }
    return 0;
}

static int build_low_pass_kernel(int factor, double *kernel) {
    const double pi = 3.1415926535897932384626433832795;
    const int taps = factor == 2 ? 65 : 129;
    const int center = taps / 2;
    const double cutoff = 0.45 / (double) factor;
    double sum = 0.0;

    for (int index = 0; index < taps; ++index) {
        const int offset = index - center;
        const double sinc = offset == 0
                            ? 2.0 * cutoff
                            : sin(2.0 * pi * cutoff * (double) offset)
                              / (pi * (double) offset);
        const double window = 0.54
                              - 0.46 * cos(2.0 * pi * (double) index
                                           / (double) (taps - 1));
        kernel[index] = sinc * window;
        sum += kernel[index];
    }
    if (sum == 0.0) {
        return 0;
    }
    for (int index = 0; index < taps; ++index) {
        kernel[index] /= sum;
    }
    return taps;
}

int ftx_resample_required_output(size_t input_count,
                                 int input_rate,
                                 int output_rate,
                                 size_t *output_count) {
    const int factor = decimation_factor(input_rate, output_rate);
    if (output_count == NULL || input_count == 0) {
        return FTX_RESAMPLE_INVALID_ARGUMENT;
    }
    if (factor == 0) {
        return FTX_RESAMPLE_UNSUPPORTED_RATE;
    }
    *output_count = input_count / (size_t) factor;
    return *output_count > 0 ? FTX_RESAMPLE_OK : FTX_RESAMPLE_INVALID_ARGUMENT;
}

int ftx_resample_float_mono(const float *input,
                            size_t input_count,
                            int input_rate,
                            int output_rate,
                            float *output,
                            size_t output_capacity,
                            size_t *output_count) {
    size_t required = 0;
    const int query_result = ftx_resample_required_output(
            input_count, input_rate, output_rate, &required);
    if (query_result != FTX_RESAMPLE_OK || input == NULL || output == NULL
        || output_count == NULL) {
        return query_result == FTX_RESAMPLE_OK
               ? FTX_RESAMPLE_INVALID_ARGUMENT
               : query_result;
    }
    if (output_capacity < required) {
        return FTX_RESAMPLE_OUTPUT_TOO_SMALL;
    }

    const int factor = decimation_factor(input_rate, output_rate);
    if (factor == 1) {
        memcpy(output, input, required * sizeof(float));
        *output_count = required;
        return FTX_RESAMPLE_OK;
    }

    double kernel[kMaxTaps];
    const int taps = build_low_pass_kernel(factor, kernel);
    const int radius = taps / 2;
    for (size_t output_index = 0; output_index < required; ++output_index) {
        const size_t center = output_index * (size_t) factor;
        double accumulator = 0.0;
        for (int tap = 0; tap < taps; ++tap) {
            long long sample_index = (long long) center + (long long) tap - radius;
            if (sample_index < 0) {
                sample_index = 0;
            } else if ((size_t) sample_index >= input_count) {
                sample_index = (long long) input_count - 1;
            }
            accumulator += (double) input[sample_index] * kernel[tap];
        }
        output[output_index] = (float) accumulator;
    }
    *output_count = required;
    return FTX_RESAMPLE_OK;
}
