#include "../common/resampler.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const double kPi = 3.1415926535897932384626433832795;

static double tone_amplitude(const float *samples,
                             size_t count,
                             int sample_rate,
                             double frequency_hz) {
    double real = 0.0;
    double imaginary = 0.0;
    const size_t edge = count > 512 ? 256 : 0;
    for (size_t index = edge; index < count - edge; ++index) {
        const double phase = 2.0 * kPi * frequency_hz * (double) index
                             / (double) sample_rate;
        real += (double) samples[index] * cos(phase);
        imaginary += (double) samples[index] * sin(phase);
    }
    return 2.0 * hypot(real, imaginary) / (double) (count - 2 * edge);
}

static double sample_rms(const float *samples, size_t count) {
    double power = 0.0;
    const size_t edge = count > 512 ? 256 : 0;
    for (size_t index = edge; index < count - edge; ++index) {
        power += (double) samples[index] * (double) samples[index];
    }
    return sqrt(power / (double) (count - 2 * edge));
}

static int run_rate_case(int source_rate) {
    const size_t input_count = (size_t) source_rate;
    const size_t expected_count = 12000;
    const double stop_frequency = source_rate == 24000 ? 9000.0 : 15000.0;
    float *input = (float *) malloc(input_count * sizeof(float));
    float *output = (float *) malloc(expected_count * sizeof(float));
    size_t written = 0;
    int ok = input != NULL && output != NULL;

    if (!ok) {
        free(input);
        free(output);
        return 0;
    }

    for (size_t index = 0; index < input_count; ++index) {
        input[index] = 0.25f;
    }
    ok = ftx_resample_float_mono(input, input_count, source_rate, 12000,
                                 output, expected_count, &written) == FTX_RESAMPLE_OK
         && written == expected_count;
    for (size_t index = 0; ok && index < written; ++index) {
        ok = fabs((double) output[index] - 0.25) < 1.0e-5;
    }

    for (size_t index = 0; index < input_count; ++index) {
        input[index] = (float) sin(2.0 * kPi * 1000.0 * (double) index
                                   / (double) source_rate);
    }
    ok = ok && ftx_resample_float_mono(input, input_count, source_rate, 12000,
                                       output, expected_count, &written) == FTX_RESAMPLE_OK
         && tone_amplitude(output, written, 12000, 1000.0) > 0.95;

    for (size_t index = 0; index < input_count; ++index) {
        input[index] = (float) sin(2.0 * kPi * stop_frequency * (double) index
                                   / (double) source_rate);
    }
    ok = ok && ftx_resample_float_mono(input, input_count, source_rate, 12000,
                                       output, expected_count, &written) == FTX_RESAMPLE_OK
         && sample_rms(output, written) < 0.05;

    free(input);
    free(output);
    return ok;
}

