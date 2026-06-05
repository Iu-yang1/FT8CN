# Explicit callback context 迁移设计

## 当前写入路径

当前 FT8、FT4、Q65 的 callback 结果写入路径为：

`backend process -> wsjtx3_bridge_process_float -> decoder%decode -> mode callback -> append_active_result -> g_active_context -> g_contexts(handle)%results`

相关共享状态：

- `g_active_context`：单一全局 callback 路由槽位。
- `g_contexts`：全局 context/result 数组。
- `g_ft8_decoders`、`g_ft4_decoders`、`g_q65_decoders`：按 handle 索引的全局 decoder 数组。
- Q65 固定文件单元 14/17 与共享 runtime 目录。
- C backend 全局 bridge mutex。

只要 callback 仍依赖 `g_active_context`，两个 decoder call 同时执行就可能覆盖路由目标。因此当前必须保留 Java `nativeBatchDecodeLock` 与 C bridge mutex。

## 方案比较

### 1. 显式 callback slot wrapper，推荐

为每个 bridge context slot 生成固定 callback，例如：

- `wsjtx3_ft8_callback_context_1` 到 `_4`
- `wsjtx3_ft4_callback_context_1` 到 `_4`
- `wsjtx3_q65_callback_context_1` 到 `_4`

每个 wrapper 直接调用 `append_result_for_context(context_id, ...)`。调用 decoder 前根据 handle 选择对应 callback procedure。

优点：

- 不依赖 TLS。
- 不修改 WSJT-X callback 参数 ABI。
- Callback 写入目标显式且可测试。
- 与当前固定 `WSJTX3_MAX_CONTEXTS` 模型一致。

风险：

- 需要为三种模式维护有限数量 wrapper。
- 增加 context 数量时需要同步生成 wrapper。
- 仍需处理 decoder 内部共享状态、FFTW critical 和 Q65 文件单元。

### 2. Decoder-owned context id

将 context id 存入 vendor decoder derived type，callback 通过 `this` 读取。

优点是模型自然；缺点是需要修改 WSJT-X vendor decoder 类型和相关 ABI，升级 vendor core 时维护成本较高。本项目暂不优先采用。

### 3. Thread-local active context，过渡方案

将 `g_active_context` 改为 thread-local，并在 decoder call 前后保存/恢复。

风险：

- 必须验证 Android flang runtime/OpenMP threadprivate 行为。
- 单一变量仍需处理 nested decoder call。
- 不能解决 Q65 固定文件单元、共享 module 状态和 FFTW critical。

因此 TLS 只能作为实验性过渡，不应作为直接启用并行的依据。

### 4. Callback closure/context pointer

这是长期最清晰的 ABI，但当前 Fortran decoder callback 签名不携带 user data。实现需要修改 vendor callback 接口并贯穿 FT8/FT4/Q65，改动和回归面最大。

## 推荐迁移步骤

1. 新增 `append_result_for_context(context_id, ...)`，保留 `append_active_result()` 作为兼容 wrapper。
2. 为 context slot 1 到 4 增加 mode-specific callback wrapper。
3. 串行模式下根据 handle 选择 callback，验证结果与 `g_active_context` 路径完全一致。
4. 为每次 process 记录 callback context id、result count 和 trace id，执行 FT8/FT4/Q65 串扰测试。
5. 停止 callback 对 `g_active_context` 的读取，但仍保留 Java/C 全局锁。
6. 将 result getter、reset、destroy 生命周期改为 context 级同步。
7. 隔离 Q65 临时文件和文件单元；若暂时无法隔离，Q65 保持独立串行 lane。
8. 验证 vendor decoder、FFTW 与 module 状态后，才允许逐步缩小全局锁范围。

## Per-worker native context 计划

第一阶段采用 fixed worker context，而不是 pooled context：

- 每个 Java decode worker 固定绑定一个 `NativeDecodeWorkerContext`。
- 每个 context 持有独立 decoder handle、bridge context id、输入 buffer、result buffer 和 trace id。
- Live worker 使用独立 context，优先级最高。
- Deep/diagnostic 只能使用低优先级 context，并在 live 到达时停止启动新任务。
- Q65 使用专用 context，但在文件单元和 runtime 资源隔离前仍保持串行。

建议生命周期：

`create -> configure -> reset(slot UTC/sample count) -> process -> get results -> idle -> reset ... -> destroy`

错误恢复：

- Process 或 getter 失败时，标记 context 为 poisoned。
- 在该 worker lane 内销毁并重建 context。
- 不把失败 context 放回其他 worker 使用。
- 每次重建记录 trace id，但不写持久化日志。

## 无串扰验证门槛

- FT8 multi sample 持续保持 `20/20/20/20`。
- FT4 multi sample 持续保持 `16/16/16/16`。
- Q65A/60 与 Q65F/60 持续保持 `1/1/1/1`。
- 并发压力测试中，callback context id 始终等于发起 process 的 context id。
- Reset/get-result/destroy 不读取其他 context 的 result。
- Q65 文件和 runtime 资源没有冲突。
- 完成以上验证前，`PARALLEL_NATIVE` 必须继续拒绝。
