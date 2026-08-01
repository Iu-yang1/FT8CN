#include "../wsjtx3_bridge.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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
extern void ft8cn_ldpc_trace_sink(int handle,
                                  int active_context,
                                  long long utc_time,
                                  int profile_pass_count,
                                  int profile_round_count,
                                  int pass_index,
                                  int call_count,
                                  int bp_iterations,
                                  int osd_calls,
                                  int bp_success_count,
                                  int osd_success_count,
                                  long long total_us,
                                  long long setup_us,
                                  long long bp_llr_syndrome_us,
                                  long long bp_bit_to_check_us,
                                  long long bp_check_to_var_us,
                                  long long osd_us,
                                  long long other_us) __attribute__((weak));
extern void ft8cn_osd_trace_sink(int handle,
                                 int active_context,
                                 long long utc_time,
                                 int profile_pass_count,
                                 int profile_round_count,
                                 int pass_index,
                                 int call_count,
                                 int success_count,
                                 int fail_count,
                                 long long total_us,
                                 long long max_us,
                                 long long success_total_us,
                                 long long success_max_us,
                                 long long fail_total_us,
                                 long long fail_max_us,
                                 long long allocation_init_us,
                                 long long generator_init_us,
                                 long long input_prepare_us,
                                 long long sort_us,
                                 long long matrix_copy_us,
                                 long long gaussian_elim_us,
                                 long long matrix_permute_us,
                                 long long order0_us,
                                 long long order1_search_us,
                                 long long higher_order_search_us,
                                 long long second_preprocess_us,
                                 long long validation_us,
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

typedef struct {
    int call_count;
    int bp_iterations;
    int osd_calls;
    int bp_success_count;
    int osd_success_count;
    long long total_us;
    long long setup_us;
    long long bp_llr_syndrome_us;
    long long bp_bit_to_check_us;
    long long bp_check_to_var_us;
    long long osd_us;
} wsjtx3_ldpc_trace_accumulator_t;

static wsjtx3_ldpc_trace_accumulator_t g_ldpc_trace;

typedef struct {
    int call_count;
    int success_count;
    long long total_us;
    long long max_us;
    long long success_total_us;
    long long success_max_us;
    long long fail_total_us;
    long long fail_max_us;
    long long allocation_init_us;
    long long generator_init_us;
    long long input_prepare_us;
    long long sort_us;
    long long matrix_copy_us;
    long long gaussian_elim_us;
    long long matrix_permute_us;
    long long order0_us;
    long long order1_search_us;
    long long higher_order_search_us;
    long long second_preprocess_us;
    long long validation_us;
} wsjtx3_osd_trace_accumulator_t;

static wsjtx3_osd_trace_accumulator_t g_osd_trace;
int wsjtx3_phase_trace_is_enabled(void) {
#if defined(__GNUC__) || defined(__clang__)
    const char *value;
    if (ft8cn_native_phase_trace_enabled != 0) {
        return ft8cn_native_phase_trace_enabled() != 0;
    }
    value = getenv("FT8CN_PHASE_TRACE");
    return value != NULL && value[0] != '\0' && strcmp(value, "0") != 0;
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
    if (!wsjtx3_phase_trace_is_enabled()) {
        return;
    }
    if (ft8cn_native_phase_trace_sink != 0) {
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
    } else {
        fprintf(stderr,
                "nativePhase phase=%d mode=%d handle=%d activeContext=%d utc=%lld pass=%d "
                "round=%d q65Submode=%d q65TrPeriod=%d samples=%d results=%d durationUs=%lld\n",
                phase, mode, handle, active_context, utc_time, decode_pass_count,
                multi_decode_round_count, q65_submode, q65_tr_period, sample_count,
                result_count, duration_us);
    }
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
            || !wsjtx3_phase_trace_is_enabled()) {
        return;
    }
    if (ft8cn_vendor_phase_trace_sink != 0) {
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
    } else {
        fprintf(stderr,
                "vendorPhase phase=%d mode=%d pass=%d candidates=%d decoded=%d durationUs=%lld\n",
                phase, g_vendor_trace_context.mode, pass_index, candidate_count,
                decoded_count, duration_us);
    }
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
    memset(&g_ldpc_trace, 0, sizeof(g_ldpc_trace));
    memset(&g_osd_trace, 0, sizeof(g_osd_trace));
    if (!g_vendor_trace_context.active || !wsjtx3_phase_trace_is_enabled()) {
        return;
    }
    g_ft8b_trace.active = 1;
    g_ft8b_trace.pass_index = pass_index;
    g_ft8b_trace.candidate_count = candidate_count;
}

