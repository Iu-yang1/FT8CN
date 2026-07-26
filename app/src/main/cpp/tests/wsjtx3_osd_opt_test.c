#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

enum {
    kRows = 91,
    kColumns = 174,
    kGaussianSeeds = 128,
    kOrder1Seeds = 1000
};

int wsjtx3_osd_gaussian_eliminate(int8_t *matrix, int k, int n, int *indices);
int wsjtx3_osd_order1_search(const int8_t *c0,
                             const int8_t *g2,
                             const int8_t *hdec,
                             const int8_t *m0,
                             const int8_t *apmask,
                             const float *absrx,
                             int k,
                             int n,
                             int nt,
                             int ntheta,
                             int include_pre1,
                             int8_t *best_codeword,
                             int *best_hard_distance,
                             float *best_weighted_distance);

static uint32_t next_random(uint32_t *state) {
    *state = *state * UINT32_C(1664525) + UINT32_C(1013904223);
    return *state;
}

static void gaussian_reference(int8_t *matrix, int *indices) {
    int8_t saved_column[kRows];
    int diagonal;
    for (diagonal = 0; diagonal < kRows; ++diagonal) {
        int pivot_column;
        for (pivot_column = diagonal; pivot_column <= kRows + 19; ++pivot_column) {
            int row;
            if (matrix[diagonal + pivot_column * kRows] == 0) {
                continue;
            }
            if (pivot_column != diagonal) {
                const int saved_index = indices[diagonal];
                for (row = 0; row < kRows; ++row) {
                    saved_column[row] = matrix[row + diagonal * kRows];
                    matrix[row + diagonal * kRows] = matrix[row + pivot_column * kRows];
                    matrix[row + pivot_column * kRows] = saved_column[row];
                }
                indices[diagonal] = indices[pivot_column];
                indices[pivot_column] = saved_index;
            }
            for (row = 0; row < kRows; ++row) {
                int column;
                if (row == diagonal || matrix[row + diagonal * kRows] == 0) {
                    continue;
                }
                for (column = 0; column < kColumns; ++column) {
                    matrix[row + column * kRows] ^=
                            matrix[diagonal + column * kRows];
                }
            }
            break;
        }
    }
}

static void encode_message(const int8_t *message, const int8_t *generator, int8_t *codeword) {
    int row;
    memset(codeword, 0, kColumns);
    for (row = 0; row < kRows; ++row) {
        int column;
        if (message[row] == 0) {
            continue;
        }
        for (column = 0; column < kColumns; ++column) {
            codeword[column] ^= generator[column + row * kColumns];
        }
    }
}

static void order1_reference(const int8_t *c0,
                             const int8_t *generator,
                             const int8_t *hard,
                             const int8_t *message,
                             const int8_t *apmask,
                             const float *weights,
                             int nt,
                             int ntheta,
                             int include_pre1,
                             int8_t *best,
                             int *best_hard,
                             float *best_distance) {
    int base;
    for (base = kRows - 1; base >= 0; --base) {
        int8_t base_codeword[kColumns];
        float message_distance = 0.0f;
        int extra;
        int index;
        if (apmask[base] != 0) {
            continue;
        }
        for (index = 0; index < kColumns; ++index) {
            base_codeword[index] = c0[index] ^ generator[index + base * kColumns];
        }
        for (index = 0; index < kRows; ++index) {
            if ((message[index] ^ (index == base ? 1 : 0)) != hard[index]) {
                message_distance += weights[index];
            }
        }
        for (extra = base;
             extra >= (include_pre1 != 0 ? 0 : base);
             --extra) {
            int8_t candidate[kColumns];
            int parity_errors = extra == base ? 1 : 2;
            int hard_distance = 0;
            float distance = message_distance;
            if (extra != base && apmask[extra] != 0) {
                continue;
            }
            for (index = 0; index < kColumns; ++index) {
                candidate[index] = extra == base
                        ? base_codeword[index]
                        : base_codeword[index] ^ generator[index + extra * kColumns];
            }
            for (index = kRows; index < kRows + nt; ++index) {
                parity_errors += candidate[index] ^ hard[index];
            }
            if (parity_errors > ntheta) {
                continue;
            }
            for (index = kRows; index < kColumns; ++index) {
                if (candidate[index] != hard[index]) {
                    distance += weights[index];
                }
            }
            if (extra != base && candidate[extra] != hard[extra]) {
                distance += weights[extra];
            }
            if (distance >= *best_distance) {
                continue;
            }
            for (index = 0; index < kColumns; ++index) {
                hard_distance += candidate[index] ^ hard[index];
            }
            memcpy(best, candidate, kColumns);
            *best_hard = hard_distance;
            *best_distance = distance;
        }
    }
}

