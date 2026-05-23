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
void wsjtx3_backend_set_ldpc_iterations(decoder_t *decoder, int iterations);
void wsjtx3_backend_set_ap_hints(decoder_t *decoder, const ap_hints_t *ap_hints);
void wsjtx3_backend_set_options(decoder_t *decoder, const wsjtx_decoder_options_t *options);
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
