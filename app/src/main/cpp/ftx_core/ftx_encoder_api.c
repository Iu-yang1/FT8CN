#include "ftx_encoder.h"

#include "../ft8/constants.h"
#include "../ft8/crc.h"
#include "../ft8/encode.h"
#include "../ft8/pack.h"
#include "../ft8/unpack.h"

#include <stddef.h>
#include <stdio.h>
#include <string.h>

int ftx_pack_message(const char *message, uint8_t payload[FTX_PAYLOAD_BYTES]) {
    if (message == NULL || payload == NULL) {
        return -1;
    }
    return pack77(message, payload);
}

int ftx_unpack_message(const uint8_t payload[FTX_PAYLOAD_BYTES], char *message, int message_capacity) {
    char unpacked[80];

    if (payload == NULL || message == NULL || message_capacity <= 0) {
        return -1;
    }

    memset(unpacked, 0, sizeof(unpacked));
    if (unpack77(payload, unpacked) < 0) {
        return -1;
    }

    snprintf(message, (size_t) message_capacity, "%s", unpacked);
    return 0;
}

int ftx_get_tone_count(ftx_mode_t mode) {
    return (mode == FTX_MODE_FT4) ? FT4_NN : FT8_NN;
}

int ftx_encode_tones(ftx_mode_t mode,
                     const uint8_t payload[FTX_PAYLOAD_BYTES],
                     uint8_t *tones,
                     int tone_capacity) {
    const int tone_count = ftx_get_tone_count(mode);

    if (payload == NULL || tones == NULL || tone_capacity < tone_count) {
        return -1;
    }

    memset(tones, 0, (size_t) tone_capacity);
    if (mode == FTX_MODE_FT4) {
        ft4_encode(payload, tones);
    } else {
        ft8_encode(payload, tones);
    }
    return tone_count;
}

int ftx_build_a91(const uint8_t payload[FTX_PAYLOAD_BYTES], uint8_t a91[FTX_PAYLOAD_BYTES]) {
    if (payload == NULL || a91 == NULL) {
        return -1;
    }

    ftx_add_crc(payload, a91);
    return 0;
}

int ftx_check_crc(const uint8_t a91[FTX_PAYLOAD_BYTES]) {
    uint16_t extracted;
    uint16_t calculated;

    if (a91 == NULL) {
        return 0;
    }

    extracted = ftx_extract_crc(a91);
    calculated = ftx_compute_crc(a91, 77);
    return extracted == calculated;
}

int ftx_encode_codeword(const uint8_t a91[FTX_PAYLOAD_BYTES], uint8_t codeword[FTX_CODEWORD_BYTES]) {
    if (a91 == NULL || codeword == NULL) {
        return -1;
    }

    memset(codeword, 0, FTX_CODEWORD_BYTES);
    ftx_encode_174(a91, codeword);
    return 0;
}