static int test_gaussian(void) {
    int trial;
    for (trial = 0; trial < kGaussianSeeds + 3; ++trial) {
        int8_t reference[kRows * kColumns];
        int8_t optimized[kRows * kColumns];
        int reference_indices[kColumns];
        int optimized_indices[kColumns];
        int index;
        uint32_t random_state = UINT32_C(0x76543210)
                                ^ (uint32_t) trial * UINT32_C(0x9e3779b9);
        if (trial == 0) {
            memset(reference, 0, sizeof(reference));
        } else if (trial == 1) {
            memset(reference, 1, sizeof(reference));
        } else if (trial == 2) {
            for (index = 0; index < kRows * kColumns; ++index) {
                reference[index] = (int8_t) ((index / kRows) & 1);
            }
        } else {
            for (index = 0; index < kRows * kColumns; ++index) {
                reference[index] = (int8_t) (next_random(&random_state) >> 31);
            }
        }
        memcpy(optimized, reference, sizeof(reference));
        for (index = 0; index < kColumns; ++index) {
            reference_indices[index] = index + 1;
            optimized_indices[index] = index + 1;
        }
        gaussian_reference(reference, reference_indices);
        if (!wsjtx3_osd_gaussian_eliminate(
                    optimized, kRows, kColumns, optimized_indices)
                || memcmp(reference, optimized, sizeof(reference)) != 0
                || memcmp(reference_indices, optimized_indices,
                          sizeof(reference_indices)) != 0) {
            return 0;
        }
    }
    return 1;
}

static int test_order1(void) {
    int trial;
    const int nt_values[] = {1, 10, 40, 83};
    for (trial = 0; trial < kOrder1Seeds; ++trial) {
        int8_t generator[kColumns * kRows] = {0};
        int8_t message[kRows];
        int8_t hard[kColumns];
        int8_t apmask[kColumns] = {0};
        int8_t c0[kColumns];
        int8_t reference[kColumns];
        int8_t optimized[kColumns];
        float weights[kColumns];
        float reference_distance = 1.0e30f;
        float optimized_distance = 1.0e30f;
        int reference_hard = kColumns;
        int optimized_hard = kColumns;
        const int nt = nt_values[trial
                                 % (int) (sizeof(nt_values) / sizeof(nt_values[0]))];
        const int ntheta = (trial / 4) % 2 == 0 ? 10 : 12;
        const int include_pre1 = (trial / 8) % 2;
        uint32_t random_state = UINT32_C(0x13579bdf)
                                ^ (uint32_t) trial * UINT32_C(0x85ebca6b);
        int index;
        for (index = 0; index < kRows; ++index) {
            int parity;
            message[index] = (int8_t) (next_random(&random_state) >> 31);
            generator[index + index * kColumns] = 1;
            for (parity = kRows; parity < kColumns; ++parity) {
                generator[parity + index * kColumns] =
                        (int8_t) (next_random(&random_state) >> 31);
            }
        }
        encode_message(message, generator, c0);
        memcpy(hard, c0, sizeof(hard));
        for (index = 0; index < 8; ++index) {
            hard[next_random(&random_state) % kColumns] ^= 1;
        }
        for (index = 0; index < kColumns; ++index) {
            if (trial == 0) {
                weights[index] = 0.0f;
            } else if (trial == 1) {
                weights[index] = index % 2 == 0 ? 0.0f : 1000000.0f;
            } else {
                weights[index] = 0.05f
                                 + (float) (next_random(&random_state) % 10000)
                                   / 10000.0f;
            }
            if (index < kRows && next_random(&random_state) % 17 == 0) {
                apmask[index] = 1;
            }
        }
        if (trial == 2) {
            memcpy(generator + kColumns, generator, kColumns);
            encode_message(message, generator, c0);
        } else if (trial == 3) {
            memset(generator, 0, sizeof(generator));
            memset(c0, 0, sizeof(c0));
        }
        memcpy(reference, c0, sizeof(reference));
        memcpy(optimized, c0, sizeof(optimized));
        order1_reference(c0, generator, hard, message, apmask, weights,
                         nt, ntheta, include_pre1,
                         reference, &reference_hard, &reference_distance);
        if (!wsjtx3_osd_order1_search(c0, generator, hard, message, apmask, weights,
                                      kRows, kColumns, nt, ntheta, include_pre1,
                                      optimized, &optimized_hard, &optimized_distance)
                || memcmp(reference, optimized, sizeof(reference)) != 0
                || reference_hard != optimized_hard
                || fabsf(reference_distance - optimized_distance) > 1.0e-4f) {
            return 0;
        }
    }
    return 1;
}

int ftx_run_wsjtx3_osd_opt_selftests(void) {
    const int gaussian_ok = test_gaussian();
    const int order1_ok = test_order1();
    printf("[%s] WSJT-X OSD packed Gaussian equivalence\n",
           gaussian_ok ? "PASS" : "FAIL");
    printf("[%s] WSJT-X OSD packed order-1 equivalence\n",
           order1_ok ? "PASS" : "FAIL");
    return gaussian_ok && order1_ok ? 0 : -1;
}