int wsjtx3_ldpc_trace_is_enabled(void) {
    return g_ft8b_trace.active;
}

int wsjtx3_osd_trace_is_enabled(void) {
    return g_ft8b_trace.active;
}

void wsjtx3_osd_trace_add(int success,
                          long long total_us,
                          long long allocation_init_us,
                          long long generator_init_us,
                          long long input_prepare_us,
                          long long sort_us,
                          long long matrix_copy_us,
                          long long gaussian_elim_us,
                          long long matrix_permute_us,
                          long long order0_us,
                          long long order1_search_us,
                          long long higher_order_search_us,
                          long long second_preprocess_us,
                          long long validation_us) {
    if (!g_ft8b_trace.active) {
        return;
    }
    g_osd_trace.call_count++;
    g_osd_trace.success_count += success != 0;
    g_osd_trace.total_us += total_us;
    if (total_us > g_osd_trace.max_us) {
        g_osd_trace.max_us = total_us;
    }
    if (success != 0) {
        g_osd_trace.success_total_us += total_us;
        if (total_us > g_osd_trace.success_max_us) {
            g_osd_trace.success_max_us = total_us;
        }
    } else {
        g_osd_trace.fail_total_us += total_us;
        if (total_us > g_osd_trace.fail_max_us) {
            g_osd_trace.fail_max_us = total_us;
        }
    }
    g_osd_trace.allocation_init_us += allocation_init_us;
    g_osd_trace.generator_init_us += generator_init_us;
    g_osd_trace.input_prepare_us += input_prepare_us;
    g_osd_trace.sort_us += sort_us;
    g_osd_trace.matrix_copy_us += matrix_copy_us;
    g_osd_trace.gaussian_elim_us += gaussian_elim_us;
    g_osd_trace.matrix_permute_us += matrix_permute_us;
    g_osd_trace.order0_us += order0_us;
    g_osd_trace.order1_search_us += order1_search_us;
    g_osd_trace.higher_order_search_us += higher_order_search_us;
    g_osd_trace.second_preprocess_us += second_preprocess_us;
    g_osd_trace.validation_us += validation_us;
}

