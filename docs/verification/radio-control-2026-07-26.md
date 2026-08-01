# 阶段 3：电台控制与安全发射门禁

## 实现边界

- `RadioController` 统一描述连接、频率、模式、VFO、split、PTT、功率和读回状态。
- `RigctldRadioController` 使用 Hamlib rigctld 文本协议，所有 socket 命令在 IO dispatcher 和互斥锁内串行执行，并对频率、模式、PTT、功率执行读回校验。
- `LegacyRigRadioController` 将已有 USB serial、Bluetooth SPP 和网络 CAT 适配器纳入相同状态接口；旧适配器无法可靠读回的能力不会伪装为支持。
- `RadioTransactionCoordinator` 提供 `NONE`、`RIG_SPLIT`、`FAKE_IT` 三种事务，执行“读取旧状态→应用→读回→失败撤销 PTT→恢复频率”。
- 发射必须先 armed；watchdog 超时、关闭、异常或用户 stop 均先撤销 PTT。CAT 和 Doppler 频率更新不进入 audio callback 或 decoder lane。
- 应用户决定，应用最低版本由 API 23 提升到 API 28；`compileSdk/targetSdk` 保持 33。用户原有 `versionName b2→b3` 仍是独立未提交资产。

## Hamlib 固定与交叉编译

- 上游提交：`c7fb0fa1482ee836e57fa0247773ad4d4c2dd54e`（`5.0.0~git`）。
- 工具链：Android NDK `26.1.10909125`，arm64-v8a，API 28，`-O2`。
- 构建脚本：`scripts/build-hamlib-android.ps1`；上游源码先复制到仓库外隔离 workspace，原始固定源码不修改。
- 产物 SHA256：`5441e4ded7f2485b066eff2748e1709dc848cc4b28e4611ec223a51b668e29ee`，大小 `11,582,856` bytes。
- ELF：AArch64；动态依赖为 `libandroid.so`、`libc++_shared.so`、`libm.so`、`libdl.so`、`libc.so`。
- APK 当前通过 rigctld 使用 Hamlib，并继续支持既有 Android transport。交叉编译库作为 ABI/可构建证据，不提交二进制，也不在没有 Android USB fd bridge 硬件矩阵前强制加载。

## 自动化门禁

- `RigctldRadioControllerTest`：3/3，通过 loopback rigctld、读回不一致回滚、命令换行注入拒绝。
- `RadioTransactionCoordinatorTest`：3/3，通过 Fake It clean passband、PTT 失败回滚、watchdog 自动停止。
- `FrequencyUpdateLimiterTest`：2/2，通过过期目标拒绝、最小间隔和最小步长抑制 CAT storm。
- Debug/Release JVM 单测、Debug/Release APK、第三方清单检查：PASS。
- Host O2 固定语料：FT8 20 条、FT4 16 条、Q65A/60 4 条，完整结果哈希保持不变。
- 官方 WSJT-X 3.0.1 `jt9`：FT8 20/20、FT4 16/16，消息多重集合、频率和 DT 严格匹配。

## 性能与真机

Host O2 本阶段测量：

| 模式 | p50 | p95 | 相对历史 p95 | 结果哈希 |
|---|---:|---:|---:|---|
| FT8 | 542.493 ms | 551.301 ms | +1.507% | `de6b3e97...70cc3` |
| FT4 | 269.910 ms | 275.837 ms | +2.362% | `877dd38b...d1d14` |
| Q65A/60 | 229.525 ms | 232.891 ms | -13.343% | `76d34ece...b9de` |

Android 16、arm64-v8a、8 logical CPU 的 Debug FT8 独立 benchmark，1 次预热加 10 次正式测量：

| 输入采样率 | 结果 | p50 | p95 | 峰值 Java heap | 峰值 native heap | 峰值 PSS | 峰值 RSS |
|---|---:|---:|---:|---:|---:|---:|---:|
| 12 kHz | 20 | 7404.473 ms | 7423.938 ms | 约 19.3 MB | 约 46.5 MB | 约 197.7 MB | 约 341.3 MB |
| 24 kHz | 20 | 7386.313 ms | 7399.716 ms | 约 16.3 MB | 约 46.4 MB | 约 195 MB | 约 338 MB |
| 48 kHz | 20 | 7284.921 ms | 7302.071 ms | 约 16.3 MB | 约 46.4 MB | 约 197 MB | 约 338 MB |

三种采样率的设备结果哈希均为 `1779a390d013173685a96cd75c1736bd371528bf6ff20dcc7b9d0f45f67b4e9a`。连续运行出现热状态差异，故本数据只作为阶段内正确性和内存证据，不与 host 毫秒数混用。

## 阻塞项

- `BLOCKED_HARDWARE_RIG`：本机没有已确认连接到假负载的实体电台，未执行真实 USB detach、低功率 PTT、功率/S-meter 和机型能力矩阵。软件 fake rig 与 loopback rigctld 已覆盖事务和回滚。
- `BLOCKED_NATIVE_HAMLIB_USB_FD`：尚未证明 Hamlib 能直接消费所有 Android USB host fd；当前生产路径使用已有 Android serial transport 或外部 rigctld，不声称全部 Hamlib 机型已在 Android 直连验证。
- `BLOCKED_SANITIZER`：既有 Android/Fortran sanitizer runtime 条件仍未满足，留到阶段 9 汇总。
