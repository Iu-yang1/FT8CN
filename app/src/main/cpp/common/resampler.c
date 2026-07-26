#include "resampler.h"

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

enum {
    kTargetSampleRate = 12000,
    kMaxTaps = 129,
    kMaxDecimationFactor = 4,
    kStreamRingCapacity = kMaxTaps + kMaxDecimationFactor
};

struct ftx_resampler_stream {
    int factor;
    int taps;
    int radius;
    int finished;
    size_t received;
    size_t next_output;
    float first_sample;
    float last_sample;
    double kernel[kMaxTaps];
    float ring[kStreamRingCapacity];
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

static size_t stream_ready_output_count(const ftx_resampler_stream_t *stream,
                                        size_t received) {
    size_t last_ready;
    if (stream == NULL || received <= (size_t) stream->radius) {
        return 0;
    }
    last_ready = (received - 1U - (size_t) stream->radius)
                 / (size_t) stream->factor;
    if (last_ready < stream->next_output) {
        return 0;
    }
    return last_ready - stream->next_output + 1U;
}

static float stream_sample_at(const ftx_resampler_stream_t *stream,
                              size_t sample_index,
                              int before_start,
                              int after_end) {
    if (before_start) {
        return stream->first_sample;
    }
    if (after_end) {
        return stream->last_sample;
    }
    return stream->ring[sample_index % (size_t) kStreamRingCapacity];
}

static float stream_compute_output(const ftx_resampler_stream_t *stream,
                                   size_t output_index,
                                   int finishing) {
    const size_t center = output_index * (size_t) stream->factor;
    double accumulator = 0.0;
    for (int tap = 0; tap < stream->taps; ++tap) {
        const int relative = tap - stream->radius;
        size_t sample_index = center;
        int before_start = 0;
        int after_end = 0;
        if (relative < 0) {
            const size_t distance = (size_t) (-relative);
            if (center < distance) {
                before_start = 1;
            } else {
                sample_index = center - distance;
            }
        } else {
            const size_t distance = (size_t) relative;
            if (center > SIZE_MAX - distance) {
                after_end = 1;
            } else {
                sample_index = center + distance;
                after_end = finishing && sample_index >= stream->received;
            }
        }
        accumulator += (double) stream_sample_at(
                stream, sample_index, before_start, after_end) * stream->kernel[tap];
    }
    return (float) accumulator;
}

ftx_resampler_stream_t *ftx_resampler_stream_create(int input_rate,
                                                    int output_rate) {
    const int factor = decimation_factor(input_rate, output_rate);
    ftx_resampler_stream_t *stream;
    if (factor == 0) {
        return NULL;
    }
    stream = (ftx_resampler_stream_t *) calloc(1, sizeof(*stream));
    if (stream == NULL) {
        return NULL;
    }
    stream->factor = factor;
    if (factor == 1) {
        stream->taps = 1;
        stream->kernel[0] = 1.0;
    } else {
        stream->taps = build_low_pass_kernel(factor, stream->kernel);
        if (stream->taps <= 0) {
            free(stream);
            return NULL;
        }
    }
    stream->radius = stream->taps / 2;
    return stream;
}

int ftx_resampler_stream_process(ftx_resampler_stream_t *stream,
                                 const float *input,
                                 size_t input_count,
                                 float *output,
                                 size_t output_capacity,
                                 size_t *output_count) {
    size_t new_received;
    size_t required;
    size_t written = 0;
    if (stream == NULL || input == NULL || input_count == 0
        || output_count == NULL || (output == NULL && output_capacity > 0)) {
        return FTX_RESAMPLE_INVALID_ARGUMENT;
    }
    if (stream->finished) {
        return FTX_RESAMPLE_ALREADY_FINISHED;
    }
    if (input_count > SIZE_MAX - stream->received) {
        return FTX_RESAMPLE_INVALID_ARGUMENT;
    }
    new_received = stream->received + input_count;
    required = stream_ready_output_count(stream, new_received);
    if (output_capacity < required || (required > 0 && output == NULL)) {
        return FTX_RESAMPLE_OUTPUT_TOO_SMALL;
    }

    for (size_t input_index = 0; input_index < input_count; ++input_index) {
        const float sample = input[input_index];
        if (stream->received == 0) {
            stream->first_sample = sample;
        }
        stream->last_sample = sample;
        stream->ring[stream->received % (size_t) kStreamRingCapacity] = sample;
        stream->received++;
        while (stream_ready_output_count(stream, stream->received) > 0) {
            output[written++] = stream_compute_output(stream, stream->next_output, 0);
            stream->next_output++;
        }
    }
    *output_count = written;
    return FTX_RESAMPLE_OK;
}

int ftx_resampler_stream_finish(ftx_resampler_stream_t *stream,
                                float *output,
                                size_t output_capacity,
                                size_t *output_count) {
    size_t total_required;
    size_t remaining;
    if (stream == NULL || output_count == NULL
        || (output == NULL && output_capacity > 0)) {
        return FTX_RESAMPLE_INVALID_ARGUMENT;
    }
    if (stream->finished) {
        return FTX_RESAMPLE_ALREADY_FINISHED;
    }
    if (stream->received == 0) {
        return FTX_RESAMPLE_INVALID_ARGUMENT;
    }
    total_required = stream->received / (size_t) stream->factor;
    remaining = total_required - stream->next_output;
    if (output_capacity < remaining || (remaining > 0 && output == NULL)) {
        return FTX_RESAMPLE_OUTPUT_TOO_SMALL;
    }
    for (size_t index = 0; index < remaining; ++index) {
        output[index] = stream_compute_output(stream, stream->next_output, 1);
        stream->next_output++;
    }
    stream->finished = 1;
    *output_count = remaining;
    return FTX_RESAMPLE_OK;
}

void ftx_resampler_stream_destroy(ftx_resampler_stream_t *stream) {
    if (stream != NULL) {
        memset(stream, 0, sizeof(*stream));
        free(stream);
    }
}
