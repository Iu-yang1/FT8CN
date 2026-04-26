#ifndef FT8CN_WSJTX_PORT_H
#define FT8CN_WSJTX_PORT_H

#ifdef __cplusplus
extern "C" {
#endif

#include "../ft8Decoder.h"

bool wsjtx_port_init_decoder(decoder_t *decoder,
                             int64_t utcTime,
                             int sample_rate,
                             int num_samples,
                             bool is_ft8);
void wsjtx_port_free_decoder(decoder_t *decoder);
void wsjtx_port_monitor_press(decoder_t *decoder, const float *signal, int sample_count);
int wsjtx_port_find_sync(decoder_t *decoder);
ft8_message wsjtx_port_analyze(decoder_t *decoder, int idx);
void wsjtx_port_reset(decoder_t *decoder, long utcTime, int num_samples);
void wsjtx_port_get_a91(decoder_t *decoder, uint8_t out[FTX_LDPC_K_BYTES]);
void wsjtx_port_set_ldpc_iterations(decoder_t *decoder, int iterations);
void wsjtx_port_set_ap_hints(decoder_t *decoder, const ap_hints_t *ap_hints);
bool wsjtx_port_owns_session_flow(decoder_t *decoder);
void wsjtx_port_subtract_signal(decoder_t *decoder,
                                const uint8_t *payload,
                                int sample_rate,
                                float frequency,
                                float time_sec,
                                int mode);

#ifdef __cplusplus
}
#endif

#endif
