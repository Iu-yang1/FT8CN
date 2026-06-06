# FT8 ft8b hotspot profile: 2026-06-06

## 测试环境

- 设备：RMX5062 / ColorOS。
- Build：debug，JDK 17。
- Worker config：`CONSERVATIVE`，1 worker。
- Concurrency policy：`PARALLEL_PREPARE_SERIAL_NATIVE`。
- Profile：`pass=2 round=1 qso=1 sens=1 wide=1 deep=1`。
- 样本：`210703_133430.wav`。
- Tracing：进程启动前设置 `log.tag.WSJTX3Phase=DEBUG`。
- Java `nativeBatchDecodeLock`、C bridge mutex 和 `g_active_context` fallback 均保留。

## 结果

Active-context path 与 fixed callback slot path 均保持：

`bridgeRawCount=20 mergedCount=20 nativeBatchCount=20 javaPublishedCount=20`

Fixed callback slot 路径 `mismatch=0`。Tracing 关闭时仍保持 `20/20/20/20`，并且没有
`WSJTX3Phase` 或 `WSJTX3CallbackSlot` 输出。

## ft8b 聚合 breakdown

以下数据来自 active-context tracing run。单位为毫秒，`success` 表示 `ft8b()` 得到有效消息，
`new` 表示经过外层重复消息过滤后新增的消息。

| Pass | Candidates | Success / fail | New | ft8b total | Avg / max | Downsample | AP setup | LDPC | Validation | Unpack | Subtract | Other |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 167 | 16 / 151 | 15 | 3553 | 21.3 / 396.9 | 78 | 0.4 | 2735 | 0.1 | 0.8 | 589 | 148 |
| 2 | 91 | 7 / 84 | 4 | 2750 | 30.2 / 386.4 | 49 | 0.3 | 2305 | 0.1 | 0.4 | 300 | 95 |
| 3 | 48 | 1 / 47 | 1 | 2507 | 52.2 / 392.4 | 29 | 0.3 | 2383 | 0.0 | 0.1 | 42 | 51 |

Fixed-slot tracing run 给出相同趋势：

- Pass 1：LDPC 2589ms，subtract 590ms，total 3402ms。
- Pass 2：LDPC 2278ms，subtract 298ms，total 2718ms。
- Pass 3：LDPC 2338ms，subtract 43ms，total 2462ms。

## 主要热点

`decode174_91()` / LDPC 是 `ft8b()` 内部绝对主热点。随着 subtraction 后候选减少，后续 pass
的候选数量下降，但失败候选仍可能执行多个内部 LDPC/AP pass，因此候选平均耗时从约 21ms
上升到约 52ms。

Subtraction 是第二大可见阶段，主要集中在成功消息较多的前两轮。Downsample、成功路径 unpack
和 validation 的占比很小。

## 当前 tracing 边界

- `downsample` 精确覆盖两次 `ft8_downsample()`。
- `ap` 仅覆盖进入 `decode174_91()` 前、未被 early cycle 跳过的 AP setup。
- `ldpc` 精确覆盖所有实际执行的 `decode174_91()` 调用。
- `validation` 与 `unpack` 精确覆盖成功路径对应区段。
- `subtract` 精确覆盖成功后的 `subtractft8()`。
- `other` 包含同步细化、FFT/bit metrics、失败路径 validation、被 early cycle 跳过的 AP 条件检查、
  SNR 计算以及少量 tracing/accounting 开销。
- Callback 在 `ft8b()` 返回后由外层 `ft8_decode` 发出，本报告不把它计入 `ft8b`。

为了保持 vendor 控制流稳定，本轮没有重写所有失败 `cycle` 分支来细分 validation，也没有逐候选
输出 Logcat。

## Tracing overhead

Tracing 开启时 active-context core 约 9526ms，fixed-slot core 约 9300ms；tracing 关闭时 core
约 9191ms。设备温度与调频会造成自然波动，当前没有观察到结果变化或明显不可接受的 tracing
开销。Tracing 默认关闭。

## 不应进行的优化

- 不减少候选数或 pass 数。
- 不降低 sync threshold、灵敏度或默认 profile。
- 不修改 AP、LDPC 参数或 subtraction 策略来换取速度。
- 不在 callback/context 与 Q65 资源隔离完成前启用 native 真并行。

任何后续优化都必须以保持 FT8 multi sample `20/20/20/20` 为准入门槛。后续可以讨论
realtime/balanced/deep profile，但不属于本轮范围。

## 下一步建议

继续只读审查 `decode174_91()` 内部 BP 与 OSD 路径，确认主要耗时来自 BP iteration、OSD 还是
候选重试；同时小步推进 worker/slot/handle 生命周期绑定，但继续保留 Java/C 双层串行锁。
