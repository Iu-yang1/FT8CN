# Native decode 并行化边界

## 当前结论

Java `DecodeScheduler` 可以并发准备和按优先级排队，但 native batch decode 必须继续串行。
在以下条件全部完成前，不得移除 `FT8SignalListener.nativeBatchDecodeLock`，也不得启用
`DecodeConcurrencyPolicy.PARALLEL_NATIVE`。

## 当前共享状态

- `wsjtx3_bridge.f90` 的 `g_contexts`、`g_ft8_decoders`、`g_ft4_decoders`、
  `g_q65_decoders` 是全局 `save` 数组。
- FT8、FT4、Q65 callback 都通过单一 `g_active_context` 决定结果写入目标。
- `append_active_result()` 根据 `g_active_context` 修改对应 context 的 result buffer。
- Q65 pipeline 使用固定 Fortran 文件单元和共享 runtime temp 目录。
- `wsjtx3_backend.c` 使用全局 bridge mutex 串行 create/reset/options/process/get-result/destroy。
- Java 侧 persistent decoder handle 按 mode 复用，不是按 worker 独占。

因此，即使不同 handle 指向不同 `g_contexts` 槽位，同时执行 callback 仍会覆盖
`g_active_context`，造成结果串扰或写入错误 context。

## 目标模型

每个 decode worker 应持有独立的 `NativeDecodeWorkerContext`：

- `workerId`
- `mode`
- `decoderHandle`
- `bridgeContextId`
- `expectedSamples`
- 独立输入工作缓冲区
- 独立 native result buffer
- 当前 job/stage/UTC tracing 信息

live、deep、diagnostic 不共享 decoder handle 和 result buffer。Q65 使用独立 context，并拥有
不会与其他 Q65 job 冲突的临时资源。

## callback 路由迁移

优先方案是让 callback 从 decoder 实例或显式参数得到 context id，再写入对应 result buffer。
如果 WSJT-X Fortran callback 接口暂时无法携带 context，可用 thread-local active context 作为
过渡，但必须先验证 Android Fortran runtime 的 TLS 行为，并保证同一线程中的嵌套调用安全。

仅把 `g_active_context` 改成 thread-local 仍不足以完成并行，还必须处理：

- decoder 对象内部可变状态是否线程独占；
- Q65 固定文件单元和临时文件；
- runtime directory 与共享 Fortran module 状态；
- create/destroy/options/get-result 与 process 的生命周期竞争；
- result buffer 容量、reset 与读取时序。

## 移除全局锁前的准入条件

1. callback 能可靠绑定到显式 context 或验证过的 thread-local context。
2. 每个 native worker 使用独立 decoder、bridge context、输入和结果缓冲区。
3. Q65 临时资源改为 context 独占，或 Q65 保持单独串行锁。
4. create/reset/process/get-result/destroy 的并发生命周期有测试覆盖。
5. FT8 多信号样本保持 `14/14/14/14`，FT4 保持 `13/13/13/13`。
6. FT8、FT4、Q65 并发压力测试没有结果串扰、崩溃或 Fortran I/O 冲突。
7. 完成以上验证后，先移除 Java 外层锁但保留 native mode/context 级锁，再逐步放开。

## 当前允许的并发

- 允许 Java worker 并发准备、重采样、排队和丢弃 stale job。
- 允许 scheduler 使用多个 worker 改善优先级响应。
- native bridge 入口仍由 Java `nativeBatchDecodeLock` 和 C backend bridge mutex 双重串行保护。

