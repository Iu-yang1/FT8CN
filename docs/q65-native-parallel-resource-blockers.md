# Q65 native 并行资源阻塞清单

## 当前结论

Q65 RX/TX 已可在现有串行 bridge 路径工作，但当前实现不具备安全的 native 真并行条件。
在完成本清单中的隔离工作前，必须继续保留：

- Java `FT8SignalListener.nativeBatchDecodeLock`
- C backend 全局 bridge mutex
- `g_active_context` 故障回退路径
- `DecodeConcurrencyPolicy.PARALLEL_NATIVE` 拒绝/降级保护

fixed callback slot 只验证了结果回调可以写入指定 context。它没有证明 Q65 decoder、
Fortran module 状态、文件单元或 runtime 目录可以并行访问。

## 已确认的共享资源

### Bridge 全局状态

`wsjtx3_bridge.f90` 使用全局 `save` 数组保存所有 context 和 decoder：

- `g_contexts`
- `g_ft8_decoders`
- `g_ft4_decoders`
- `g_q65_decoders`
- `g_active_context`

Q65 decode 开始前设置 `g_active_context=handle`，结束后清零。即使 fixed callback slot
正常工作，错误或不完整的显式路由仍会回退到该全局 active context。

### Q65 固定 Fortran 文件单元

Q65 bridge 与 vendor decoder 使用固定文件单元和固定文件名：

- unit 17：`temp_dir/red.dat`
- unit 14：`temp_dir/avemsg.txt`
- unit 22：`data_dir/q65_decodes.txt`
- unit 24：`data_dir/tsil.3q`

两个 Q65 job 同时执行时会竞争相同 unit、文件位置和 open/close 生命周期。仅为不同
worker 分配 decoder handle 不能消除该竞争。

### Q65 module 级可变状态

vendor Q65 代码包含跨调用共享的 module 变量和 `save` 数据：

- `q65_decode.f90` 使用 `use q65`，并保存大型解析缓冲区 `c00`
- `q65.f90` 保存累计频谱 `s1a`、`ccf2`、`ccf2_avg`
- `q65.f90` 保存平均、候选、AP、历史消息和频率等 module 状态
- `q65_hist` 保存 `nhist`、`nf0`、`msg`
- `genq65.f90` 也包含 `save` 状态
- `prog_args` 中的 `temp_dir`、`data_dir` 为进程共享目录

这些状态目前不属于某个 bridge context。并发 Q65 decode、Q65 decode 与 Q65 encode，
或不同 Q65 submode/TR period 同时执行，都可能互相覆盖状态。

## Worker、slot 与 handle 生命周期验证

当前 Java scheduler 仍只有 worker `0` 进入 listener native batch 路径。每个 mode
持有独立的 `NativeDecodeWorkerContext`，并通过 JNI 查询真实 `bridgeContextId`。

生命周期调试日志覆盖：

`create -> configure -> reset -> process-begin -> results-ready -> destroy`

日志仅在 `WSJTX3CallbackSlot` 调试开关启用时输出到 Logcat，不写入手机存储。

2026-06-06 设备串行验证：

| Sample | raw/merged/native/published | lifecycle mismatch |
| --- | --- | --- |
| FT8 multi | 20/20/20/20 | 0 |
| FT4 multi | 16/16/16/16 | 0 |
| Q65A/60s | 1/1/1/1 | 0 |

该结果证明当前串行路径中 slot 路由和生命周期日志一致，不证明 native 并行安全。

## 开启 Q65 native 并行前的准入条件

1. 将 callback 正常路径完全绑定到显式 context，并移除对 `g_active_context` 的正常依赖。
2. 为每个 worker 提供独立 decoder、bridge context、输入缓冲区和结果缓冲区。
3. 将 Q65 module 可变状态改为 context-owned，或把 Q65 保留在独立串行 lane。
4. 隔离 unit 14/17/22/24 和对应临时文件，或移除移动端不需要的文件 I/O。
5. 隔离 `temp_dir/data_dir`，并验证 Q65 decode/encode 同时执行不会竞争。
6. 覆盖 create/reset/configure/process/get-result/destroy 与异常恢复的压力测试。
7. 先在保留 Java 锁时缩小 C mutex 范围，再逐层移除保护。
8. FT8、FT4、Q65 多模式并发压力测试必须保持结果完整且没有崩溃、串扰或文件冲突。

在以上条件完成前，允许 Java scheduler 并发准备、排队和丢弃 stale job，但 native
batch decode 必须继续串行。
