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
extern void ft8cn_vendor_phase_trace_sink(int handle,
                                          int active_context,
                                          int mode,
                                          int phase,
                                          long long utc_time,
                                          int decode_pass_count,
                                          int multi_decode_round_count,
                                          int q65_submode,
                                          int q65_tr_period,
                                          int sample_count,
                                          int pass_index,
                                          int candidate_count,
                                          int decoded_count,
                                          long long duration_us) __attribute__((weak));
extern int ft8cn_callback_slot_enabled(void) __attribute__((weak));
extern void ft8cn_callback_slot_trace_sink(int active_context,
                                           int explicit_context,
                                           int callback_slot,
                                           int result_count,
                                           int mismatch) __attribute__((weak));
extern void ft8cn_ft8b_trace_sink(int handle,
                                  int active_context,
                                  long long utc_time,
                                  int profile_pass_count,
                                  int profile_round_count,
                                  int pass_index,
                                  int candidate_count,
                                  int success_count,
                                  int fail_count,
                                  int new_decode_count,
                                  long long total_us,
                                  long long max_us,
                                  long long downsample_us,
                                  long long ap_us,
                                  long long ldpc_us,
                                  long long validation_us,
                                  long long unpack_us,
                                  long long subtract_us,
                                  long long other_us) __attribute__((weak));
#endif

typedef struct {
    int active;
    int handle;
    int active_context;
    int mode;
    long long utc_time;
    int decode_pass_count;
    int multi_decode_round_count;
    int q65_submode;
    int q65_tr_period;
    int sample_count;
} wsjtx3_vendor_trace_context_t;

/*
 * Bridge 解码入口仍由上层锁串行保护，因此诊断上下文可安全复用。
 * 真正启用 native 并行前必须把它改为显式参数或线程局部存储。
 */
static wsjtx3_vendor_trace_context_t g_vendor_trace_context;

typedef struct {
    int active;
    int pass_index;
    int candidate_count;
    int success_count;
    int call_count;
    long long total_us;
    long long max_us;
    long long downsample_us;
    long long ap_us;
    long long ldpc_us;
    long long validation_us;
    long long unpack_us;
    long long subtract_us;
} wsjtx3_ft8b_trace_accumulator_t;

static wsjtx3_ft8b_trace_accumulator_t g_ft8b_trace;

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

void wsjtx3_vendor_trace_set_context(int handle,
                                     int active_context,
                                     int mode,
                                     long long utc_time,
                                     int decode_pass_count,
                                     int multi_decode_round_count,
                                     int q65_submode,
                                     int q65_tr_period,
                                     int sample_count) {
    if (!wsjtx3_phase_trace_is_enabled()) {
        g_vendor_trace_context.active = 0;
        return;
    }
    g_vendor_trace_context.active = 1;
    g_vendor_trace_context.handle = handle;
    g_vendor_trace_context.active_context = active_context;
    g_vendor_trace_context.mode = mode;
    g_vendor_trace_context.utc_time = utc_time;
    g_vendor_trace_context.decode_pass_count = decode_pass_count;
    g_vendor_trace_context.multi_decode_round_count = multi_decode_round_count;
    g_vendor_trace_context.q65_submode = q65_submode;
    g_vendor_trace_context.q65_tr_period = q65_tr_period;
    g_vendor_trace_context.sample_count = sample_count;
}

void wsjtx3_vendor_trace_clear_context(void) {
    g_vendor_trace_context.active = 0;
    g_ft8b_trace.active = 0;
}

void wsjtx3_vendor_trace_event(int phase,
                               int pass_index,
                               int candidate_count,
                               int decoded_count,
                               long long duration_us) {
#if defined(__GNUC__) || defined(__clang__)
    if (!g_vendor_trace_context.active
            || ft8cn_vendor_phase_trace_sink == 0
            || !wsjtx3_phase_trace_is_enabled()) {
        return;
    }
    ft8cn_vendor_phase_trace_sink(g_vendor_trace_context.handle,
                                  g_vendor_trace_context.active_context,
                                  g_vendor_trace_context.mode,
                                  phase,
                                  g_vendor_trace_context.utc_time,
                                  g_vendor_trace_context.decode_pass_count,
                                  g_vendor_trace_context.multi_decode_round_count,
                                  g_vendor_trace_context.q65_submode,
                                  g_vendor_trace_context.q65_tr_period,
                                  g_vendor_trace_context.sample_count,
                                  pass_index,
                                  candidate_count,
                                  decoded_count,
                                  duration_us);
#else
    (void) phase;
    (void) pass_index;
    (void) candidate_count;
    (void) decoded_count;
    (void) duration_us;
#endif
}

