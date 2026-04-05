#ifndef FT8CN_EXPERIMENTAL_MODEM_H
#define FT8CN_EXPERIMENTAL_MODEM_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct exp_symbol_result_t {
    float energies[4];
    float noise_floor;
    int best_index;
} exp_symbol_result_t;

int exp_analyze_first_symbol(
        const float *samples,
        int sample_count,
        int sample_rate,
        int symbol_samples,
        const int *tone_hz,
        int tone_count,
        exp_symbol_result_t *out_result
);

#ifdef __cplusplus
}
#endif

#endif // FT8CN_EXPERIMENTAL_MODEM_H

