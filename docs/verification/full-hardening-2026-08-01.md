# FT8CN 完整加固与发布验收（2026-08-01）

## 结论与范围

- 分支：`wsjtx-ft8ft4-core-port`
- 起始本地/远程 SHA：`4cae0d5b7a071a7404a674dcb6e43ec81ed25a7c`
- 本报告覆盖的实现 SHA：`4d23885c3e19eaa2ec9fe09742da95ab41597f5d`
- 完整机器报告：`H:/tools/ft8cn-stage9-verification-final-20260801.json`
- Q65 会话清理复测：`H:/tools/ft8cn-stage9-q65-session-cleanup.json`

最终脚本状态为 `HOST_RC_PASS`、`BLOCKED_SANITIZER`、
`BLOCKED_Q65_STREAMING`、`BLOCKED_RELEASE_SIGNING`，没有 `FAIL`。这里的
`BLOCKED_Q65_STREAMING` 只表示当前 unsigned Release 无法安装，Debug 生产路径
的 300 秒流式门禁已通过；不得据此宣称当前正式 Release 设备门禁完成。

## 阶段与提交

| 阶段 | 主题 | 提交 |
|---|---|---|
| 0 | 基线、Git/临时目录、工具链与许可证 | `91ea3f357fa3f5ca744c4ea3cf1a47b328656aa0` |
| 1 | decoder/PTT/HTTP/Android 生命周期与安全 | `c7172360c35a69fa1432ba4c39cc8e78107a88a6` |
| 2 | SNTP/GNSS 纪律化 UTC | `13b16f201f252f411e742176bc7228813243a181` |
| 3 | Hamlib、Split、Fake It 与 radio transaction | `1f8721b8ffe6743cb5f9216dc06916c762986b3a` |
| 4 | Q65 流式内存和 EME CAT 门禁 | `02faa294ca655289f308609d91cbecb9dd0fd9eb` |
| 5 | 卫星预测、缓存和 Doppler | `94ce7da67abccf2cc3cb702d80a6833f06b1dac7` |
| 6 | Room QSO、ADIF、LoTW 与 HTTP | `a244c28bdbaa48168fc433d998c81500fafecfea` |
| 7 | FT8/FT4 自动化会话安全 | `963ec8b8d025f148560b25838e2776a7f6744523` |
| 8 | Material 3 UI、录音、频谱与功能入口 | `006706b6b1b0a6d1ba2bd9f892a3f2a4fd809f2e` |
| 9 | 签名/可复现构建与真机门禁稳定性 | `d0f9fa2638772f22ba458fc1da2a3bf7af4bca28`、`9d1ba5378375c47101aa2a8572d860f0d68cf581`、`4d23885c3e19eaa2ec9fe09742da95ab41597f5d` |

起始未提交的 `versionName b4 -> b5` 在阶段 9 以 `versionCode 5`、
`versionName b5` 进入 `d0f9fa2`；其余设置/UI/测试修改按功能进入阶段 8。没有
创建 WIP 提交，也没有恢复或遗漏用户修改。

## 工具链与执行命令

| 工具 | 版本 | 可执行文件 SHA256 |
|---|---|---|
| JDK | 17.0.19 | `b3afe83e1ab067da4c56f1a7b2ba4c14ec832d694333f35b2b45178e9ac596ef` |
| Gradle wrapper | 7.5 | `af835f98787e9269af5a046edcb821a592fed372139df7b947b471a63cfc236b` |
| Android NDK/Clang | 26.1.10909125 / 17.0.2 | `b3d7b6767b747798d05affb68d72d060a1862a1459a885bc11fd16a4464d08ad` |
| CMake | 3.22.1 | `41d609bae2a65a9a8e2060bb222d6e031d33c0546054d354137eb490933cb8ac` |
| Ninja | 1.10.2 | `d5d6705439aac3162ff6cfb1509246cdbbecaf9d10d7c846713af2c07d8d7ee8` |
| Flang | 22.1.5 | `50e2d389f67405b56d34f2759144c7a290bff5949178c49b837d1b16bb1a3733` |
| ADB | 1.0.41 | `56656270da132f44e9cb4fb86a12ba965635c80423d43dcdd944d9fec4ab4622` |

主要命令为：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-toolchain.ps1
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -JavaHome H:/tools/jdks/jdk-17.0.19+10 -ReportPath H:/tools/ft8cn-stage9-verification-final-20260801.json
gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
gradlew.bat :app:testReleaseUnitTest :app:lint
adb shell am instrument -w -r -e class com.bg7yoz.ft8cn.wave.FtxStreamingResamplerInstrumentationTest,com.bg7yoz.ft8cn.ft8transmit.Q65WaveStreamInstrumentationTest com.bg7yoz.ft8cn.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

