# Native decode phase profile：2026-06-06

## 测试环境

- 设备：RMX5062，ColorOS。
- 构建：debug，JDK 17。
- Worker config：`CONSERVATIVE`，1 worker。
- Concurrency policy：`PARALLEL_PREPARE_SERIAL_NATIVE`。
- Native bridge：保留 Java `nativeBatchDecodeLock` 与 C bridge mutex。
- Phase tracing：启动应用前执行 `adb shell setprop log.tag.WSJTX3Phase DEBUG`。
- 所有 tracing 仅输出到 Logcat，不写入手机磁盘。

## Trace 边界

当前 phase trace 覆盖：

- C backend：input copy、configure、bridge lock wait、process、merge、backend total。
- Fortran bridge：bridge enter/exit、float-to-int16 conversion、FT8 decoder call、FT4 decoder call。
- Q65：文件单元 open、decoder call、文件单元 close。
- Callback：每次 decoder call 的 callback append 总数。

Tracing 默认关闭。Official core 通过弱 hook 与 Android Logcat binding 解耦，独立 link validation 和非 Android host 不依赖 Logcat。

## FT8 multi sample

样本：`210703_133430.wav`，profile：`pass=2 round=1`。

| Run | Counts | Listener total | FT8 decoder call | Backend merge |
| --- | --- | ---: | ---: | ---: |
| 1 | 20/20/20/20 | 7174ms | 7155ms | 0.38ms |
| 2 | 20/20/20/20 | 6959ms | 6945ms | 0.41ms |
| 3 | 20/20/20/20 | 6877ms | 6866ms | 0.33ms |

三轮平均 listener total 约 `7003ms`，FT8 decoder call 平均约 `6989ms`。Input conversion 约 `2ms`，bridge lock wait 为 `0ms`，callback count 为 `20`。

结论：FT8 的主要瓶颈已经收敛到官方 `ft8_decoder%decode()` 单次调用内部。C backend、bridge conversion、result merge、JNI 与 Java 发布均不是主要耗时来源。下一轮若继续细分，应在 vendor FT8 decoder 内部围绕 candidate/sync search、LDPC decode、AP 与 subtraction 做只读 tracing。

## FT4 multi sample

样本：`000000_000002.wav`，profile：`pass=2 round=1`。

- Counts：`16/16/16/16`
- Listener total：`1374ms`
- FT4 decoder call：`1367ms`
- Backend merge：`0.16ms`
- Bridge lock wait：`0ms`
- Callback count：`16`

补充验证：同一样本使用 `pass=1` 只得到 `13/13/13/13`。因此当前不能为了性能降低 FT4 默认 pass 数。

## Q65A/60 real sample

样本：`210106_1621.wav`，profile：`pass=1 round=1`。

- Counts：`1/1/1/1`
- 解码文本：`W7GJ W1VD FN31`
- Listener total：`244ms`
- Input conversion：`9.19ms`
- Q65 files open：`2.35ms`
- Q65 decoder call：`230.94ms`
- Q65 files close：`0.004ms`
- Backend merge：`0.026ms`

## Q65F/60 generated sample

样本：`Q65F_60s_F_60s_12000.wav`，profile：`pass=1 round=1`。

- Counts：`1/1/1/1`
- Listener total：`9996ms`
- Input conversion：`11.91ms`
- Q65 files open：`0.079ms`
- Q65 decoder call：`9982.49ms`
- Q65 files close：`0.012ms`
- Backend merge：`0.048ms`
- Bridge lock wait：`0.001ms`

结论：Q65F/60 的约 10 秒耗时几乎全部位于 `q65_decoder%decode()` 内部。固定文件单元当前仍是并发安全阻塞点，但不是本次单线程性能瓶颈。

## Q65A/300 regression

- Expected/actual samples：`3600000`
- Counts：`1/1/1/1`
- Input conversion：`61.24ms`
- Q65 decoder call：`1324.54ms`
- Backend total：`1386.72ms`

## Tracing overhead

Trace 开启的 FT8 三轮均落在既有约 7.2 秒波动范围内。Trace 关闭后单轮 FT8 为 `6767ms`，保持 `20/20/20/20`，且 Logcat 中没有 `WSJTX3Phase` 输出。

由于设备调频和温度会造成自然波动，目前没有观察到可辨识的 tracing 额外开销。关闭状态只在进程首次调用时读取一次 `log.tag.WSJTX3Phase`，之后使用缓存结果。

## 保持不变的策略

- 不增加 worker 数。
- 不启用 `PARALLEL_NATIVE`。
- 不移除 Java 或 C bridge 锁。
- 不降低 FT8/FT4/Q65 profile。
- Q65 继续 full-slot only，不运行 early 或 deep supplement。

## 下一步建议

优先为 FT8 vendor decoder call 增加更细的只读 phase tracing。Q65F 同样需要进入 `q65_decoder%decode()` 内部追踪，但在 callback context 迁移和 Q65 文件资源隔离前，不应尝试真并行。
