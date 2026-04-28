#ifndef FT8CN_FT8_DECODER_H
#define FT8CN_FT8_DECODER_H

#include "ft8/constants.h"
#include "ft8/decode.h"
#include "monitor_opr.h"

#include <time.h>

typedef enum {
    DECODER_BACKEND_LEGACY = 0,
    DECODER_BACKEND_WSJTX_PORT = 1
} decoder_backend_t;

typedef struct {
    int decode_pass_count;
    int multi_decode_round_count;
    int qso_freq_sensitivity;
    int decode_sensitivity;
    bool enable_early_decode;
    bool enable_wideband_dx_search;
} wsjtx_decoder_options_t;

enum {
    kMax_candidates = 220,
    kMax_decoded_messages = 100,
    kLDPC_iterations = 20,
    deep_kLDPC_iterations = 200,
    fast_kLDPC_iterations = 20
};

typedef struct {
    long long utcTime;
    int num_samples;
    int num_candidates;
    int num_decoded;
    decoder_backend_t backend;
    void *backend_state;

    message_t decoded[kMax_decoded_messages];
    float decoded_freq_hz[kMax_decoded_messages];
    message_t *decoded_hashtable[kMax_decoded_messages];
    candidate_t candidate_list[kMax_candidates];

    monitor_t mon;
    monitor_config_t mon_cfg;
    uint8_t a91[FTX_LDPC_K_BYTES];
    int kLDPC_iterations;
    ap_hints_t ap_hints;
} decoder_t;

typedef struct {
    int64_t utcTime;
    bool isValid;
    int snr;
    candidate_t candidate;
    float time_sec;
    float freq_hz;
    message_t message;
    decode_status_t status;
} ft8_message;

static const int kFreq_osr = 2;
static const int kTime_osr = 2;

void signalToFFT(decoder_t *decoder, float signal[], int sample_rate);
void *init_decoder(int64_t utcTime, int sample_rate, int num_samples, bool is_ft8);
void delete_decoder(decoder_t *decoder);
void decoder_monitor_press(float signal[], decoder_t *decoder);
void decoder_monitor_press_samples(float signal[], decoder_t *decoder, int sample_count);
int decoder_ft8_find_sync(decoder_t *decoder);
ft8_message decoder_ft8_analysis(int idx, decoder_t *decoder);
void decoder_ft8_reset(decoder_t *decoder, long utcTime, int num_samples);
void decoder_get_a91(decoder_t *decoder, uint8_t out[FTX_LDPC_K_BYTES]);
void decoder_set_ldpc_iterations(decoder_t *decoder, bool is_deep);
void decoder_set_ap_hints(decoder_t *decoder, const ap_hints_t *ap_hints);
void decoder_set_wsjtx_options(decoder_t *decoder, const wsjtx_decoder_options_t *options);
bool decoder_owns_session_flow(decoder_t *decoder);
void decoder_subtract_signal(decoder_t *decoder,
                             const uint8_t *payload,
                             int sample_rate,
                             float frequency,
                             float time_sec,
                             int mode);
void recode(int a174[], int a79[]);

#endif
