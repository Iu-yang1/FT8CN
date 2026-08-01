# 阶段 3 Radio Transaction 加固验证

日期：2026-08-01

## 实现边界

- `SelectableHamlibRadioController` 使用外层 mutex 串行 backend 选择、连接、断开、轮询和命令；切换前先尝试撤销 PTT。
- `HamlibFirstRadioController` 优先使用进程内 Hamlib/rigctld；旧 USB、蓝牙和网络 BaseRig 仅作为兼容 fallback，并进入同一个 `RadioTransactionCoordinator`。
- `MainViewModel` 不再直接拉高或释放 BaseRig PTT；CAT 音频队列失败也统一触发 transaction abort。
- 手动调频、EME 和卫星 Doppler 通过 `setIdleFrequency()` 与 PTT/Fake It 共用互斥锁，包含连接/PTT 检查、频率读回和失败回滚。
- Radio 页 300 ms PTT 测试使用 begin/stop 事务与 `finally`，不再直接调用 controller PTT。
- NONE、RIG_SPLIT、FAKE_IT 的候选、解码和音频参数均未修改。

## Hamlib 构建与许可证

- 固定上游：`Hamlib/Hamlib@c7fb0fa1482ee836e57fa0247773ad4d4c2dd54e`。
- Android：arm64-v8a、API 28、`-O2`，动态链接 LGPL `libhamlib.so`。
- Debug/Release APK 均实测包含 `libhamlib.so`；`llvm-readelf` 显示 `libft8cn.so` 的 `NEEDED` 包含 `libhamlib.so`、`liblog.so`、`libm.so`、`libc++_shared.so`、`libdl.so`、`libc.so`。
- CMake `FT8CN_ENABLE_NATIVE_HAMLIB=OFF` 可显式生成 rigctld-only 构建。非 Windows 主机开启 native Hamlib 时必须提供 `FT8CN_HAMLIB_PREBUILT_ROOT`，不再静默按宿主降级。
- APK 不包含 Hamlib GPL 命令行工具；LGPL 源码、修改和重链接边界记录在 `third_party/hamlib`。

## 测试

- `RadioTransactionCoordinatorTest`：Fake It、Rig Split、PTT/读回失败、watchdog、重复 stop、generation、发射中调频拒绝、调频失败回滚均通过。
- `SelectableHamlibRadioControllerTest`：backend 切换先 PTT OFF 再 disconnect。
- `HamlibFirstRadioControllerTest`：Hamlib 优先、legacy fallback、双 backend emergency stop。
- `EmeRadioTrackerTest`、`SatelliteRadioTrackerTest`：Doppler 调频、限速、读回与恢复通过。
- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`：PASS。
- `scripts/verify.ps1`：`HOST_RC_PASS,DEVICE_RELEASE_PASS,BLOCKED_SANITIZER`。

## 编解码回归

| 模式 | 数量 | 完整结果 SHA256 | p50 / p95 |
|---|---:|---|---:|
| FT8 | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` | 523.449 / 527.804 ms |
| FT4 | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` | 260.707 / 271.663 ms |
| Q65A/60 | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` | 221.370 / 229.083 ms |

官方 WSJT-X 3.0.1 `jt9`：FT8 20/20、FT4 16/16，消息多重集合、频率与 DT 在文档化容差内完全匹配。

## 阻塞项

- `BLOCKED_HARDWARE_RIG`：未确认实体电台连接安全假负载，因此未执行真实 PTT、USB detach、功率/S 表和全机型矩阵；fake、loopback rigctld、读回、回滚与 watchdog 已覆盖。
- `BLOCKED_NATIVE_HAMLIB_USB_FD`：尚未证明所有 Hamlib 机型均可直接消费 Android USB host fd；兼容 BaseRig transport 仍受统一事务保护。
- `BLOCKED_SANITIZER`：本机没有 verify 所需的 MSYS2 ASan/UBSan runtime。
