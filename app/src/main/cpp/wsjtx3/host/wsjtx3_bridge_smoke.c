#include "../wsjtx3_bridge.h"
#include "../../common/wave.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FT8_SMOKE_MAX_SAMPLES 200000

static long long hhmmss_to_utc_millis(int hhmmss) {
    const int hours = hhmmss / 10000;
    const int minutes = (hhmmss / 100) % 100;
    const int seconds = hhmmss % 100;
    return (long long) (((hours * 3600) + (minutes * 60) + seconds) * 1000LL);
}

static int run_sample(const char *label,
                      const char *wav_path,
                      int is_ft8,
                      int hhmmss,
                      const char *my_call,
                      const char *his_call,
                      const char *his_grid) {
    float *samples = (float *) calloc(FT8_SMOKE_MAX_SAMPLES, sizeof(float));
    int sample_count = FT8_SMOKE_MAX_SAMPLES;
    int sample_rate = 0;
    int handle;
    int result_count;
    int index;

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

    handle = wsjtx3_bridge_create(is_ft8,
                                  sample_rate,
                                  sample_count,
                                  hhmmss_to_utc_millis(hhmmss));
    if (handle <= 0) {
        fprintf(stderr, "%s: create bridge handle failed\n", label);
        free(samples);
        return 1;
    }

    fprintf(stderr, "%s: handle=%d created\n", label, handle);

    wsjtx3_bridge_set_options(handle, 1, 1, 1, 1, 0, 0, 20);
    wsjtx3_bridge_set_ap_hints(handle, "", "", "");
    wsjtx3_bridge_set_qso_frequencies(handle, 1000, 1000);
    fprintf(stderr, "%s: options pushed, start process\n", label);

    result_count = wsjtx3_bridge_process_float(handle, samples, sample_count);
    fprintf(stderr, "%s: process returned count=%d\n", label, result_count);
    printf("[%s] rate=%d samples=%d results=%d\n", label, sample_rate, sample_count, result_count);

    for (index = 0; index < result_count && index < 10; ++index) {
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
    setbuf(stdout, NULL);
    setbuf(stderr, NULL);

    if (run_sample("FT8",
                   "H:/iu_yang1/study/FT8CN/ft8cn/.tmp_wsjtx/samples/FT8/210703_133430.wav",
                   1,
                   133430,
                   "BG5JSU",
                   "JA6RJK",
                   "PM53") != 0) {
        status = 1;
    }

    if (run_sample("FT4",
                   "H:/iu_yang1/study/FT8CN/ft8cn/.tmp_wsjtx/samples/FT4/000000_000002.wav",
                   0,
                   2,
                   "BG5JSU",
                   "JA6RJK",
                   "PM53") != 0) {
        status = 1;
    }

    return status;
}
