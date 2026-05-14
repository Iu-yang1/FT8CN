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

int wsjtx3_bridge_create(int is_ft8,
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
void wsjtx3_bridge_set_ap_hints(int handle,
                                const char *my_call,
                                const char *his_call,
                                const char *his_grid);
void wsjtx3_bridge_set_qso_frequencies(int handle, int qso_frequency_hz, int tx_frequency_hz);
int wsjtx3_bridge_process_float(int handle, const float *samples, int sample_count);
int wsjtx3_bridge_get_result_count(int handle);
int wsjtx3_bridge_get_result(int handle,
                             int index,
                             wsjtx3_bridge_decode_result_t *out_result);

#ifdef __cplusplus
}
#endif

#endif
