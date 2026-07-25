#include "../wsjtx3/wsjtx3_bridge.h"

#include <stdio.h>

enum {
    kFt8Samples = 180000,
    kFt8EarlySamples = 147600,
    kFt8LateSamples = 169200
};

static int read_context(int handle,
                        int expected_mode,
                        int expected_live,
                        int expected_qso,
                        int expected_tx,
                        int expected_passes,
                        int expected_rounds) {
    int mode;
    int input_is_live;
    int qso_frequency_hz;
    int tx_frequency_hz;
    int decode_pass_count;
    int multi_decode_round_count;
    return wsjtx3_bridge_get_context_state(handle,
                                           &mode,
                                           &input_is_live,
                                           &qso_frequency_hz,
                                           &tx_frequency_hz,
                                           &decode_pass_count,
                                           &multi_decode_round_count)
            && mode == expected_mode
            && input_is_live == expected_live
            && qso_frequency_hz == expected_qso
            && tx_frequency_hz == expected_tx
            && decode_pass_count == expected_passes
            && multi_decode_round_count == expected_rounds;
}

static int test_ft8_context_and_phase(void) {
    int handle = wsjtx3_bridge_create(0, 12000, kFt8Samples, 0);
    int ok = handle > 0;
    if (ok) {
        wsjtx3_bridge_set_input_is_live(handle, 1);
        wsjtx3_bridge_set_qso_frequencies(handle, 3200, 2600);
        wsjtx3_bridge_set_options(handle, 5, 0, 1, 1, 1, 1, 20);
        ok = read_context(handle, 0, 1, 3000, 2600, 3, 1)
                && wsjtx3_bridge_get_ft8_phase(handle, kFt8EarlySamples - 1) == 0
                && wsjtx3_bridge_get_ft8_phase(handle, kFt8EarlySamples) == 41
                && wsjtx3_bridge_get_ft8_phase(handle, kFt8LateSamples) == 47
                && wsjtx3_bridge_get_ft8_phase(handle, kFt8Samples) == 50;
        wsjtx3_bridge_set_options(handle, 2, 2, 1, 1, 0, 1, 20);
        ok = ok
                && wsjtx3_bridge_get_ft8_phase(handle, kFt8LateSamples) == 0
                && wsjtx3_bridge_get_ft8_phase(handle, kFt8Samples) == 50;
        wsjtx3_bridge_destroy(handle);
    }

    /* 复用 handle 时必须恢复文件输入默认值，不能继承上一会话的实时标志。 */
    handle = wsjtx3_bridge_create(0, 12000, kFt8Samples, 0);
    ok = ok && handle > 0 && read_context(handle, 0, 0, 1000, 1000, 3, 3);
    if (handle > 0) {
        wsjtx3_bridge_destroy(handle);
    }
    return ok;
}

static int test_mode_frequency_limits(void) {
    int ft4_handle = wsjtx3_bridge_create(1, 12000, 72576, 0);
    int q65_handle = wsjtx3_bridge_create(2, 12000, 720000, 0);
    int ok = ft4_handle > 0 && q65_handle > 0;
    if (ft4_handle > 0) {
        wsjtx3_bridge_set_qso_frequencies(ft4_handle, 4500, 4500);
        ok = ok && read_context(ft4_handle, 1, 0, 3000, 3000, 3, 3)
                && wsjtx3_bridge_get_ft8_phase(ft4_handle, 72576) == 0;
        wsjtx3_bridge_destroy(ft4_handle);
    }
    if (q65_handle > 0) {
        wsjtx3_bridge_set_input_is_live(q65_handle, 1);
        wsjtx3_bridge_set_qso_frequencies(q65_handle, 9000, 7000);
        ok = ok && read_context(q65_handle, 2, 1, 5000, 5000, 3, 3);
        wsjtx3_bridge_destroy(q65_handle);
    }
    return ok;
}

int ftx_run_request_context_selftests(void) {
    const int ft8_ok = test_ft8_context_and_phase();
    const int frequency_ok = test_mode_frequency_limits();
    printf("[%s] request live/disk snapshot and FT8 phase mapping\n",
           ft8_ok ? "PASS" : "FAIL");
    printf("[%s] FT8/FT4/Q65 frequency clamp propagation\n",
           frequency_ok ? "PASS" : "FAIL");
    return ft8_ok && frequency_ok ? 0 : -1;
}