void wsjtx3_ft8b_trace_reset(int pass_index, int candidate_count) {
    memset(&g_ft8b_trace, 0, sizeof(g_ft8b_trace));
    if (!g_vendor_trace_context.active || !wsjtx3_phase_trace_is_enabled()) {
        return;
    }
    g_ft8b_trace.active = 1;
    g_ft8b_trace.pass_index = pass_index;
    g_ft8b_trace.candidate_count = candidate_count;
}

void wsjtx3_ft8b_trace_add(int success,
                           long long total_us,
                           long long downsample_us,
                           long long ap_us,
                           long long ldpc_us,
                           long long validation_us,
                           long long unpack_us,
                           long long subtract_us) {
    if (!g_ft8b_trace.active) {
        return;
    }
    g_ft8b_trace.call_count++;
    g_ft8b_trace.success_count += success != 0;
    g_ft8b_trace.total_us += total_us;
    if (total_us > g_ft8b_trace.max_us) {
        g_ft8b_trace.max_us = total_us;
    }
    g_ft8b_trace.downsample_us += downsample_us;
    g_ft8b_trace.ap_us += ap_us;
    g_ft8b_trace.ldpc_us += ldpc_us;
    g_ft8b_trace.validation_us += validation_us;
    g_ft8b_trace.unpack_us += unpack_us;
    g_ft8b_trace.subtract_us += subtract_us;
}

void wsjtx3_ft8b_trace_flush(int new_decode_count) {
#if defined(__GNUC__) || defined(__clang__)
    long long measured_us;
    long long other_us;
    if (!g_ft8b_trace.active
            || ft8cn_ft8b_trace_sink == 0
            || !wsjtx3_phase_trace_is_enabled()) {
        g_ft8b_trace.active = 0;
        return;
    }
    measured_us = g_ft8b_trace.downsample_us
            + g_ft8b_trace.ap_us
            + g_ft8b_trace.ldpc_us
            + g_ft8b_trace.validation_us
            + g_ft8b_trace.unpack_us
            + g_ft8b_trace.subtract_us;
    other_us = g_ft8b_trace.total_us > measured_us
            ? g_ft8b_trace.total_us - measured_us
            : 0;
    ft8cn_ft8b_trace_sink(g_vendor_trace_context.handle,
                          g_vendor_trace_context.active_context,
                          g_vendor_trace_context.utc_time,
                          g_vendor_trace_context.decode_pass_count,
                          g_vendor_trace_context.multi_decode_round_count,
                          g_ft8b_trace.pass_index,
                          g_ft8b_trace.candidate_count,
                          g_ft8b_trace.success_count,
                          g_ft8b_trace.call_count - g_ft8b_trace.success_count,
                          new_decode_count,
                          g_ft8b_trace.total_us,
                          g_ft8b_trace.max_us,
                          g_ft8b_trace.downsample_us,
                          g_ft8b_trace.ap_us,
                          g_ft8b_trace.ldpc_us,
                          g_ft8b_trace.validation_us,
                          g_ft8b_trace.unpack_us,
                          g_ft8b_trace.subtract_us,
                          other_us);
#else
    (void) new_decode_count;
#endif
    g_ft8b_trace.active = 0;
}

int wsjtx3_callback_slot_is_enabled(void) {
#if defined(__GNUC__) || defined(__clang__)
    return ft8cn_callback_slot_enabled != 0
            && ft8cn_callback_slot_enabled() != 0;
#else
    return 0;
#endif
}

void wsjtx3_callback_slot_trace_event(int active_context,
                                      int explicit_context,
                                      int callback_slot,
                                      int result_count,
                                      int mismatch) {
#if defined(__GNUC__) || defined(__clang__)
    if (ft8cn_callback_slot_trace_sink == 0 || !wsjtx3_callback_slot_is_enabled()) {
        return;
    }
    ft8cn_callback_slot_trace_sink(active_context,
                                   explicit_context,
                                   callback_slot,
                                   result_count,
                                   mismatch);
#else
    (void) active_context;
    (void) explicit_context;
    (void) callback_slot;
    (void) result_count;
    (void) mismatch;
#endif
}
