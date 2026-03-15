#ifndef _INCLUDE_DECODE_H_
#define _INCLUDE_DECODE_H_

#include <stdbool.h>
#include <stdint.h>

#include "constants.h"
#include "../common/debug.h"
#include "../fft/kiss_fft.h"

typedef struct
{
    int max_blocks;
    int num_blocks;
    int num_bins;
    int time_osr;
    int freq_osr;
    uint8_t *mag;
    int block_stride;
    ftx_protocol_t protocol;
    float *mag2;
} waterfall_t;

typedef struct
{
    int16_t score;
    int16_t time_offset;
    int16_t freq_offset;
    uint8_t time_sub;
    uint8_t freq_sub;
    int snr;
} candidate_t;

typedef struct
{
    uint32_t hash22;
    uint32_t hash12;
    uint32_t hash10;
} hashCode;

typedef struct
{
    uint8_t i3;
    uint8_t n3;

    // Composite messages can exceed the old 48-byte buffer with long callsigns.
    char text[80];
    uint16_t hash;

    char call_to[14];
    char call_de[14];
    char dx_call_to2[14];
    char extra[19];

    char maidenGrid[5];
    int report;

    hashCode call_to_hash;
    hashCode call_de_hash;

    uint8_t a91[FTX_LDPC_K_BYTES];
} message_t;

typedef struct
{
    int ldpc_errors;
    uint16_t crc_extracted;
    uint16_t crc_calculated;
    int unpack_status;
} decode_status_t;

int ft8_find_sync(const waterfall_t *power, int num_candidates, candidate_t heap[], int min_score);
bool ft8_decode(waterfall_t *power, candidate_t *cand, message_t *message, int max_iterations,
                decode_status_t *status);

#endif // _INCLUDE_DECODE_H_
