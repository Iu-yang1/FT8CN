# FT8CN 阶段 0-9 实施台账

- 仓库：`H:/iu_yang1/study/FT8CN/ft8cn`
- 产品分支：`wsjtx-ft8ft4-core-port`
- 开始 SHA：`10b2c62e4c75021eb559825c3da85f77b10a052d`
- 开始远程 SHA：`10b2c62e4c75021eb559825c3da85f77b10a052d`
- 用户工作区资产：`app/build.gradle` 的 `versionName b2 -> b3`，不纳入阶段提交
- 仓库外备份：`H:/tools/ft8cn-backups/20260726-182508-10b2c62`

| 阶段 | 状态 | 主要门禁 | 提交 SHA |
|---|---|---|---|
| 0 仓库、基线、联合审查、工具链和合规 | 完成 | Host/oracle/Gradle PASS；`BLOCKED_DEVICE`、`BLOCKED_SANITIZER`、`BLOCKED_Q65_STREAMING` | `a4aa090a` |
| 1 Kotlin 功能架构和数据基础 | 完成 | JVM/迁移/导航、Debug/Release、DSP 回归 PASS；`BLOCKED_DEVICE_LEAK` | `888738a6` |
| 2 NTP/GNSS 时间纪律和 slot 调度 | 完成 | fake clock、本地 NTP mock、GNSS 转换、slot、DSP/oracle PASS；`BLOCKED_DEVICE` | `fac3c4c0` |
| 3 Hamlib 电台控制、split/Fake It、Doppler 底座 | 完成 | API 28 AArch64 ELF、fake rig/rigctld、PTT 安全、device FT8 PASS；`BLOCKED_HARDWARE_RIG` | `dab521a4` |
| 4 FT8/FT4 呼叫页、FT4 收发和自动化 | 完成 | oracle、状态机、纯噪声、Debug/Release 真机矩阵 PASS；`BLOCKED_HARDWARE_TX` | `68cf7b29` |
| 5 Q65-EME 页面和生产流式内存 | 完成 | 300 秒 RX/TX、averaging、内存、host/oracle/Debug/Release 真机回归 PASS；`BLOCKED_SANITIZER` | `16e4a26c` |
| 6 卫星页面、轨道预测和双向 Doppler | 完成 | SGP4/Skyfield golden、pass、fake rig、离线缓存、Debug/Release/device/DSP PASS；`BLOCKED_HARDWARE_RIG` | `2bd9b82e` |
| 7 本地日志、ADIF 和 LoTW | 完成 | Room v4、ADIF 3.1.5、签名 TQ8、幂等上传与 mock PASS；`BLOCKED_TQSL_EMBEDDED_SIGNING`、`BLOCKED_LOTW_ACCOUNT` | `11f8d86b` |
| 8 Kotlin/Compose Material 3 UI 和全局优化 | 部分完成 | DSP/device PASS；`BLOCKED_DEVICE_UI_UNLOCK`、`BLOCKED_COMPLETE_COMPOSE_MIGRATION`、`BLOCKED_BASELINE_PROFILE_GENERATION` | 本阶段提交 |
| 9 集成验收、发布门禁和最终交付 | 待开始 | 全量 verify、device、ELF、SBOM、secret | 待提交 |

状态只能在该阶段实现和门禁完成后改为“完成”。外部硬件、账户或平台工具缺失时，记录精确的 `BLOCKED_*`，并保留已完成的 fake、离线或 host 证据。

## 阶段 0 记录

- 严格 O2 host CTest、FT8/FT4/Q65 固定语料与哈希、官方 `jt9` 逐条 oracle、Gradle unit/debug/release/androidTest APK：PASS。
- 1 次预热后连续 20 次：FT8 513.474/516.580 ms，FT4 258.967/266.237 ms，Q65A/60 212.572/222.730 ms；结果哈希无变化。
- 完成 FT8AF 联合内存审查、12 个第三方组件登记、许可证矩阵、CycloneDX 1.5 SBOM 和工具链锁定。
- 当前无授权 ADB 设备；设备冷启动、前后台和 live 20-slot 不伪报。Q65 生产流式留给阶段 5 完成。

## 阶段 1 记录

