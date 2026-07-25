#include "../wsjtx3/wsjtx3_bridge.h"

#include <stdio.h>
#include <stdlib.h>

int ftx_run_q65_averaging_selftests(void) {
    const int sample_count = 60 * 12000;
    float *samples = (float *) calloc((size_t) sample_count, sizeof(float));
    int handle = 0;
    int first_navg = 0;
    int second_navg = 0;
    int clear_pending = 1;
    int ok = samples != NULL;

    if (!ok) {
        return -1;
    }
    handle = wsjtx3_bridge_create(2, 12000, sample_count, 0);
    ok = handle > 0;
    if (ok) {
        wsjtx3_bridge_set_q65_params(handle, 0, 60);
        wsjtx3_bridge_set_options(handle, 1, 1, 1, 1, 0, 0, 20);
        wsjtx3_bridge_reset(handle, 0, sample_count);
        ok = wsjtx3_bridge_process_float(handle, samples, sample_count) >= 0
             && wsjtx3_bridge_get_q65_averaging_state(
                     handle, &first_navg, &clear_pending)
             && clear_pending == 0;
    }
    if (ok) {
        wsjtx3_bridge_reset(handle, 120000, sample_count);
        ok = wsjtx3_bridge_process_float(handle, samples, sample_count) >= 0
             && wsjtx3_bridge_get_q65_averaging_state(
                     handle, &second_navg, &clear_pending)
             && clear_pending == 0
             && second_navg > first_navg;
    }

    if (ok) {
        wsjtx3_bridge_set_qso_frequencies(handle, 1100, 1000);
        ok = wsjtx3_bridge_get_q65_averaging_state(
                handle, &second_navg, &clear_pending)
             && clear_pending == 1;
    }
    if (ok) {
        wsjtx3_bridge_reset_q65_averaging(handle);
        ok = wsjtx3_bridge_get_q65_averaging_state(
                handle, &second_navg, &clear_pending)
             && clear_pending == 1 && second_navg == 0;
    }
    if (handle > 0) {
        wsjtx3_bridge_destroy(handle);
    }
    free(samples);

    printf("[%s] Q65 same-parity averaging persistence/reset isolation\n",
           ok ? "PASS" : "FAIL");
    return ok ? 0 : -1;
}
