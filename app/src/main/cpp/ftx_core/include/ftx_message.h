#ifndef FT8CN_FTX_MESSAGE_H
#define FT8CN_FTX_MESSAGE_H

#include "ftx_types.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    long long utc_time;
    int is_valid;
    int snr;
    int score;
    float time_sec;
    float freq_hz;
    char text[FTX_MAX_TEXT_LENGTH];
    char call_to[FTX_MAX_CALLSIGN_LENGTH];
    char call_de[FTX_MAX_CALLSIGN_LENGTH];
    char grid[FTX_MAX_GRID_LENGTH];
    unsigned int message_hash;
} ftx_decode_result_t;

#ifdef __cplusplus
}
#endif

#endif
