# FT8 candidate-level 并行设计（2026-06-06）

## 目标与默认策略

目标是在不减少候选、不减少 pass、不改变 OSD order 和成功判定的前提下，为 FT8 建立真正执行并行计算的实验路径。

默认路径继续保持串行。实验路径由 `FT8CandidateParallel` Log tag 显式开启：

- `WARN` 或未设置：关闭。
- `DEBUG`：请求 2 个 candidate worker。
- `VERBOSE`：请求 4 个 candidate worker。

FT4、Q65 不参与 candidate-level 并行。Q65 始终使用 serial lane。

## 当前 candidate loop 的顺序依赖

`ft8_decode.f90` 当前在一个 pass 内按候选顺序调用 `ft8b()`。成功候选会立即：

1. 调用 `subtractft8()` 修改共享的 `dd`。
2. 更新 duplicate/result 状态。
3. 写入 callback。

因此，后续候选看到的输入可能已经包含 subtraction 结果。直接对现有循环使用 `parallel do` 会改变算法语义。

此外还存在以下共享状态：

- `ft8_downsample()` 使用 `save x,cx,first,taper`；长 FFT 缓存由 `newdat` 控制。
- `four2a()` 保存 plan 表；Android KISS FFT shim 当前在执行阶段使用全局锁。
- `osd174_91()` 保存 generator matrix 和首次初始化标志。
- OSD second-preprocess 使用共享 `/boxes/`，当前 FT8 `norder=2` 路径不会进入该分支，但未来不能默认假设安全。
- phase/FT8b/LDPC/OSD tracing 使用全局聚合器，不适合 candidate worker 同时写入。
- callback、duplicate filter、pass/round/subtraction 状态必须继续串行。

## 首个安全原型：失败候选推测并行

首个原型只并行计算“候选是否能成功解码”，不在 worker 中 subtraction 或 callback：

1. 串行完成 sync search 和 pass 输入快照。
2. 串行准备共享、只读的 downsample 长 FFT 数据。
3. worker 使用独立局部输出和 scratch，调用 `ft8b(..., lsubtract=.false.)`。
4. worker 不写 callback、不写 duplicate filter、不修改 `dd`。
5. 如果所有候选都失败，则该 pass 与原串行路径等价，可直接结束该 candidate loop。
6. 如果任意候选成功，则丢弃全部推测结果，完整执行原串行 candidate loop。

该策略优先加速失败 OSD 占比很高的 pass；成功路径会自动降级串行，保持 subtraction、结果顺序和 callback 语义。

## 必须满足的启用条件

candidate parallel 只有在以下条件全部满足时才允许实际运行：

- mode 为 FT8。
- 显式实验 gate 开启。
- worker 数为 2 或 4。
- 当前不是 early/live deadline bail-out 路径；首版只允许 diagnostic sample。
- phase tracing 关闭，避免并行写全局 trace accumulator。
- OpenMP/runtime 或 bounded worker 实现已经通过 Android link/package smoke。
- downsample 长 FFT 已串行准备。
- OSD generator 初始化已串行完成或受到线程安全保护。
- candidate worker 使用独立输出和局部 scratch。

任一条件不满足时必须回到原串行路径，并在 summary 输出 `downgradeReason` 和 `fallbackCount`。

## OpenMP 工具链审查

当前 Android 官方 core 使用：

- LLVM Flang：`H:/tools/build/llvm-flang-22.1.5-clangcl/bin/flang.exe`
- Android NDK：`23.1.7779620`
- 目标：`aarch64-linux-android21`

Flang 支持 `-fopenmp`，NDK 包含 arm64 `libomp.a`/`libomp.so`，但当前构建脚本没有：

- 为 Fortran 源添加 `-fopenmp`。
- 将 OpenMP runtime 链接到 probe 和最终 `libft8cn.so`。
- 验证 APK 中 runtime 的打包方式。

因此不能直接添加 OpenMP directive 后宣称并行生效。下一阶段必须先做默认关闭的 link/package smoke。

## 回归与 fallback

每次实验必须记录：

- candidateParallelEnabled / ActuallyUsed / Threads
- pass / candidateCount / parallelTasks / duration
- downgradeReason / fallbackCount
- resultRegression / callbackMismatch
- raw / merged / native batch / Java published count

硬回归门槛：

- 默认串行 FT8：`20/20/20/20`
- FT4：`16/16/16/16`
- Q65A/60：`1/1/1/1`，消息 `W7GJ W1VD FN31`

实验结果出现数量、文本、SNR、DT、频率、顺序或 duplicate filtering 漂移时，不得默认启用。
