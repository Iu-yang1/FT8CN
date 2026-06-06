# FT8 OSD gated 优化基准（2026-06-06）

## 变更边界

本轮原型只处理 `osd174_91()` order-1 搜索中的一次等价重复编码：

- 当 `n1 == iflag` 时，阈值判断前已经使用相同的 `me`、`g2`、`N`、`k` 生成 `ce`。
- 阈值通过后，原路径会用完全相同的输入再次调用 `mrbencode91()`。
- 实验路径只复用第一次生成的 `ce`，不改变候选、pass、round、OSD 阶数、test pattern、距离计算或成功判决。

实验由 Android Log tag `FT8OSDExperimental` 控制，默认关闭。只有进程启动前将该 tag
设置为 `DEBUG` 才会启用；设置为 `WARN` 即恢复原路径。诊断仅输出到 Logcat，不写手机存储。

## 测试环境

- 设备：RMX5062
- 系统：Android 16，API 36
- ABI：arm64-v8a
- Build：debug
- FT8 样本：`210703_133430.wav`
- FT8 profile：native diagnostic，pass=2，round=1，deep=false
- Worker config：`CONSERVATIVE`，1 worker
- Concurrency policy：`PARALLEL_PREPARE_SERIAL_NATIVE`
- Native bridge：Java `nativeBatchDecodeLock` 与 C bridge mutex 均保留
- Callback routing：`g_active_context` fallback 保留

## A/B 结果

两组结果均保持 `bridgeRawCount/mergedCount/nativeBatchCount/javaPublishedCount=20/20/20/20`。

| 指标 | 默认关闭 | 实验开启 | 差异 |
| --- | ---: | ---: | ---: |
| Core duration | 9,606 ms | 9,599 ms | -7 ms（约 -0.07%） |
| OSD total | 7,083,669 us | 7,060,666 us | -23,003 us（约 -0.32%） |
| Order-1 search | 4,930,047 us | 4,906,575 us | -23,472 us（约 -0.48%） |
| Gaussian elimination | 2,022,086 us | 2,023,576 us | +1,490 us（噪声范围） |
| Reused encode count | 0 | 376 | +376 |

按 pass 拆分：

| Pass | OFF OSD total | ON OSD total | OFF order-1 | ON order-1 | Reused encode |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 2,610,559 us | 2,600,472 us | 1,800,307 us | 1,789,607 us | 185 |
| 2 | 2,203,196 us | 2,198,909 us | 1,534,829 us | 1,530,236 us | 106 |
| 3 | 2,269,914 us | 2,261,285 us | 1,594,911 us | 1,586,732 us | 85 |

原型命中了明确的重复工作，但端到端收益处于设备调度噪声范围，没有依据默认启用。

## 回归结果

- FT8，实验开启且 tracing 关闭：`20/20/20/20`，core 9,598 ms；无 OSD/LDPC/FT8b trace。
- FT4 多信号样本，实验开启：`16/16/16/16`，core 1,483 ms；不产生 OSD trace。
- Q65A/60 样本，实验开启：`1/1/1/1`，core 295 ms、total 406 ms；不产生 OSD trace。
- 测试结束后 `FT8OSDExperimental`、`WSJTX3Phase`、`WSJTX3CallbackSlot` 均恢复为 `WARN`。

## Worker-slot 生命周期审计

使用真实 listener 路径临时开启 `WSJTX3CallbackSlot` 后，共观察到 95 条 `slotLifecycle` 日志：

- `workerId=0` 在 create/configure/process/reset/get-result 间持续复用同一 native handle。
- `callbackSlot=1` 与 `bridgeContextId=1` 在整个观察窗口保持一致。
- `mismatch=1` 数量为 0。
- 非 `none` 的 `fallbackReason` 数量为 0。

该次 listener 审计由设备前台真实监听触发，不作为样本结果数量回归。它只用于确认固定 callback slot
和长期 worker handle 的生命周期一致性。

当前仍不能启用 native 真并行：

- Java `nativeBatchDecodeLock` 仍串行保护 native batch decode。
- C `g_wsjtx3_bridge_lock` 仍串行保护 WSJT-X bridge。
- Fortran callback routing 仍保留全局 `g_active_context` fallback。
- `PARALLEL_NATIVE` 仍被拒绝并降级为 `PARALLEL_PREPARE_SERIAL_NATIVE`。

## 结论

保留该优化作为默认关闭、可逆的实验原型，不默认启用。下一步若继续优化 OSD，应优先设计收益更大的
等价结构优化，并继续以相同结果计数和灵敏度边界做 A/B 验证。真正 native 并行必须先移除全局
active-context callback 路由依赖，不能通过放开现有锁来实现。
