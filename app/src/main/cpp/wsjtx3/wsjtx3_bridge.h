#ifndef FT8CN_WSJTX3_BRIDGE_H
#define FT8CN_WSJTX3_BRIDGE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int snr;
    int nap;
    float sync;
    float dt;
    float freq;
    float qual;
    char decoded[38];
} wsjtx3_bridge_decode_result_t;

int wsjtx3_bridge_create(int mode,
                         int sample_rate,
                         int expected_samples,
                         long long utc_time);
void wsjtx3_bridge_destroy(int handle);
void wsjtx3_bridge_reset(int handle, long long utc_time, int expected_samples);
void wsjtx3_bridge_set_options(int handle,
                               int decode_pass_count,
                               int multi_decode_round_count,
                               int qso_freq_sensitivity,
                               int decode_sensitivity,
                               int enable_early_decode,
                               int enable_wideband_dx_search,
                               int ldpc_iterations);
void wsjtx3_bridge_set_q65_params(int handle, int q65_submode, int q65_tr_period);
void wsjtx3_bridge_set_ap_hints(int handle,
                                const char *my_call,
                                const char *his_call,
                                const char *his_grid);
void wsjtx3_bridge_set_qso_frequencies(int handle, int qso_frequency_hz, int tx_frequency_hz);
void wsjtx3_bridge_set_runtime_dirs(const char *temp_dir, const char *data_dir);
int wsjtx3_bridge_generate_q65_wave(const char *message,
                                    int q65_submode,
                                    int q65_tr_period,
                                    int sample_rate,
                                    float base_frequency_hz,
                                    float *out_wave,
                                    int out_capacity);
int wsjtx3_bridge_process_float(int handle, const float *samples, int sample_count);
int wsjtx3_bridge_get_result_count(int handle);
int wsjtx3_bridge_get_result(int handle,
                             int index,
                             wsjtx3_bridge_decode_result_t *out_result);

/*
 * 仅用于诊断构建的轻量 phase tracing。Android 侧通过
 * `setprop log.tag.WSJTX3Phase DEBUG` 开启，默认关闭。
 */
int wsjtx3_phase_trace_is_enabled(void);
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
                              long long duration_us);
void wsjtx3_vendor_trace_set_context(int handle,
                                     int active_context,
                                     int mode,
                                     long long utc_time,
                                     int decode_pass_count,
                                     int multi_decode_round_count,
                                     int q65_submode,
                                     int q65_tr_period,
                                     int sample_count);
void wsjtx3_vendor_trace_clear_context(void);
void wsjtx3_vendor_trace_event(int phase,
                               int pass_index,
                               int candidate_count,
                               int decoded_count,
                               long long duration_us);
void wsjtx3_ft8b_trace_reset(int pass_index, int candidate_count);
int wsjtx3_ldpc_trace_is_enabled(void);
void wsjtx3_ft8b_trace_add(int success,
                           long long total_us,
                           long long downsample_us,
                           long long ap_us,
                           long long ldpc_us,
                           long long validation_us,
                           long long unpack_us,
                           long long subtract_us);
void wsjtx3_ldpc_trace_add(int bp_iterations,
                           int osd_calls,
                           int bp_success,
                           int osd_success,
                           long long total_us,
                           long long setup_us,
                           long long bp_llr_syndrome_us,
                           long long bp_bit_to_check_us,
                           long long bp_check_to_var_us,
                           long long osd_us);
int wsjtx3_osd_trace_is_enabled(void);
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
                          long long validation_us);
void wsjtx3_ft8b_trace_flush(int new_decode_count);
int wsjtx3_callback_slot_is_enabled(void);
void wsjtx3_callback_slot_trace_event(int active_context,
                                      int explicit_context,
                                      int callback_slot,
                                      int result_count,
                                      int mismatch);

#ifdef __cplusplus
}
#endif

#endif
