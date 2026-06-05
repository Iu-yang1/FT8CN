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

#ifdef __cplusplus
}
#endif

#endif
