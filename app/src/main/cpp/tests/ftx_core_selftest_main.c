#include "../ftx_core/include/ftx_selftest.h"

#include <stdio.h>

int ftx_run_synthetic_decode_selftests(void);

int main(void) {
    char report[4096];
    int rc = ftx_core_run_selftests(report, (int) sizeof(report));
    fputs(report, stdout);
    if (ftx_run_synthetic_decode_selftests() != 0) {
        rc = -1;
    }
    return rc == 0 ? 0 : 1;
}

