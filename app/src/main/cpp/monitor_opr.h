
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <math.h>
#include <stdbool.h>
#include "ft8/decode.h"
#include "fft/kiss_fftr.h"
#include "common/debug.h"

#ifdef LOG_LEVEL
#undef LOG_LEVEL
#endif
#define LOG_LEVEL LOG_INFO



/// FT4/FT8 监视器配置项
typedef struct {
    float f_min;             ///< 分析的最低频率边界
    float f_max;             ///< 分析的最高频率边界
    int sample_rate;         ///< 采样率（Hz）
    int time_osr;            ///< 时间方向细分数（过采样率）
    int freq_osr;            ///< 频率方向细分数（过采样率）
    ftx_protocol_t protocol; ///< 协议类型：FT4 或 FT8
} monitor_config_t;

/// FT4/FT8 监视器对象：负责输入音频的 DSP 处理并生成瀑布图对象
typedef struct {
    float symbol_period; ///< FT4/FT8 符号周期（秒）
    int block_size;      ///< 每个符号（FSK）的样本数
    int subblock_size;   ///< 分析窗口每次移动的样本数
    int nfft;            ///< FFT 点数
    float fft_norm;      ///< FFT 归一化因子
    float *window;       ///< STFT 分析窗口函数（nfft 样本）
    float *last_frame;   ///< 当前 STFT 分析帧（nfft 样本）
    waterfall_t wf;      ///< 瀑布图对象
    float max_mag;       ///< 最大检测幅值（调试统计）

    // KISS FFT 运行时辅助变量
    void *fft_work;        ///< Kiss FFT 所需工作区
    kiss_fftr_cfg fft_cfg; ///< Kiss FFT 配置对象
} monitor_t;


void monitor_init(monitor_t *me, const monitor_config_t *cfg);
void monitor_free(monitor_t* me);
void monitor_process(monitor_t *me, const float *frame);
void monitor_reset(monitor_t *me);

