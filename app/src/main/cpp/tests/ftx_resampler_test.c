#include "../common/resampler.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>

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

int ftx_run_resampler_selftests(void) {
    const int rate_24k_ok = run_rate_case(24000);
    const int rate_48k_ok = run_rate_case(48000);
    size_t unused = 0;
    const int unsupported_ok = ftx_resample_required_output(
            44100, 44100, 12000, &unused) == FTX_RESAMPLE_UNSUPPORTED_RATE;

    printf("[%s] 24 kHz to 12 kHz resampler\n", rate_24k_ok ? "PASS" : "FAIL");
    printf("[%s] 48 kHz to 12 kHz resampler\n", rate_48k_ok ? "PASS" : "FAIL");
    printf("[%s] unsupported resampler input rejection\n",
           unsupported_ok ? "PASS" : "FAIL");
    return rate_24k_ok && rate_48k_ok && unsupported_ok ? 0 : -1;
}
