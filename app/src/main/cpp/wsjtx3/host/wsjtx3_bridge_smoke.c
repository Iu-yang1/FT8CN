#include "../wsjtx3_bridge.h"
#include "../../common/wave.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FT8_SMOKE_MAX_SAMPLES 3600000
#define WSJTX3_MODE_FT8 0
#define WSJTX3_MODE_FT4 1
#define WSJTX3_MODE_Q65 2

static int read_env_int(const char *name, int fallback) {
    const char *value = getenv(name);
    char *end = NULL;
    long parsed;

    if (value == NULL || value[0] == '\0') {
        return fallback;
    }

    parsed = strtol(value, &end, 10);
    if (end == value || (end != NULL && *end != '\0')) {
        return fallback;
    }
    return (int) parsed;
}

static int min_int(int lhs, int rhs) {
    return lhs < rhs ? lhs : rhs;
}

static const char *read_env_string(const char *name, const char *fallback) {
    const char *value = getenv(name);
    if (value == NULL || value[0] == '\0') {
        return fallback;
    }
    return value;
}

static long long hhmmss_to_utc_millis(int hhmmss) {
    const int hours = hhmmss / 10000;
    const int minutes = (hhmmss / 100) % 100;
    const int seconds = hhmmss % 100;
    return (long long) (((hours * 3600) + (minutes * 60) + seconds) * 1000LL);
}

static int run_sample(const char *label,
                      const char *wav_path,
                      int mode,
                      int hhmmss,
                      const char *my_call,
                      const char *his_call,
                      const char *his_grid,
                      int decode_pass_count,
                      int multi_decode_round_count,
                      int decode_sensitivity,
                      int qso_sensitivity,
                      int enable_early_decode,
                      int enable_wideband_dx_search,
                      int ldpc_iterations) {
    float *samples = (float *) calloc(FT8_SMOKE_MAX_SAMPLES, sizeof(float));
    int sample_count = FT8_SMOKE_MAX_SAMPLES;
    int sample_rate = 0;
    int handle;
    int result_count;
    int index;
    const int max_print_count = read_env_int("FT8CN_SMOKE_PRINT_COUNT", 10);

    if (samples == NULL) {
        fprintf(stderr, "%s: allocate samples failed\n", label);
        return 1;
    }

    if (load_wav(samples, &sample_count, &sample_rate, wav_path) != 0) {
        fprintf(stderr, "%s: load wav failed: %s\n", label, wav_path);
        free(samples);
        return 1;
    }

    fprintf(stderr, "%s: loaded rate=%d samples=%d path=%s\n", label, sample_rate, sample_count, wav_path);

    handle = wsjtx3_bridge_create(mode,
                                  sample_rate,
                                  sample_count,
                                  hhmmss_to_utc_millis(hhmmss));
    if (handle <= 0) {
        fprintf(stderr, "%s: create bridge handle failed\n", label);
        free(samples);
        return 1;
    }

    fprintf(stderr, "%s: handle=%d created\n", label, handle);

    wsjtx3_bridge_set_options(handle,
                              decode_pass_count,
                              multi_decode_round_count,
                              qso_sensitivity,
                              decode_sensitivity,
                              enable_early_decode,
                              enable_wideband_dx_search,
                              ldpc_iterations);
    wsjtx3_bridge_set_ap_hints(handle, my_call, his_call, his_grid);
    wsjtx3_bridge_set_qso_frequencies(handle, 1000, 1000);
    fprintf(stderr,
            "%s: options pushed passes=%d rounds=%d decodeSensitivity=%d qsoSensitivity=%d early=%d wideband=%d ldpc=%d, start process\n",
            label,
            decode_pass_count,
            multi_decode_round_count,
            decode_sensitivity,
            qso_sensitivity,
            enable_early_decode,
            enable_wideband_dx_search,
            ldpc_iterations);

    result_count = wsjtx3_bridge_process_float(handle, samples, sample_count);
    fprintf(stderr, "%s: process returned count=%d\n", label, result_count);
    printf("[%s] rate=%d samples=%d results=%d\n", label, sample_rate, sample_count, result_count);

    for (index = 0; index < min_int(result_count, max_print_count); ++index) {
        wsjtx3_bridge_decode_result_t result;
        memset(&result, 0, sizeof(result));
        if (!wsjtx3_bridge_get_result(handle, index, &result)) {
            continue;
        }
        printf("  #%d sync=%.2f snr=%d dt=%.2f freq=%.1f nap=%d text=%s\n",
               index,
               result.sync,
               result.snr,
               result.dt,
               result.freq,
               result.nap,
               result.decoded);
    }

    wsjtx3_bridge_destroy(handle);
    free(samples);
    return result_count > 0 ? 0 : 2;
}

