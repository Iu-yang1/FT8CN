#ifndef FT8CN_COMMON_Q65_WAVE_SIZE_H
#define FT8CN_COMMON_Q65_WAVE_SIZE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int ftx_q65_required_samples(int tr_period_seconds,
                             int sample_rate,
                             size_t *required_samples);

/* Fortran bridge 使用固定宽度返回值，失败时返回 -1。 */
int64_t ftx_q65_required_samples_c(int tr_period_seconds, int sample_rate);

#ifdef __cplusplus
}
#endif

#endif
