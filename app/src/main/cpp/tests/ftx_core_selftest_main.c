#include "../ftx_core/include/ftx_selftest.h"

#include <stdio.h>

int main(void) {
    char report[4096];
    int rc = ftx_core_run_selftests(report, (int) sizeof(report));
    fputs(report, stdout);
    return rc == 0 ? 0 : 1;
}
