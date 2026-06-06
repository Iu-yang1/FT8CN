# Vendor decoder phase profile: 2026-06-06

## 环境与开关

- 设备：RMX5062 / ColorOS，debug build，JDK 17。
- Worker：`CONSERVATIVE`，1 worker。
- 并发策略：`PARALLEL_PREPARE_SERIAL_NATIVE`。
- Java `nativeBatchDecodeLock` 与 C bridge mutex 均保留。
- Vendor tracing：进程启动前执行 `adb shell setprop log.tag.WSJTX3Phase DEBUG`。
- Fixed callback slot：进程启动前执行
  `adb shell setprop log.tag.WSJTX3CallbackSlot DEBUG`；默认关闭。
- 所有诊断只输出 Logcat，没有新增手机端持久化日志。

## FT8 vendor phase

样本 `210703_133430.wav`，profile `pass=2 round=1`，结果保持 `20/20/20/20`。

| Vendor pass | Candidates | New decodes | Sync search | Candidate decode | Pass total |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 167 | 15 | 205ms | 3752ms | 3958ms |
| 2 | 91 | 4 | 198ms | 2729ms | 2927ms |
| 3 | 48 | 1 | 199ms | 2476ms | 2675ms |

首个 decoder call 约 9562ms；后续 follow-up call 约 184ms。FT8 的主瓶颈不是 sync search，
而是对候选逐个执行 `ft8b()` 的聚合耗时。`ft8b()` 内仍包含 downsample、AP pass、
`decode174_91`、message unpack 和 subtraction；若继续优化，应先对这些子阶段做只读 tracing，
不能通过减少候选或结果数换取速度。

## FT4 regression

样本 `000000_000002.wav`，profile `pass=2 round=1`：

- Active-context path：`16/16/16/16`，core 1495ms。
- Fixed-slot path：`16/16/16/16`，core 1493ms。

本轮未修改 FT4 vendor 算法或 profile。

## Q65 vendor phase

真实 Q65A/60 样本 `210106_1621.wav`：

- 结果 `1/1/1/1`，解出 `W7GJ W1VD FN31`。
- `q65_dec0` 约 284ms，成功后直接返回。

生成 Q65F/60 样本 `Q65F_60s_F_60s_12000.wav`：

| Phase | Duration |
| --- | ---: |
| `q65_dec0` | 6370ms |
| `ana64` | 142ms |
| first `q65_loops` | 3168ms |
| second `q65_loops` | 279ms |
| total core | about 9990ms |

Q65F 的主要瓶颈已经明确为初始 `q65_dec0` 与首个 `q65_loops`。本轮不修改 Q65 算法、profile、
full-slot-only 行为或 deep/early 策略。

Q65A/300 先前回归保持 `expectedSamples=3600000` 与 `1/1/1/1`。本轮额外设备触发受后台冻结干扰，
没有生成新的完整结束日志，因此不把它计为本轮新验证。

## Fixed callback slot comparison

| Sample | Old path | Fixed-slot path | Mismatch |
| --- | ---: | ---: | ---: |
| FT8 multi | 20/20/20/20, 9422ms | 20/20/20/20, 9340ms | 0 |
| FT4 multi | 16/16/16/16, 1495ms | 16/16/16/16, 1493ms | 0 |
| Q65A/60 | 1/1/1/1, 288ms | 1/1/1/1, 293ms | 0 |
| Q65F/60 | 1/1/1/1, 9996ms | 1/1/1/1, 9999ms | 0 |

当前没有观察到 fixed-slot path 的明显开销或结果变化。但它只在串行锁下验证，不能作为启用
`PARALLEL_NATIVE` 的依据。

## 下一步

优先继续细分 FT8 `ft8b()` 内的 LDPC/AP/subtraction/unpack 热点；callback context 方向则应先完成
per-worker handle/slot 生命周期与 Q65 文件资源隔离，再讨论缩小锁粒度。默认 profile、结果门槛和
串行保护保持不变。