- 以渐进方式加入 Kotlin 1.8.10、Coroutines、Compose、Room、DataStore 和 WorkManager；旧 `MainActivity`、SQLite 与 JNI/DSP 路径保持默认且不改行为。
- 建立 `DisciplinedClock`、`DecoderCoordinator`、`RadioController`、`DopplerEngine`、`QsoLogRepository`、`AutomationController` 接口及 fake；PCM 通过按块 source 传递，不进入 `FeatureState`。
- Room schema v1/v2 和 `MIGRATION_1_2` 已生成并通过旧 QSO 数据保留测试；DataStore schema migration、六个导航目的地和 fake 边界通过 JVM 测试。
- 阶段门禁：28 项 Debug JVM 测试和 Release JVM 测试通过；Debug/Release/androidTest APK 构建通过；Compose 外壳 `exported=false`，旧 `MainActivity` 仍为 launcher。
- O2 固定语料：FT8 20 条、`de6b3e97...70cc3`、513.474/516.580 → 512.641/518.284 ms；FT4 16 条、`877dd38b...d1d14`、258.967/266.237 → 258.788/260.523 ms；Q65 4 条、`76d34ece...b9de`、212.572/222.730 → 224.072/227.042 ms。FT8/FT4 均在 3% 门槛内，且本阶段未改 native。
- 官方 `jt9` 仍为 FT8 20/20、FT4 16/16 严格匹配。当前无 ADB 设备，页面反复进入/退出的真机 LeakCanary 门禁记为 `BLOCKED_DEVICE_LEAK`，不冒充 PASS。

## 阶段 2 记录

- 应用 UTC 已改为单调时钟锚定；NTP/GNSS 样本携带 uncertainty、age、source 和 drift，系统 wall clock 跳变不再直接改变正在运行的 slot。
- NTP 完成四时间戳、响应身份、KoD/stratum/leap/root dispersion 校验，多服务器稳健融合及指数退避；GNSS 只接受非 mock fix、完整 bias/leap 的 measurement，且不记录坐标。
- FT8/FT4/Q65 统一 slot 边界算法。自动 TX 在不健康时间下阻止，手动 TX 明确警告后仍可继续；RX 不受影响。
- Debug/Release JVM、APK 和 lintVital PASS；O2 固定语料与官方 `jt9` 哈希/结果完全不变。FT8/FT4/Q65 p50/p95 为 514.634/526.629、261.300/265.155、216.610/226.977 ms。
- 无授权 ADB 与 sanitizer runtime，分别保留 `BLOCKED_DEVICE`、`BLOCKED_SANITIZER`；详见 `docs/verification/time-discipline-2026-07-26.md`。

## 阶段 3 记录

- 建立统一 `RadioController`、rigctld 和既有 USB/Bluetooth/network adapter，加入频率、模式、VFO、split、PTT、功率读回与错误状态。
- `RadioTransactionCoordinator` 实现 NONE/RIG_SPLIT/FAKE_IT、armed、全局 stop、PTT watchdog 和失败恢复；Doppler 目标具有 age、最小间隔和步长限制。
- Hamlib 固定在 `c7fb0fa1482ee836e57fa0247773ad4d4c2dd54e`，arm64 API 28 O2 构建和 ELF 依赖检查通过；应用最低版本按用户决定统一到 API 28。
- 8 项 radio JVM 测试、Debug/Release、第三方清单、host O2 与官方 jt9 通过；FT8/FT4/Q65 哈希不变，p95 均在阶段门槛内。
- 真机 Debug FT8 在 12/24/48 kHz 均稳定得到 20 条且哈希一致。实体电台低功率/PTT 与 Android USB fd 机型矩阵记为 `BLOCKED_HARDWARE_RIG`，不伪报硬件 PASS。
- 详见 `docs/verification/radio-control-2026-07-26.md`。

## 阶段 4 记录

- Call 页面只保留 FT8/FT4；Q65/EME 控件保留实现但隐藏，等待独立 EME 页面接管。
- 新增自动 QSO 确定性门禁，同一 slot 的 early/full/deep 重复回调只推进一次，同一 slot 最多认领一次自动 TX；模式切换和 stop 会隔离旧会话。
- 6 项状态机测试、完整 Debug/Release JVM 与 APK、官方 `jt9` FT8 20/20、FT4 16/16、各 200 个纯噪声时隙 0 假解码均通过。
- 真机 Debug/Release 在 12/24/48 kHz 下：FT8 均为 20 条且哈希一致，FT4 均为 16 条并保持既有每采样率哈希；Release p95 详见阶段报告。
- Host native 未修改。20 次 FT8 最终 p95 相对阶段 3 增加 2.503%；严格脚本对较早历史参考的一次波动失败已保留，阶段 9 在稳定环境重测，不以降低灵敏度掩盖。
- 详见 `docs/verification/ft8-ft4-operating-workflow-2026-07-26.md`。

## 阶段 5 记录

