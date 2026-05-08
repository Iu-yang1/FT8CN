#include "ftx_selftest.h"

#include "ftx_encoder.h"

#include "../ft8/constants.h"

#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static void append_line(char *report, int report_capacity, int *used, const char *format, ...) {
    va_list args;
    int written;

    if (report == NULL || report_capacity <= 0 || used == NULL || *used >= report_capacity) {
        return;
    }

    va_start(args, format);
    written = vsnprintf(report + *used, (size_t) (report_capacity - *used), format, args);
    va_end(args);

    if (written < 0) {
        return;
    }
    if (written >= report_capacity - *used) {
        *used = report_capacity - 1;
        report[*used] = '\0';
        return;
    }
    *used += written;
}

static int expect_true(int condition,
                       const char *label,
                       char *report,
                       int report_capacity,
                       int *used) {
    append_line(report, report_capacity, used, "[%s] %s\n", condition ? "PASS" : "FAIL", label);
    return condition ? 1 : 0;
}

static int check_ft8_costas(const uint8_t *tones) {
    int index;

    for (index = 0; index < FT8_LENGTH_SYNC; ++index) {
        if (tones[index] != kFT8CostasPattern[index]) {
            return 0;
        }
        if (tones[FT8_SYNC_OFFSET + index] != kFT8CostasPattern[index]) {
            return 0;
        }
        if (tones[2 * FT8_SYNC_OFFSET + index] != kFT8CostasPattern[index]) {
            return 0;
        }
    }
    return 1;
}

static int check_ft4_sync(const uint8_t *tones) {
    int index;

    if (tones[0] != 0 || tones[FT4_NN - 1] != 0) {
        return 0;
    }

    for (index = 0; index < FT4_LENGTH_SYNC; ++index) {
        if (tones[1 + index] != kFT4CostasPattern[0][index]) {
            return 0;
        }
        if (tones[34 + index] != kFT4CostasPattern[1][index]) {
            return 0;
        }
        if (tones[67 + index] != kFT4CostasPattern[2][index]) {
            return 0;
        }
        if (tones[100 + index] != kFT4CostasPattern[3][index]) {
            return 0;
        }
    }
    return 1;
}

int ftx_core_run_selftests(char *report, int report_capacity) {
    static const char *kMessages[] = {
            "CQ BG5JSU OL87",
            "BG5JSU JA6RJK R-10",
            "JA6RJK BG5JSU RR73",
            "CQ TEST BG5JSU OL87"
    };
    uint8_t payload[FTX_PAYLOAD_BYTES];
    uint8_t a91[FTX_PAYLOAD_BYTES];
    uint8_t codeword[FTX_CODEWORD_BYTES];
    uint8_t ft8_tones[FTX_FT8_TONE_COUNT];
    uint8_t ft4_tones[FTX_FT4_TONE_COUNT];
    char unpacked[80];
    int used = 0;
    int passed = 0;
    int total = 0;
    int index;

    if (report != NULL && report_capacity > 0) {
        report[0] = '\0';
    }

    for (index = 0; index < (int) (sizeof(kMessages) / sizeof(kMessages[0])); ++index) {
        memset(payload, 0, sizeof(payload));
        memset(unpacked, 0, sizeof(unpacked));

        total++;
        if (ftx_pack_message(kMessages[index], payload) >= 0 &&
            ftx_unpack_message(payload, unpacked, (int) sizeof(unpacked)) == 0 &&
            strcmp(kMessages[index], unpacked) == 0) {
            passed += expect_true(1, kMessages[index], report, report_capacity, &used);
        } else {
            passed += expect_true(0, kMessages[index], report, report_capacity, &used);
        }
    }

    total++;
    passed += expect_true(ftx_encode_tones(FTX_MODE_FT8, payload, ft8_tones, FTX_FT8_TONE_COUNT) == FTX_FT8_TONE_COUNT,
                          "FT8 tone count",
                          report,
                          report_capacity,
                          &used);

    total++;
    passed += expect_true(check_ft8_costas(ft8_tones),
                          "FT8 Costas sync",
                          report,
                          report_capacity,
                          &used);

    total++;
    passed += expect_true(ftx_encode_tones(FTX_MODE_FT4, payload, ft4_tones, FTX_FT4_TONE_COUNT) == FTX_FT4_TONE_COUNT,
                          "FT4 tone count",
                          report,
                          report_capacity,
                          &used);

    total++;
    passed += expect_true(check_ft4_sync(ft4_tones),
                          "FT4 sync structure",
                          report,
                          report_capacity,
                          &used);

    total++;
    passed += expect_true(ftx_build_a91(payload, a91) == 0 && ftx_check_crc(a91),
                          "CRC valid payload",
                          report,
                          report_capacity,
                          &used);

    a91[0] ^= 0x80u;
    total++;
    passed += expect_true(!ftx_check_crc(a91),
                          "CRC invalid payload",
                          report,
                          report_capacity,
                          &used);
    a91[0] ^= 0x80u;

    total++;
    passed += expect_true(ftx_encode_codeword(a91, codeword) == 0,
                          "LDPC codeword encode",
                          report,
                          report_capacity,
                          &used);

    append_line(report,
                report_capacity,
                &used,
                "SUMMARY %d/%d passed\n",
                passed,
                total);
    append_line(report,
                report_capacity,
                &used,
                "TODO synthetic waveform decode test not implemented yet.\n");

    return (passed == total) ? 0 : -1;
}
