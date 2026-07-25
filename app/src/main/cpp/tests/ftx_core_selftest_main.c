#include "../ftx_core/include/ftx_selftest.h"

#include <stdio.h>

int ftx_run_synthetic_decode_selftests(void);
int ftx_run_resampler_selftests(void);
int ftx_run_q65_capacity_selftests(void);
int ftx_run_q65_averaging_selftests(void);
int ftx_run_q65_tx_rx_selftests(void);
int ftx_run_wsjtx3_osd_opt_selftests(void);
int ftx_run_channel_regression_selftests(void);
int ftx_run_request_context_selftests(void);

int main(void) {
    char report[4096];
    int rc = ftx_core_run_selftests(report, (int) sizeof(report));
    fputs(report, stdout);
    if (ftx_run_synthetic_decode_selftests() != 0) {
        rc = -1;
    }
    if (ftx_run_resampler_selftests() != 0) {
        rc = -1;
    }
    if (ftx_run_q65_capacity_selftests() != 0) {
        rc = -1;
    }
    if (ftx_run_q65_averaging_selftests() != 0) {
        rc = -1;
    }
    if (ftx_run_q65_tx_rx_selftests() != 0) {
        rc = -1;
    }
    if (ftx_run_wsjtx3_osd_opt_selftests() != 0) {
        rc = -1;
    }
    if (ftx_run_channel_regression_selftests() != 0) {
        rc = -1;
    }
    if (ftx_run_request_context_selftests() != 0) {
        rc = -1;
    }
    return rc == 0 ? 0 : 1;
}

