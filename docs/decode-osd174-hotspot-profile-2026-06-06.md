# FT8 OSD174 内部热点分析（2026-06-06）

## 测试边界

本轮只为 `osd174_91()` 增加 pass-level 聚合 tracing，没有修改：

- 候选数量、pass、round 或默认 profile
- FT8/FT4/Q65 灵敏度
- BP 最大迭代次数
- OSD `norder`、`maxosd` 或 test pattern 数量
- OSD 成功/失败判定
- Java/native 并发策略

诊断仅通过 Logcat 输出，不写手机存储。测试结束后 `WSJTX3Phase` 与
`WSJTX3CallbackSlot` 均恢复为 `WARN`。

## 测试环境

- 设备：RMX5062
- 系统：Android 16，API 36
- ABI：arm64-v8a
- Build：debug
- Worker config：`CONSERVATIVE`，1 worker
- Concurrency policy：`PARALLEL_PREPARE_SERIAL_NATIVE`
- Native bridge：Java `nativeBatchDecodeLock` 与 C bridge mutex 均保留
- Callback：`g_active_context` fallback 保留，fixed callback slot 默认关闭
- FT8 profile：native diagnostic，pass=2，round=1，deep=false
- Sample：`210703_133430.wav`
- Tracing 开关：`adb shell setprop log.tag.WSJTX3Phase DEBUG`

## OSD 阶段边界

- `allocationInitUs`：每次调用的 allocatable 数组分配
- `generatorInitUs`：首次调用生成并缓存 generator matrix
- `inputPrepareUs`：输入复制、hard decision、reliability magnitude
- `sortUs`：reliability 排序
- `matrixCopyUs`：按可靠度顺序复制 generator matrix 列
- `gaussianElimUs`：pivot 搜索、列交换与行消元
- `matrixPermuteUs`：matrix transpose 与输入向量重排
- `order0Us`：order-0 encode 与距离计算
- `order1SearchUs`：order-1 test-pattern 搜索及其距离计算
- `higherOrderSearchUs`：order 2 以上搜索
- `secondPreprocessUs`：second preprocessing rule
- `validationUs`：结果恢复顺序、CRC 与有效性判定

Gaussian elimination 的 pivot 搜索和行消元位于同一紧密循环。本轮没有为了 tracing
逐 pivot 计时，以免显著扰动热点。距离计算嵌在 order-0/order search 中，也没有强行拆分。

## 每 pass 结果

FT8 结果保持 `20/20/20/20`。

| Pass | Candidates | LDPC calls | OSD calls | BP success | OSD success | OSD total | Order-1 | Gaussian | Fail OSD total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 167 | 82 | 139 | 10 | 6 | 2,607,514 us | 1,814,507 us (69.59%) | 739,528 us (28.36%) | 2,397,327 us (91.94%) |
| 2 | 91 | 64 | 118 | 3 | 4 | 2,161,145 us | 1,518,956 us (70.28%) | 605,690 us (28.03%) | 2,075,184 us (96.02%) |
| 3 | 48 | 64 | 127 | 0 | 1 | 2,220,019 us | 1,570,422 us (70.74%) | 611,318 us (27.54%) | 2,201,501 us (99.17%) |

其余阶段单 pass 均远小于主热点：

- matrix permutation：约 20–25 ms
- generator 首次初始化：约 9 ms，仅首个 pass 出现
- allocation/init：小于 0.2 ms
- sort、matrix copy、order-0、validation：单项均小于约 8 ms
- `higherOrderSearchUs=0`、`secondPreprocessUs=0`

当前 FT8 路径传入的 `norder=2` 在 `osd174_91()` 内映射为 `nord=1`，因此本样本只进入
order-1 主搜索，不进入 higher-order 或 second preprocessing。这是现有算法行为。

## 成功与失败 OSD 耗时

| Pass | Success calls | Success total / avg / max | Fail calls | Fail total / avg / max |
| --- | ---: | --- | ---: | --- |
| 1 | 6 | 210,187 / 35,031 / 75,133 us | 133 | 2,397,327 / 18,025 / 27,089 us |
| 2 | 4 | 85,961 / 21,490 / 22,845 us | 114 | 2,075,184 / 18,203 / 37,591 us |
| 3 | 1 | 18,518 / 18,518 / 18,518 us | 126 | 2,201,501 / 17,472 / 28,055 us |

成功 OSD 单次平均耗时通常更高，但失败 OSD 调用数量远大于成功调用，因此失败路径贡献了
绝大多数 OSD 总耗时。

## OSD calls 与 LDPC calls

OSD calls 是 `osd174_91()` 的实际调用次数，不等于 candidate count 或 LDPC calls。
一次 `decode174_91()` 在 BP 未成功后可以多次调用 `osd174_91()`；BP 成功则不会进入 OSD，
OSD 成功也可能提前停止该次 LDPC call 的后续 OSD 尝试。

因此 pass 1 中存在 82 次 LDPC call、10 次 BP 成功，但有 139 次 OSD call。

## 回归与 tracing 开销

- FT8 tracing-on：`20/20/20/20`，core 9669 ms
- FT8 tracing-off：`20/20/20/20`，core 9437 ms
- FT4 tracing-on：`16/16/16/16`，无 `osdTrace`
- FT8 tracing-off：`osdTraceCount=0`、`ldpcTraceCount=0`、`ft8bTraceCount=0`

单次运行估计中，全 tracing 相对关闭约增加 2.46%；新增 OSD 细分相对此前 LDPC tracing
约增加 1.19%。该数值包含设备调度噪声，只用于确认诊断开销量级可接受。

## 结论与下一步

`osd174_91()` 的主热点不是排序、分配或 CRC，而是：

1. order-1 test-pattern search，约占 OSD 的 70%
2. Gaussian elimination，约占 OSD 的 28%

下一步应继续只读分析这两个区域的结构性成本和可复用边界，重点评估 generator matrix
消元结果是否可安全缓存、搜索循环中的重复 encode/距离计算是否存在等价复用机会。

在提出优化方案前，仍不得减少候选、降低灵敏度、减少 pass/test pattern、改变 OSD 深度，
或启用 `PARALLEL_NATIVE`。任何后续优化都必须保持 FT8 `20/20/20/20` 回归门槛。
