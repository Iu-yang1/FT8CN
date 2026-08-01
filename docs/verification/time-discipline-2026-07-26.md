# 阶段 2：应用内 UTC 时间纪律与 slot 调度验证

## 设计边界

- 应用 UTC 由 `SystemClock.elapsedRealtimeNanos()` 上的锚点推进，不修改 Android 系统时钟。
- NTP 使用四时间戳公式，校验 originate timestamp、leap、version、mode、stratum、KoD、root dispersion 和往返延迟。
- 配置服务器与 `time.google.com`、`time.cloudflare.com`、`pool.ntp.org` 并行采样；按 offset 中位数剔除离群值，并进行最多三次指数退避重试。
- GNSS 只有在精确定位权限、非 mock GPS fix、完整硬件 bias 和 leap second 均存在时才提交时间样本；坐标不进入状态或日志。
- NTP/GNSS 样本带 uncertainty 和 age。可信样本过期后进入 HOLDOVER；自动发射要求 uncertainty 不超过 500 ms 且样本年龄不超过 30 分钟。
- RX 不受时间健康门禁影响。自动 TX/自动 CQ 被阻止并显示原因；用户明确点击的手动 TX 只警告后继续。
- FT8 15 秒、FT4 7.5 秒、Q65 15/30/60/120/300 秒共用纯 UTC slot 算法。

Android API 语义依据：

- `SystemClock.elapsedRealtimeNanos()`：<https://developer.android.com/reference/android/os/SystemClock#elapsedRealtimeNanos()>
- `GnssClock`：<https://developer.android.com/reference/android/location/GnssClock>
- `GnssMeasurementsEvent.Callback`：<https://developer.android.com/reference/android/location/GnssMeasurementsEvent.Callback>

## 自动化测试

- `DisciplinedClockTest`：初始不可信、NTP 锚定、wall clock 跳变、holdover、过期和大离群样本拒绝。
- `SecureNtpClientTest`：本地 UDP mock 的有效四时间戳、originate mismatch 和 KoD。
- `GnssTimeConversionTest`：GPS epoch、full/fractional bias 和 leap second。
- `DisciplinedSlotSchedulerTest`：FT8/FT4/Q65 周期、负 UTC floor division，以及连续 1000 个边界无重复/漏 slot。
- Debug/Release JVM 测试、Debug/Release APK、lintVital：PASS。

## DSP 回归

相同 Release O2 host 语料，预热后由 `scripts/verify.ps1` 测量：

| 模式 | 阶段 0 p50/p95 | 阶段 2 p50/p95 | 结果数 | 完整 SHA256 |
|---|---:|---:|---:|---|
| FT8 | 513.474 / 516.580 ms | 514.634 / 526.629 ms | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` |
| FT4 | 258.967 / 266.237 ms | 261.300 / 265.155 ms | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` |
| Q65A/60 | 212.572 / 222.730 ms | 216.610 / 226.977 ms | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` |

- FT8 p95 相对阶段 0 为约 `+1.95%`，未超过阶段 2 的 2% 门限；本阶段未修改 native/DSP。
- 官方 WSJT-X `jt9` 严格多重集合：FT8 20/20、FT4 16/16，消息、频率和 DT 均匹配。
- 报告：`.tmp_phase2/verification-report.json`（临时输出，不提交）。

## 阻塞项

- `BLOCKED_DEVICE`：执行时没有授权 ADB 设备，未伪报真机 GNSS/slot 精度与页面泄漏 PASS。
- `BLOCKED_SANITIZER`：本机未发现兼容的 MSYS2 ASan/UBSan runtime。
- `BLOCKED_Q65_STREAMING`：属于阶段 5，当前生产 Q65 RX/TX 仍是完整 slot 路径。
