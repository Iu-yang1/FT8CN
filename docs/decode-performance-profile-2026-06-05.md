# Decode 性能剖析：2026-06-05

## 测试环境

- 设备：RMX5062，ColorOS。
- 构建：debug，JDK 17。
- 调度：`CONSERVATIVE`，1 worker。
- 并发策略：`PARALLEL_PREPARE_SERIAL_NATIVE`。
- Profile：FT8/FT4 `pass=2 round=1`；Q65 `pass=1 round=1`；均关闭 wideband/deep。
- 诊断任务保持应用前台，避免 ColorOS 后台冻结。

## FT8 multi sample

样本：`210703_133430.wav`

| Run | Counts | Total | Queue | Decoder process | Java dedupe/callback |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | 20/20/20/20 | 7202ms | 0ms | 7192ms | 9ms |
| 2 | 20/20/20/20 | 7353ms | 0ms | 7343ms | 10ms |
| 3 | 20/20/20/20 | 7296ms | 0ms | 7285ms | 11ms |

平均总耗时约 `7284ms`，平均 core/JNI batch 约 `7273ms`。`nativeLockWaitMs`、input copy、result getter 和 result object build 均接近 `0ms`。

结论：FT8 约 7.27s 的主要瓶颈明确位于 WSJT-X/Fortran core。Java scheduler、JNI 对象构造、去重和发布不是当前主瓶颈。默认 profile 不应在缺少结果数回归证据时贸然降级。

## FT4 multi sample

样本：`000000_000002.wav`

- Counts：`16/16/16/16`
- Total：`1271ms`
- Decoder process/core：`1262ms`
- Queue：`1ms`
- Java dedupe/callback：`8ms`

结论：与 FT8 一样，耗时主要位于 native core；当前结果链路无丢失。

## Q65

| Case | Counts | Total | Decoder process/core | Queue/lock wait | Deadline |
| --- | --- | ---: | ---: | ---: | --- |
| Real Q65A/60s | 1/1/1/1 | 190ms | 189ms | 0ms / 0ms | met |
| Q65B/60s | 1/1/1/1 | 234ms | 234ms | 1ms / 0ms | met |
| Q65C/60s | 1/1/1/1 | 244ms | 244ms | 1ms / 0ms | met |
| Q65D/60s | 1/1/1/1 | 628ms | 627ms | 0ms / 0ms | met |
| Q65E/60s | 1/1/1/1 | 1687ms | 1684ms | 1ms / 0ms | met |
| Q65F/60s | 1/1/1/1 | 11064ms | 11062ms | 1ms / 0ms | start deadline met |
| Q65A/120s | 1/1/1/1 | 546ms | 546ms | 0ms / 0ms | met |
| Q65A/300s | 1/1/1/1 | 1311ms | 1310ms | 1ms / 0ms | met |

真实 Q65A/60s 解出 `W7GJ W1VD FN31`。Q65A/300s 使用 `3600000` samples。

结论：Q65F/60s 的最坏耗时同样几乎全部位于 native core。当前只应记录并通过 scheduler 保护 live，不应贸然改 Q65 算法。Q65 保持 full-slot only，不运行 early 或 deep supplement。

## Scheduler 与 deadline 决策

- 当前 isolated benchmark 的 queue 和 native lock wait 接近零，没有证据支持扩大 worker 数或启用 native 并行。
- `LIVE_FULL` 始终接收；deep 在 active/pending live 或 Q65 full 时丢弃；diagnostic 在 scheduler 忙时丢弃。
- Native job 无法安全抢占；已经运行的 diagnostic 不能被 live 中断，因此重型 Q65 diagnostics 必须显式运行且保持应用前台。
- 可丢弃任务的 deadline 表示“最晚启动时间”；live full 的 deadline 表示“完成安全窗口”。这避免立即启动但 core 较慢的 diagnostic 被误报为排队过期。

## 下一步

优先在 native core 内增加更细的 FT8 phase/pass 与 Q65 submode tracing，再决定优化算法还是拆分 per-worker context。当前不应移除 bridge mutex 或启用 `PARALLEL_NATIVE`。
