# 自适应解码并行验收 - 2026-07-26

## 范围

- 分支：`wsjtx-ft8ft4-core-port`
- 实现提交：`2eff784`（FT8 自适应频率切块）、`45230e1`（真机 CPU/内存计量）
- Release：`-O2 -DNDEBUG`；未启用 fast-math、LTO、CPU 专用指令或 `PARALLEL_NATIVE`
- Java `nativeBatchDecodeLock`、C bridge mutex、Q65 串行 lane 均保留
- 候选预算、pass、multi-decode round、BP/OSD 深度、LDPC 迭代、同步阈值和 0-3000 Hz 搜索带宽均未减少

## 并行边界

FT8 官方 `sync8` 按频率行静态切块。每个线程独立计算一段频率范围，并在行内复用 7-tone 总能量；候选生成后的排序、`ft8b`、LDPC/OSD、subtract、去重和 callback 仍按原顺序串行执行。因此并行区不改变候选顺序或浮点归并顺序。

运行时读取 Linux CPU capacity，缺失时回退到 max-frequency。性能簇定义为不低于最大 capacity 的 80%，始终给录音/UI 保留至少一个在线核心，并将 FT8 sync 限制为最多两个线程。realme RMX5062 的 8 核拓扑为 4 个 capacity 450、3 个 871、1 个 1024，策略选择 2 个 FT8 sync 线程。ADB 抽样观察到计算线程由 Android 调度到 CPU 4-7；没有硬绑核。

FT4 曾采用同类 OpenMP 频率切块，但配对真机 A/B 慢 21%-29%，已回退。保留的 FT4 优化只缓存 `idf=-16..16` 的 Costas 模板。Q65 仍串行；其 averaging、Fortran unit 和 callback 状态不满足请求级并行条件。

## 正确性

官方 WSJT-X 3.0.1 `jt9` 严格 oracle 使用消息多重集合、3.2 Hz 频率容差和 0.06 秒 DT 容差：

| 模式 | 官方/FT8CN | Host 完整结果 SHA256 | 结果 |
|---|---:|---|---|
| FT8 `210703_133430.wav` | 20/20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` | PASS |
| FT4 `000000_000002.wav` | 16/16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` | PASS |
| Q65A/60 `210106_1621.wav` | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` | PASS |

真机 Debug/Release 在 12/24/48 kHz 共 18 个 case、每个 1 次预热和 10 次正式测量，结果数与完整哈希差异为 0。FT8 设备哈希为 `1779a390d013173685a96cd75c1736bd371528bf6ff20dcc7b9d0f45f67b4e9a`；Q65 为 `b8bf4a9a5f6718ef4df6479bd6232d890a33c081b57e370a8ee2d6644c5d2813`。FT4 的 12/24/48 kHz 哈希分别为 `9cac34409bfbf3f5552f99c16f0e46350bc0ab452c28871698775e1b73870792`、`b240f95611bd9ed69971f6f9be323d27f90fb321ab980b0cc8b9b19db490c36c`、`c8fc70fb7d86c314fb7967c7b1638a6eae10d60d50d5cb124869bf785f3eb560`。

## 性能

同一 host、Release O2、关闭 tracing，2 次预热后 FT8/FT4 各 30 次，Q65 10 次：

| 模式 | 任务参考 p50/p95 | 当前 p50/p95 | p95 变化 | 峰值 RSS / Private |
|---|---:|---:|---:|---:|
| FT8 | 540.096 / 543.117 ms | 510.183 / 523.489 ms | -3.61% | 27,181,056 / 71,032,832 B |
| FT4 | 269.323 / 269.472 ms | 259.347 / 267.168 ms | -0.85% | 16,621,568 / 69,353,472 B |
| Q65A/60 | 258.688 / 268.751 ms | 212.128 / 223.531 ms | -16.83% | 49,225,728 / 98,525,184 B |

与同会话串行 FT8 基线 `574.857/587.354 ms` 比较，当前 p50/p95 分别改善 11.25%/10.87%。三线程方案的 p95 不稳定，未采用；两线程是当前设备上吞吐、尾延迟和移动端功耗之间更稳妥的选择。

真机完整门禁设备为 realme RMX5062、Android 16/API 36、arm64-v8a。持续负载后的 Release 12 kHz 结果如下；这些热态绝对值只用于 Debug/Release 一致性和资源上界，不替代 host 稳定基线：

| 模式 | p50/p95 | CPU 利用率 p50 | Java / native / PSS / RSS 峰值 |
|---|---:|---:|---:|
| FT8 | 592.447 / 609.211 ms | 178.48% | 21.3 / 41.8 / 89.1 / 238.6 MB |
| FT4 | 270.883 / 292.380 ms | 91.27% | 9.5 / 46.6 / 92.9 / 242.1 MB |
| Q65A/60 | 167.920 / 175.208 ms | 97.31% | 10.8 / 120.4 / 173.2 / 322.2 MB |

Debug 比 Release 慢约一个数量级，因为 official Fortran Debug 使用 `-O0`、`-g`、`-fcheck=all` 和 backtrace；两者输出完全一致，性能结论只采用 Release。

## 回归与门禁

- 严格 O2 host CMake/Ninja、CTest、codec/synthetic/resampler/Q65/OSD/request selftest：PASS
- OSD：`include_pre1=0/1`、`ntheta=10/12`、不同 `nt`、零/极值/重复行和至少 1000 个确定性随机种子逐项等价：PASS
- AWGN 10 次/点：FT8 在 `+10/0/-10/-16 dB` 为 `10/10/10/10`；FT4 为 `10/10/10/6`
- 纯噪声：FT8 100 slot（0.417 小时）和 FT4 100 slot（0.208 小时），CRC-valid 误解码 0
- Gradle unit test、Debug/Release APK、内部 androidTest APK：PASS
- 非导出真机 harness Debug/Release 18-case 矩阵：PASS
- Release ELF：OpenMP 静态链接；动态依赖仅 `liblog`、`libm`、`libc++_shared`、`libdl`、`libc`
- `HOST_RC_PASS`；官方 oracle、Android build 和 device gate 均 PASS
- `BLOCKED_SANITIZER`：MSYS2 UCRT 工具链缺少兼容的 ASan/UBSan runtime
- `BLOCKED_Q65_STREAMING`：分块重采样已验证，但生产 Q65 RX capture 与 TX `AudioTrack.MODE_STREAM` 尚未接线

机器可读报告位于本地忽略目录 `.tmp_verify_run/`，不进入 Git。