static int run_stream_equivalence_case(int source_rate) {
    const size_t input_count = (size_t) source_rate / 5U + 37U;
    const size_t output_capacity = input_count / (size_t) (source_rate / 12000);
    float *input = (float *) malloc(input_count * sizeof(float));
    float *reference = (float *) malloc(output_capacity * sizeof(float));
    float *streamed = (float *) malloc(output_capacity * sizeof(float));
    ftx_resampler_stream_t *stream = NULL;
    size_t reference_count = 0;
    size_t streamed_count = 0;
    size_t input_offset = 0;
    int ok = input != NULL && reference != NULL && streamed != NULL;
    const size_t chunk_pattern[] = {1U, 7U, 64U, 3U, 511U, 29U};
    size_t chunk_index = 0;

    if (!ok) {
        free(input);
        free(reference);
        free(streamed);
        return 0;
    }
    for (size_t index = 0; index < input_count; ++index) {
        input[index] = (float) (0.31 * sin(2.0 * kPi * 997.0 * (double) index
                                         / (double) source_rate)
                                + 0.07 * cos(2.0 * kPi * 4211.0 * (double) index
                                             / (double) source_rate));
    }
    ok = ftx_resample_float_mono(input, input_count, source_rate, 12000,
                                 reference, output_capacity,
                                 &reference_count) == FTX_RESAMPLE_OK;
    stream = ftx_resampler_stream_create(source_rate, 12000);
    ok = ok && stream != NULL;
    while (ok && input_offset < input_count) {
        size_t chunk = chunk_pattern[chunk_index
                                     % (sizeof(chunk_pattern) / sizeof(chunk_pattern[0]))];
        size_t written = 0;
        if (chunk > input_count - input_offset) {
            chunk = input_count - input_offset;
        }
        ok = ftx_resampler_stream_process(
                stream,
                input + input_offset,
                chunk,
                streamed + streamed_count,
                output_capacity - streamed_count,
                &written) == FTX_RESAMPLE_OK;
        input_offset += chunk;
        streamed_count += written;
        chunk_index++;
    }
    if (ok) {
        size_t written = 0;
        ok = ftx_resampler_stream_finish(
                stream,
                streamed + streamed_count,
                output_capacity - streamed_count,
                &written) == FTX_RESAMPLE_OK;
        streamed_count += written;
    }
    ok = ok && streamed_count == reference_count;
    for (size_t index = 0; ok && index < reference_count; ++index) {
        ok = memcmp(&streamed[index], &reference[index], sizeof(float)) == 0;
    }

    ftx_resampler_stream_destroy(stream);
    free(input);
    free(reference);
    free(streamed);
    return ok;
}

static int run_q65_duration_capacity_case(void) {
    const int rates[] = {12000, 24000, 48000};
    const int durations[] = {30, 60, 120, 300};
    int ok = 1;
    for (size_t rate_index = 0;
         rate_index < sizeof(rates) / sizeof(rates[0]);
         ++rate_index) {
        for (size_t duration_index = 0;
             duration_index < sizeof(durations) / sizeof(durations[0]);
             ++duration_index) {
            const size_t input_count = (size_t) rates[rate_index]
                                       * (size_t) durations[duration_index];
            size_t output_count = 0;
            ok = ok && ftx_resample_required_output(
                    input_count, rates[rate_index], 12000,
                    &output_count) == FTX_RESAMPLE_OK;
            ok = ok && output_count == (size_t) 12000
                                             * (size_t) durations[duration_index];
        }
    }
    return ok;
}

int ftx_run_resampler_selftests(void) {
    const int rate_24k_ok = run_rate_case(24000);
    const int rate_48k_ok = run_rate_case(48000);
    const int stream_12k_ok = run_stream_equivalence_case(12000);
    const int stream_24k_ok = run_stream_equivalence_case(24000);
    const int stream_48k_ok = run_stream_equivalence_case(48000);
    const int q65_capacity_ok = run_q65_duration_capacity_case();
    size_t unused = 0;
    const int unsupported_ok = ftx_resample_required_output(
            44100, 44100, 12000, &unused) == FTX_RESAMPLE_UNSUPPORTED_RATE;

    printf("[%s] 24 kHz to 12 kHz resampler\n", rate_24k_ok ? "PASS" : "FAIL");
    printf("[%s] 48 kHz to 12 kHz resampler\n", rate_48k_ok ? "PASS" : "FAIL");
    printf("[%s] unsupported resampler input rejection\n",
           unsupported_ok ? "PASS" : "FAIL");
    printf("[%s] chunked 12 kHz equivalence\n", stream_12k_ok ? "PASS" : "FAIL");
    printf("[%s] chunked 24 kHz equivalence\n", stream_24k_ok ? "PASS" : "FAIL");
    printf("[%s] chunked 48 kHz equivalence\n", stream_48k_ok ? "PASS" : "FAIL");
    printf("[%s] Q65 30/60/120/300 second resampler capacity\n",
           q65_capacity_ok ? "PASS" : "FAIL");
    return rate_24k_ok && rate_48k_ok && unsupported_ok
           && stream_12k_ok && stream_24k_ok && stream_48k_ok
           && q65_capacity_ok ? 0 : -1;
}