- Q65 24/48 kHz RX 改为录音 chunk 直接写入最终 12 kHz slot；300 秒 48 kHz 不再同时持有约 57.6 MB 源数组和 14.4 MB 输出数组。
- Q65 A-E TX 改为官方 tone 一次生成、连续相位 JNI 分块合成和 `AudioTrack.MODE_STREAM`；Java/native/PCM chunk 均固定为 4096 samples，Q65F 保持诊断限定。
- 独立 EME Compose 页面展示 A-E、周期、grids、averaging 和三种 WSJT-X Doppler 组合；Call 页仍不混入 Q65。
- Host FT8/FT4/Q65 哈希不变，官方 `jt9` FT8/FT4 严格一致；Debug/Release 真机 12/24/48 kHz 结果一致，Q65 流式 instrumentation 各 7 项通过。
- 完整门禁状态为 `HOST_RC_PASS`、`DEVICE_RELEASE_PASS`、`BLOCKED_SANITIZER`。高精度月面天文 oracle 与外部 wave transport 流式协议分别记录为 `BLOCKED_EME_EPHEMERIS_ORACLE`、`BLOCKED_Q65_EXTERNAL_STREAMING`，不伪报完成。
- 详见 `docs/verification/q65-eme-streaming-2026-07-28.md`。

## 阶段 6 记录

- 以 clean-room 边界引入固定版本、Unlicense 的 Java SGP4；Look4Sat 只参考功能范围，未复制 GPL-3.0 源码或资源。
- 完成严格 TLE、SGP4/TEME、站心观测、过境、地面/极坐标轨迹、上下行 Doppler、反向线性转发器和可回滚 CAT 跟踪；跟踪永不自动 PTT。
- CelesTrak/SatNOGS 使用 HTTPS、条件请求、响应上限、最短刷新间隔和 Room v3 离线元数据；官方仓库外快照与 Skyfield 1.54 golden PASS。
- 全量 host/oracle/Gradle/真机门禁 PASS，FT8/FT4/Q65 结果哈希不变，host p95 相对阶段 0 均在 3% 内；sanitizer runtime 仍为 `BLOCKED_SANITIZER`。
- 详见 `docs/verification/satellite-doppler-2026-07-28.md`。

## 阶段 7 记录

- 新日志仓库使用 Room v4 保留 FT8/FT4/Q65、卫星/EME、频率、网格、报告和 LoTW 审计状态；旧 SQLite 日志没有删除或覆盖。
- ADIF 3.1.5 编解码按官方模式枚举导出，输入长度、字段和记录数量均有边界；重复 QSO 通过稳定 SHA256 合并。
- LoTW 只允许外部 TQSL 数字签名的 `.tq8`：结构校验、私有 no-backup 存储、文件 SHA256、WorkManager 唯一任务、指数退避和官方 HTTPS 响应解析均已接线。
- TrustedQSL 2.8.6 已在 `H:/tools` 做许可证与依赖审查，但未进入 APK；内置签名和真实账户上传分别记录为 `BLOCKED_TQSL_EMBEDDED_SIGNING`、`BLOCKED_LOTW_ACCOUNT`。
- 完整发布回归状态为 `HOST_RC_PASS`、`DEVICE_RELEASE_PASS`、`BLOCKED_SANITIZER`；官方 `jt9` 对 FT8 20 条、FT4 16 条逐条多重集合匹配，Q65 4 条固定结果保持不变。
- Host O2 的 FT8/FT4/Q65 p50/p95 分别为 522.145/548.953、261.002/269.214、221.922/233.311 ms；FT8、FT4 相对历史 p95 为 +1.075%、-0.096%，均未超过 3% 门槛。
- 详见 `docs/verification/logbook-lotw-2026-07-28.md`。

## 阶段 8 记录

- Material 3 工作台提供 Call、EME、Satellite、Logbook、Radio、Settings 六入口；Call 只包含
  FT8/FT4，手机抽屉和宽屏 NavigationRail 使用同一稳定路由。
- 修复 AudioRecord 快速停止/重启竞态，monitor 热路径不再逐 block 复制列表；频谱固定复用
  640-bin、`Rect[]` 和 bitmap，并在 View 生命周期边界释放/重建。
- Debug/Release 各 88 项 JVM、APK、lint、host CTest、官方 `jt9` 和真机 Debug/Release
  12/24/48 kHz 门禁通过；FT8/FT4/Q65 哈希保持不变。
- Host O2 FT8/FT4/Q65 p50/p95 为 523.227/530.964、260.421/271.920、
  218.259/218.943 ms；FT8/FT4 p95 相对阶段 7为 -3.277%/+1.005%。
- 安全锁屏和 Oplus 后台 Activity 门禁阻止 Compose 真机交互/宏基准；默认入口仍保留完整兼容
  操作台，分别记录 `BLOCKED_DEVICE_UI_UNLOCK`、`BLOCKED_COMPLETE_COMPOSE_MIGRATION` 和
  `BLOCKED_BASELINE_PROFILE_GENERATION`，不伪报完成。
- 详见 `docs/verification/compose-memory-2026-07-29.md`。