void wsjtx3_ldpc_trace_add(int bp_iterations,
                           int osd_calls,
                           int bp_success,
                           int osd_success,
                           long long total_us,
                           long long setup_us,
                           long long bp_llr_syndrome_us,
                           long long bp_bit_to_check_us,
                           long long bp_check_to_var_us,
                           long long osd_us) {
    if (!g_ft8b_trace.active) {
        return;
    }
    g_ldpc_trace.call_count++;
    g_ldpc_trace.bp_iterations += bp_iterations;
    g_ldpc_trace.osd_calls += osd_calls;
    g_ldpc_trace.bp_success_count += bp_success != 0;
    g_ldpc_trace.osd_success_count += osd_success != 0;
    g_ldpc_trace.total_us += total_us;
    g_ldpc_trace.setup_us += setup_us;
    g_ldpc_trace.bp_llr_syndrome_us += bp_llr_syndrome_us;
    g_ldpc_trace.bp_bit_to_check_us += bp_bit_to_check_us;
    g_ldpc_trace.bp_check_to_var_us += bp_check_to_var_us;
    g_ldpc_trace.osd_us += osd_us;
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
    if (!g_ft8b_trace.active || !wsjtx3_phase_trace_is_enabled()) {
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
    if (ft8cn_ft8b_trace_sink != 0) {
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
    } else {
        fprintf(stderr,
                "ft8bTrace pass=%d candidates=%d calls=%d success=%d newDecodes=%d totalUs=%lld "
                "maxUs=%lld downsampleUs=%lld apUs=%lld ldpcUs=%lld validationUs=%lld "
                "unpackUs=%lld subtractUs=%lld otherUs=%lld\n",
                g_ft8b_trace.pass_index, g_ft8b_trace.candidate_count,
                g_ft8b_trace.call_count, g_ft8b_trace.success_count, new_decode_count,
                g_ft8b_trace.total_us, g_ft8b_trace.max_us, g_ft8b_trace.downsample_us,
                g_ft8b_trace.ap_us, g_ft8b_trace.ldpc_us, g_ft8b_trace.validation_us,
                g_ft8b_trace.unpack_us, g_ft8b_trace.subtract_us, other_us);
    }
    if (ft8cn_ldpc_trace_sink != 0 && g_ldpc_trace.call_count > 0) {
        measured_us = g_ldpc_trace.setup_us
                + g_ldpc_trace.bp_llr_syndrome_us
                + g_ldpc_trace.bp_bit_to_check_us
                + g_ldpc_trace.bp_check_to_var_us
                + g_ldpc_trace.osd_us;
        other_us = g_ldpc_trace.total_us > measured_us
                ? g_ldpc_trace.total_us - measured_us
                : 0;
        ft8cn_ldpc_trace_sink(g_vendor_trace_context.handle,
                              g_vendor_trace_context.active_context,
                              g_vendor_trace_context.utc_time,
                              g_vendor_trace_context.decode_pass_count,
                              g_vendor_trace_context.multi_decode_round_count,
                              g_ft8b_trace.pass_index,
                              g_ldpc_trace.call_count,
                              g_ldpc_trace.bp_iterations,
                              g_ldpc_trace.osd_calls,
                              g_ldpc_trace.bp_success_count,
                              g_ldpc_trace.osd_success_count,
                              g_ldpc_trace.total_us,
                              g_ldpc_trace.setup_us,
                              g_ldpc_trace.bp_llr_syndrome_us,
                              g_ldpc_trace.bp_bit_to_check_us,
                              g_ldpc_trace.bp_check_to_var_us,
                               g_ldpc_trace.osd_us,
                               other_us);
    } else if (g_ldpc_trace.call_count > 0) {
        measured_us = g_ldpc_trace.setup_us
                + g_ldpc_trace.bp_llr_syndrome_us
                + g_ldpc_trace.bp_bit_to_check_us
                + g_ldpc_trace.bp_check_to_var_us
                + g_ldpc_trace.osd_us;
        other_us = g_ldpc_trace.total_us > measured_us
                ? g_ldpc_trace.total_us - measured_us
                : 0;
        fprintf(stderr,
                "ldpcTrace pass=%d calls=%d bpIterations=%d osdCalls=%d bpSuccess=%d "
                "osdSuccess=%d totalUs=%lld setupUs=%lld syndromeUs=%lld bitToCheckUs=%lld "
                "checkToVarUs=%lld osdUs=%lld otherUs=%lld\n",
                g_ft8b_trace.pass_index, g_ldpc_trace.call_count,
                g_ldpc_trace.bp_iterations, g_ldpc_trace.osd_calls,
                g_ldpc_trace.bp_success_count, g_ldpc_trace.osd_success_count,
                g_ldpc_trace.total_us, g_ldpc_trace.setup_us,
                g_ldpc_trace.bp_llr_syndrome_us, g_ldpc_trace.bp_bit_to_check_us,
                g_ldpc_trace.bp_check_to_var_us, g_ldpc_trace.osd_us, other_us);
    }
    if (ft8cn_osd_trace_sink != 0 && g_osd_trace.call_count > 0) {
        measured_us = g_osd_trace.allocation_init_us
                + g_osd_trace.generator_init_us
                + g_osd_trace.input_prepare_us
                + g_osd_trace.sort_us
                + g_osd_trace.matrix_copy_us
                + g_osd_trace.gaussian_elim_us
                + g_osd_trace.matrix_permute_us
                + g_osd_trace.order0_us
                + g_osd_trace.order1_search_us
                + g_osd_trace.higher_order_search_us
                + g_osd_trace.second_preprocess_us
                + g_osd_trace.validation_us;
        other_us = g_osd_trace.total_us > measured_us
                ? g_osd_trace.total_us - measured_us
                : 0;
        ft8cn_osd_trace_sink(g_vendor_trace_context.handle,
                             g_vendor_trace_context.active_context,
                             g_vendor_trace_context.utc_time,
                             g_vendor_trace_context.decode_pass_count,
                             g_vendor_trace_context.multi_decode_round_count,
                             g_ft8b_trace.pass_index,
                             g_osd_trace.call_count,
                             g_osd_trace.success_count,
                             g_osd_trace.call_count - g_osd_trace.success_count,
                             g_osd_trace.total_us,
                             g_osd_trace.max_us,
                             g_osd_trace.success_total_us,
                             g_osd_trace.success_max_us,
                             g_osd_trace.fail_total_us,
                             g_osd_trace.fail_max_us,
                             g_osd_trace.allocation_init_us,
                             g_osd_trace.generator_init_us,
                             g_osd_trace.input_prepare_us,
                             g_osd_trace.sort_us,
                             g_osd_trace.matrix_copy_us,
                             g_osd_trace.gaussian_elim_us,
                             g_osd_trace.matrix_permute_us,
                             g_osd_trace.order0_us,
                             g_osd_trace.order1_search_us,
                             g_osd_trace.higher_order_search_us,
                             g_osd_trace.second_preprocess_us,
                             g_osd_trace.validation_us,
                             other_us);
    } else if (g_osd_trace.call_count > 0) {
        measured_us = g_osd_trace.allocation_init_us
                + g_osd_trace.generator_init_us
                + g_osd_trace.input_prepare_us
                + g_osd_trace.sort_us
                + g_osd_trace.matrix_copy_us
                + g_osd_trace.gaussian_elim_us
                + g_osd_trace.matrix_permute_us
                + g_osd_trace.order0_us
                + g_osd_trace.order1_search_us
                + g_osd_trace.higher_order_search_us
                + g_osd_trace.second_preprocess_us
                + g_osd_trace.validation_us;
        other_us = g_osd_trace.total_us > measured_us
                ? g_osd_trace.total_us - measured_us
                : 0;
        fprintf(stderr,
                "osdTrace pass=%d calls=%d success=%d totalUs=%lld maxUs=%lld allocationUs=%lld "
                "generatorUs=%lld inputUs=%lld sortUs=%lld matrixCopyUs=%lld gaussianUs=%lld "
                "permuteUs=%lld order0Us=%lld order1Us=%lld higherOrderUs=%lld secondPreUs=%lld "
                "validationUs=%lld otherUs=%lld\n",
                g_ft8b_trace.pass_index, g_osd_trace.call_count, g_osd_trace.success_count,
                g_osd_trace.total_us, g_osd_trace.max_us, g_osd_trace.allocation_init_us,
                g_osd_trace.generator_init_us, g_osd_trace.input_prepare_us, g_osd_trace.sort_us,
                g_osd_trace.matrix_copy_us, g_osd_trace.gaussian_elim_us,
                g_osd_trace.matrix_permute_us, g_osd_trace.order0_us,
                g_osd_trace.order1_search_us, g_osd_trace.higher_order_search_us,
                g_osd_trace.second_preprocess_us, g_osd_trace.validation_us, other_us);
    }
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
