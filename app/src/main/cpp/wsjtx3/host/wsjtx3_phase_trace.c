#include "../wsjtx3_bridge.h"

/*
 * Official core 会先独立做 link validation，因此这里通过弱 hook 与 Android
 * binding 解耦。未链接宿主 hook 时 tracing 默认关闭且不产生额外日志。
 */
#if defined(__GNUC__) || defined(__clang__)
extern int ft8cn_native_phase_trace_enabled(void) __attribute__((weak));
extern void ft8cn_native_phase_trace_sink(int handle,
                                          int active_context,
                                          int mode,
                                          int phase,
                                          long long utc_time,
                                          int decode_pass_count,
                                          int multi_decode_round_count,
                                          int q65_submode,
                                          int q65_tr_period,
                                          int sample_count,
                                          int result_count,
                                          long long duration_us) __attribute__((weak));
#endif

int wsjtx3_phase_trace_is_enabled(void) {
#if defined(__GNUC__) || defined(__clang__)
    return ft8cn_native_phase_trace_enabled != 0
            && ft8cn_native_phase_trace_enabled() != 0;
#else
    return 0;
#endif
}

void wsjtx3_phase_trace_event(int handle,
                              int active_context,
                              int mode,
                              int phase,
                              long long utc_time,
                              int decode_pass_count,
                              int multi_decode_round_count,
                              int q65_submode,
                              int q65_tr_period,
                              int sample_count,
                              int result_count,
                              long long duration_us) {
#if defined(__GNUC__) || defined(__clang__)
    if (ft8cn_native_phase_trace_sink == 0 || !wsjtx3_phase_trace_is_enabled()) {
        return;
    }
    ft8cn_native_phase_trace_sink(handle,
                                  active_context,
                                  mode,
                                  phase,
                                  utc_time,
                                  decode_pass_count,
                                  multi_decode_round_count,
                                  q65_submode,
                                  q65_tr_period,
                                  sample_count,
                                  result_count,
                                  duration_us);
#else
    (void) handle;
    (void) active_context;
    (void) mode;
    (void) phase;
    (void) utc_time;
    (void) decode_pass_count;
    (void) multi_decode_round_count;
    (void) q65_submode;
    (void) q65_tr_period;
    (void) sample_count;
    (void) result_count;
    (void) duration_us;
#endif
}