`scripts/verify.ps1` 内部执行严格 O2 host CMake/Ninja、CTest、语料、官方
oracle、PowerShell parser、OSD、重采样、scheduler/request context、纯噪声、
Gradle 和设备 benchmark。ELF 使用 NDK `llvm-readelf` 检查。

## Host 正确性与性能

Release host 使用 `-O2 -DNDEBUG`，先预热再测量；O3 未启用。FT8/FT4 没有
减少候选、pass、round、BP/OSD、同步阈值、搜索带宽或纠错深度。

| 模式 | 结果 | 完整结果 SHA256 | p50/p95 | 峰值 working set | private memory | 相对参考 p95 |
|---|---:|---|---:|---:|---:|---:|
| FT8 | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` | 514.359 / 517.692 ms | 27,119,616 B | 70,946,816 B | -4.681% |
| FT4 | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` | 258.589 / 259.603 ms | 16,601,088 B | 69,304,320 B | -3.662% |
| Q65A/60 | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` | 215.573 / 217.856 ms | 49,184,768 B | 98,529,280 B | -18.938% |

官方 WSJT-X 3.0.1 `jt9.exe` SHA256 为
`fc3a1dcd0fcbc05752d3e8fca4527ac5b7bbc2b8a60b8cfee181536d68d78a1a`。
FT8 20/20、FT4 16/16 的消息多重集合、频率和 DT 逐条匹配；容差为 3.2 Hz
和 0.06 秒，0–3000 Hz 外没有 FT8CN 额外结果。Q65 由固定语料、期望结果和
完整哈希门禁验证，不冒充 jt9 FT8/FT4 cross-oracle。

## 真机 Debug 矩阵

设备为 Android 16、arm64-v8a、8 核 RMX5062；序列号未写入报告。每项预热
1 次、正式 10 次。结果数始终为 FT8 20、FT4 16、Q65 4。

| 模式/源采样率 | p50/p95 | CPU p50/p95 | Java heap 峰值 | native heap 峰值 | PSS 峰值 | RSS 峰值 |
|---|---:|---:|---:|---:|---:|---:|
| FT8/12k | 1089.880 / 1122.410 ms | 161.138 / 165.828% | 21,074,848 B | 44,338,920 B | 156,675,072 B | 349,487,104 B |
| FT8/24k | 1104.406 / 1121.741 ms | 157.960 / 162.392% | 16,514,528 B | 44,740,368 B | 158,632,960 B | 350,863,360 B |
| FT8/48k | 1097.508 / 1112.182 ms | 159.363 / 165.960% | 16,452,944 B | 44,609,192 B | 158,832,640 B | 350,941,184 B |
| FT4/12k | 464.152 / 489.707 ms | 96.402 / 98.153% | 15,884,032 B | 50,688,424 B | 166,309,888 B | 358,555,648 B |
| FT4/24k | 480.574 / 490.670 ms | 94.573 / 98.385% | 9,727,456 B | 48,371,912 B | 160,964,608 B | 353,263,616 B |
| FT4/48k | 466.912 / 486.275 ms | 95.035 / 98.326% | 16,424,960 B | 49,770,712 B | 166,551,552 B | 358,866,944 B |
| Q65/12k | 356.171 / 383.256 ms | 93.953 / 97.818% | 10,370,528 B | 121,818,696 B | 240,926,720 B | 433,111,040 B |
| Q65/24k | 352.143 / 384.255 ms | 92.325 / 97.797% | 18,558,432 B | 121,807,800 B | 247,105,536 B | 439,033,856 B |
| Q65/48k | 345.158 / 378.565 ms | 97.477 / 98.725% | 24,284,640 B | 121,804,856 B | 252,844,032 B | 444,874,752 B |

FT8 三档设备结果哈希均为
`1779a390d013173685a96cd75c1736bd371528bf6ff20dcc7b9d0f45f67b4e9a`；
Q65 均为 `b8bf4a9a5f6718ef4df6479bd6232d890a33c081b57e370a8ee2d6644c5d2813`。
FT4 结果数与文本一致，但 12/24/48 kHz 的精确频率/DT 字段不同，完整设备
哈希分别为 `9cac34409bfbf3f5552f99c16f0e46350bc0ab452c28871698775e1b73870792`、
`b240f95611bd9ed69971f6f9be323d27f90fb321ab980b0cc8b9b19db490c36c`、
`c8fc70fb7d86c314fb7967c7b1638a6eae10d60d50d5cb124869bf785f3eb560`。

## Q65 300 秒内存

Debug 真机复测 7 项通过。48 kHz/300 秒 RX 的 source chunk 为 4096 点，最终
12 kHz frame 为 3,600,000 点且由 native 持有，Java 最终数组为 0；Java heap
增量 86,016 B、native heap 增量 14,475,808 B。它消除了约 57.6 MB 完整 48 kHz
源 `float[]` 与 14.4 MB Java 输出的双重常驻。TX 使用 4096 点块生成并送流，
总计 14,100,480 点，门禁耗时 125 ms，不创建完整 Java 波形。

阶段 8 曾在同一 native core 上取得 Release 设备证据（FT8/FT4/Q65 12 kHz
p95 为 608.213/281.501/221.537 ms，Q65 RX Java/native 增量为
151,696/14,704,384 B），但阶段 9 移除 debug signing 后当前 Release 为 unsigned，
因此这组历史证据不能替代当前签名候选的 Release 门禁。

## 功能、安全与构建

- FT4/FT8/Q65 自动 TX uncertainty 上限分别为 250/500/1000 ms；样本最大 age
  30 分钟。RX 不因时间不健康停用，自动 TX/CAT 会硬阻断。
- Hamlib、NONE/RIG_SPLIT/FAKE_IT、PTT 读回、取消和回滚通过 dummy/事务测试；
  实体电台未在假负载条件下发射。
- EME 自动 CAT 因缺少高精度月面 oracle 保持禁用。卫星 SGP4、TLE 容错、pass
  缓存和 Doppler golden 测试通过。
- Room 是 QSO 权威写入源；旧 SQLite 仅为 WebUI/统计兼容镜像。LoTW 仅上传
  外部 TQSL 已签名 `.tq8`。
- Debug/Release unit test、APK 构建、lint、host CTest、oracle、OSD、纯噪声、
  重采样、scheduler、request context、Q65 capacity/averaging/TX-RX 均通过。
- `connectedDebugAndroidTest` 被本机 AGP/UTP protobuf `IllegalAccessError` 阻塞；
  相同 test APK 通过直接 `adb am instrument` 执行。最终 Compose UI 复测被用户
  安全锁屏阻塞，未绕过 PIN；阶段 8 在已解锁设备上的同一测试为 2/2 PASS。

APK：Debug 60,540,695 B，SHA256
`b3f9ff2b7d75ed01caf5743b9a0c9214b492455cedf765a6c381cacffa8df68e`；
unsigned Release 48,748,116 B，SHA256
`ca2f518a03dada4dd4cd95d05377d521ae16216dafc269e8d32bc260ba687824`；
androidTest 5,849,104 B，SHA256
`30e833f7a627120f32bd7f93706728e122bd1338b1a92cad39c8b08fa7d8c66f`。
Debug 证书 SHA256 为
`5fc40f60a7b878de53c3a04c54aa4912fd9738b27ba3b947cc4f3aa7183255e6`；
Release 没有证书，不能分发。

`libft8cn.so` SHA256 为
`4891b80c2ed8fd5b7f335f91836a2b5d8e45d19bafec81c5f32fa3b5968b0fb8`，
动态依赖为 `libhamlib.so`、`liblog.so`、`libm.so`、`libc++_shared.so`、
`libdl.so` 和 `libc.so`。

## 仓库卫生与阻塞

起始计数为 1679 tracked / 6 modified / 0 staged / 0 untracked / 66 ignored；
实现 SHA 计数为 1711 / 0 / 0 / 0 / 63。无 tracked symlink、submodule、LFS
pointer、嵌套 Git、APK、object 或 native 静态归档。四个忽略的嵌套 Git 检查
目录被完整移动到 `H:/tools/ft8cn-work/repo-inspection-20260801-1615`，没有删除
用户文件或有效上游源码；`.tmp_wsjtx_lib_inspect` 未跟踪、未暂存。

- `BLOCKED_RELEASE_SIGNING`：未提供外部 keystore/密码；只生成 unsigned Release。
- `BLOCKED_Q65_STREAMING`：Debug 生产路径通过，当前 Release 设备半边受签名阻塞。
- `BLOCKED_SANITIZER`：MSYS2 未找到兼容混合 C/Fortran 构建的 ASan/UBSan runtime。
- `BLOCKED_ANDROID_UTP`：本机 UTP protobuf 冲突；使用直接 instrumentation 替代。
- `BLOCKED_DEVICE_UI_KEYGUARD`：最终 UI 复测遇到安全锁屏，未绕过用户 PIN。
- `BLOCKED_HARDWARE_RIG`：没有经确认的假负载射频测试环境。
- `BLOCKED_EME_EPHEMERIS_ORACLE`：高精度月面 oracle 未完成，自动 CAT 禁用。
- `BLOCKED_TQSL_EMBEDDED_SIGNING`：不在 APK 中集成私钥签名，采用外部 TQSL。
- `BLOCKED_LOTW_ACCOUNT`：没有使用真实账户凭据上传。

本报告不把代码已实现但因外部条件未验证的项目描述为 PASS。
