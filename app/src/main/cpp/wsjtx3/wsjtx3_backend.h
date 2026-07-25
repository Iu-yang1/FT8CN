#ifndef FT8CN_WSJTX3_BACKEND_H
#define FT8CN_WSJTX3_BACKEND_H

#ifdef __cplusplus
extern "C" {
#endif

#include "../ft8Decoder.h"

bool wsjtx3_backend_init_decoder(decoder_t *decoder,
                                 int64_t utcTime,
                                 int sample_rate,
                                 int num_samples,
                                 int mode);
void wsjtx3_backend_free_decoder(decoder_t *decoder);
void wsjtx3_backend_monitor_press(decoder_t *decoder, const float *signal, int sample_count);
int wsjtx3_backend_find_sync(decoder_t *decoder);
ft8_message wsjtx3_backend_analyze(decoder_t *decoder, int idx);
void wsjtx3_backend_reset(decoder_t *decoder, long utcTime, int num_samples);
void wsjtx3_backend_get_a91(decoder_t *decoder, uint8_t out[FTX_LDPC_K_BYTES]);
int wsjtx3_backend_get_last_bridge_raw_count(decoder_t *decoder);
int wsjtx3_backend_get_last_merged_count(decoder_t *decoder);
int wsjtx3_backend_get_bridge_context_id(decoder_t *decoder);
void wsjtx3_backend_set_ldpc_iterations(decoder_t *decoder, int iterations);
void wsjtx3_backend_set_ap_hints(decoder_t *decoder, const ap_hints_t *ap_hints);
void wsjtx3_backend_set_options(decoder_t *decoder, const wsjtx_decoder_options_t *options);
void wsjtx3_backend_set_q65_config(decoder_t *decoder, int q65_submode, int q65_tr_period_seconds);
void wsjtx3_backend_set_input_context(decoder_t *decoder,
                                      bool input_is_live,
                                      int qso_frequency_hz,
                                      int tx_frequency_hz,
                                      int source_sample_rate,
                                      int decode_stage);
void wsjtx3_backend_configure_runtime_dirs(const char *temp_dir, const char *data_dir);
int wsjtx3_backend_q65_required_samples(int q65_tr_period,
                                        int sample_rate,
                                        size_t *required_samples);
int wsjtx3_backend_generate_q65_wave(const char *message,
                                     int q65_submode,
                                     int q65_tr_period,
                                     int sample_rate,
                                     float base_frequency_hz,
                                     float *out_wave,
                                     int out_capacity);
bool wsjtx3_backend_owns_session_flow(decoder_t *decoder);
void wsjtx3_backend_subtract_signal(decoder_t *decoder,
                                    const uint8_t *payload,
                                    int sample_rate,
                                    float frequency,
                                    float time_sec,
                                    int mode);

#ifdef __cplusplus
}
#endif

#endif
