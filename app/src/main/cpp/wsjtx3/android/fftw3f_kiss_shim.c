#include "../vendor/wsjtx-3.0.0/lib/wsprd/fftw3.h"
#include "../../fft/kiss_fft.h"
#include "../../fft/kiss_fftr.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef enum {
    SHIM_PLAN_C2C_FORWARD = 0,
    SHIM_PLAN_C2C_BACKWARD = 1,
    SHIM_PLAN_R2C = 2,
    SHIM_PLAN_C2R = 3
} shim_plan_kind_t;

typedef struct {
    int nfft;
    shim_plan_kind_t kind;
    void *base_in;
    void *base_out;
    kiss_fft_cfg c2c_cfg;
    kiss_fftr_cfg real_cfg;
    kiss_fft_cpx *complex_in;
    kiss_fft_cpx *complex_out;
    kiss_fft_scalar *real_buffer;
} sfftw_plan_shim_t;

static sfftw_plan_shim_t *shim_from_handle(const intptr_t *handle) {
    if (handle == NULL || *handle == 0) {
        return NULL;
    }
    return (sfftw_plan_shim_t *) (intptr_t) (*handle);
}

static void free_plan(sfftw_plan_shim_t *plan) {
    if (plan == NULL) {
        return;
    }
    free(plan->c2c_cfg);
    free(plan->real_cfg);
    free(plan->complex_in);
    free(plan->complex_out);
    free(plan->real_buffer);
    free(plan);
}

static sfftw_plan_shim_t *allocate_plan(int nfft,
                                        shim_plan_kind_t kind,
                                        void *base_in,
                                        void *base_out) {
    sfftw_plan_shim_t *plan = (sfftw_plan_shim_t *) calloc(1, sizeof(*plan));
    if (plan == NULL) {
        return NULL;
    }

    plan->nfft = nfft;
    plan->kind = kind;
    plan->base_in = base_in;
    plan->base_out = base_out;

    switch (kind) {
        case SHIM_PLAN_C2C_FORWARD:
        case SHIM_PLAN_C2C_BACKWARD:
            plan->c2c_cfg = kiss_fft_alloc(nfft,
                                           kind == SHIM_PLAN_C2C_BACKWARD,
                                           NULL,
                                           NULL);
            plan->complex_in = (kiss_fft_cpx *) calloc((size_t) nfft, sizeof(kiss_fft_cpx));
            plan->complex_out = (kiss_fft_cpx *) calloc((size_t) nfft, sizeof(kiss_fft_cpx));
            if (plan->c2c_cfg == NULL || plan->complex_in == NULL || plan->complex_out == NULL) {
                free_plan(plan);
                return NULL;
            }
            break;

        case SHIM_PLAN_R2C:
            plan->real_cfg = kiss_fftr_alloc(nfft, 0, NULL, NULL);
            plan->real_buffer = (kiss_fft_scalar *) calloc((size_t) nfft, sizeof(kiss_fft_scalar));
            plan->complex_out = (kiss_fft_cpx *) calloc((size_t) (nfft / 2 + 1), sizeof(kiss_fft_cpx));
            if (plan->real_cfg == NULL || plan->real_buffer == NULL || plan->complex_out == NULL) {
                free_plan(plan);
                return NULL;
            }
            break;

        case SHIM_PLAN_C2R:
            plan->real_cfg = kiss_fftr_alloc(nfft, 1, NULL, NULL);
            plan->real_buffer = (kiss_fft_scalar *) calloc((size_t) nfft, sizeof(kiss_fft_scalar));
            plan->complex_out = (kiss_fft_cpx *) calloc((size_t) (nfft / 2 + 1), sizeof(kiss_fft_cpx));
            if (plan->real_cfg == NULL || plan->real_buffer == NULL || plan->complex_out == NULL) {
                free_plan(plan);
                return NULL;
            }
            break;
    }

    return plan;
}

void sfftw_plan_dft_1d_(intptr_t *plan_handle,
                        const int *nfft,
                        void *in,
                        void *out,
                        const int *sign,
                        const int *flags) {
    const shim_plan_kind_t kind =
            (sign != NULL && *sign >= 0) ? SHIM_PLAN_C2C_BACKWARD : SHIM_PLAN_C2C_FORWARD;
    (void) flags;

    if (plan_handle == NULL || nfft == NULL || *nfft <= 0) {
        return;
    }

    sfftw_plan_shim_t *plan = allocate_plan(*nfft, kind, in, out);
    if (plan == NULL) {
        *plan_handle = 0;
        return;
    }
    *plan_handle = (intptr_t) plan;
}

void sfftw_plan_dft_r2c_1d_(intptr_t *plan_handle,
                            const int *nfft,
                            void *in,
                            void *out,
                            const int *flags) {
    (void) flags;
    if (plan_handle == NULL || nfft == NULL || *nfft <= 0) {
        return;
    }

    sfftw_plan_shim_t *plan = allocate_plan(*nfft, SHIM_PLAN_R2C, in, out);
    if (plan == NULL) {
        *plan_handle = 0;
        return;
    }
    *plan_handle = (intptr_t) plan;
}

void sfftw_plan_dft_c2r_1d_(intptr_t *plan_handle,
                            const int *nfft,
                            void *in,
                            void *out,
                            const int *flags) {
    (void) flags;
    if (plan_handle == NULL || nfft == NULL || *nfft <= 0) {
        return;
    }

    sfftw_plan_shim_t *plan = allocate_plan(*nfft, SHIM_PLAN_C2R, in, out);
    if (plan == NULL) {
        *plan_handle = 0;
        return;
    }
    *plan_handle = (intptr_t) plan;
}

void sfftw_execute_(intptr_t *plan_handle) {
    sfftw_plan_shim_t *plan = shim_from_handle(plan_handle);
    if (plan == NULL) {
        return;
    }

    switch (plan->kind) {
        case SHIM_PLAN_C2C_FORWARD:
        case SHIM_PLAN_C2C_BACKWARD:
            memcpy(plan->complex_in,
                   plan->base_in,
                   (size_t) plan->nfft * sizeof(kiss_fft_cpx));
            kiss_fft(plan->c2c_cfg, plan->complex_in, plan->complex_out);
            memcpy(plan->base_out,
                   plan->complex_out,
                   (size_t) plan->nfft * sizeof(kiss_fft_cpx));
            break;

        case SHIM_PLAN_R2C:
            memcpy(plan->real_buffer,
                   plan->base_in,
                   (size_t) plan->nfft * sizeof(kiss_fft_scalar));
            kiss_fftr(plan->real_cfg, plan->real_buffer, plan->complex_out);
            memcpy(plan->base_out,
                   plan->complex_out,
                   (size_t) (plan->nfft / 2 + 1) * sizeof(kiss_fft_cpx));
            break;

        case SHIM_PLAN_C2R:
            memcpy(plan->complex_out,
                   plan->base_in,
                   (size_t) (plan->nfft / 2 + 1) * sizeof(kiss_fft_cpx));
            kiss_fftri(plan->real_cfg, plan->complex_out, plan->real_buffer);
            memcpy(plan->base_out,
                   plan->real_buffer,
                   (size_t) plan->nfft * sizeof(kiss_fft_scalar));
            break;
    }
}

void sfftw_destroy_plan_(intptr_t *plan_handle) {
    sfftw_plan_shim_t *plan = shim_from_handle(plan_handle);
    if (plan == NULL) {
        return;
    }
    free_plan(plan);
    *plan_handle = 0;
}