int main(void) {
    int status = 0;
    int decode_pass_count = read_env_int("FT8CN_SMOKE_PASSES", 1);
    int multi_decode_round_count = read_env_int("FT8CN_SMOKE_ROUNDS", 1);
    int decode_sensitivity = read_env_int("FT8CN_SMOKE_DECODE_SENSITIVITY", 1);
    int qso_sensitivity = read_env_int("FT8CN_SMOKE_QSO_SENSITIVITY", 1);
    int enable_early_decode = read_env_int("FT8CN_SMOKE_EARLY", 0);
    int enable_wideband_dx_search = read_env_int("FT8CN_SMOKE_WIDEBAND", 0);
    int ldpc_iterations = read_env_int("FT8CN_SMOKE_LDPC", 20);
    int run_ft8 = read_env_int("FT8CN_SMOKE_RUN_FT8", 1);
    int run_ft4 = read_env_int("FT8CN_SMOKE_RUN_FT4", 1);
    int run_q65 = read_env_int("FT8CN_SMOKE_RUN_Q65", 0);
    int ft8_hhmmss = read_env_int("FT8CN_SMOKE_FT8_HHMMSS", 133430);
    int ft4_hhmmss = read_env_int("FT8CN_SMOKE_FT4_HHMMSS", 2);
    int q65_hhmmss = read_env_int("FT8CN_SMOKE_Q65_HHMMSS", 1621);
    const char *my_call = read_env_string("FT8CN_SMOKE_MY_CALL", "BG5JSU");
    const char *his_call = read_env_string("FT8CN_SMOKE_HIS_CALL", "");
    const char *his_grid = read_env_string("FT8CN_SMOKE_HIS_GRID", "");
    const char *ft8_sample_path = read_env_string("FT8CN_SMOKE_FT8_PATH",
                                                  ".tmp_wsjtx/samples/FT8/210703_133430.wav");
    const char *ft4_sample_path = read_env_string("FT8CN_SMOKE_FT4_PATH",
                                                  ".tmp_wsjtx/samples/FT4/000000_000002.wav");
    const char *q65_sample_path = read_env_string("FT8CN_SMOKE_Q65_PATH",
                                                  ".tmp_wsjtx/samples/Q65/60A_EME_6m/210106_1621.wav");
    setbuf(stdout, NULL);
    setbuf(stderr, NULL);

    if (run_ft8) {
        if (run_sample("FT8",
                       ft8_sample_path,
                       WSJTX3_MODE_FT8,
                       ft8_hhmmss,
                       my_call,
                       his_call,
                       his_grid,
                       decode_pass_count,
                       multi_decode_round_count,
                       decode_sensitivity,
                       qso_sensitivity,
                       enable_early_decode,
                       enable_wideband_dx_search,
                       ldpc_iterations) != 0) {
            status = 1;
        }
    }

    if (run_ft4) {
        if (run_sample("FT4",
                       ft4_sample_path,
                       WSJTX3_MODE_FT4,
                       ft4_hhmmss,
                       my_call,
                       his_call,
                       his_grid,
                       decode_pass_count,
                       multi_decode_round_count,
                       decode_sensitivity,
                       qso_sensitivity,
                       enable_early_decode,
                       enable_wideband_dx_search,
                       ldpc_iterations) != 0) {
            status = 1;
        }
    }

    if (run_q65) {
        if (run_sample("Q65",
                       q65_sample_path,
                       WSJTX3_MODE_Q65,
                       q65_hhmmss,
                       my_call,
                       his_call,
                       his_grid,
                       decode_pass_count,
                       multi_decode_round_count,
                       decode_sensitivity,
                       qso_sensitivity,
                       enable_early_decode,
                       enable_wideband_dx_search,
                       ldpc_iterations) != 0) {
            status = 1;
        }
    }

    return status;
}
