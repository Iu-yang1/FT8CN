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
| 1 Kotlin 功能架构和数据基础 | 完成 | JVM/迁移/导航、Debug/Release、DSP 回归 PASS；`BLOCKED_DEVICE_LEAK` | 本阶段提交 |
| 2 NTP/GNSS 时间纪律和 slot 调度 | 待开始 | fake clock、NTP、GNSS、slot、性能 | 待提交 |
| 3 Hamlib 电台控制、split/Fake It、Doppler 底座 | 待开始 | Android ABI、fake rig/rigctld、PTT 安全 | 待提交 |
| 4 FT8/FT4 呼叫页、FT4 收发和自动化 | 待开始 | oracle、状态机、纯噪声、性能 | 待提交 |
| 5 Q65-EME 页面和生产流式内存 | 待开始 | 300 秒 RX/TX、averaging、内存、回归 | 待提交 |
| 6 卫星页面、轨道预测和双向 Doppler | 待开始 | SGP4 golden、pass、fake rig、离线缓存 | 待提交 |
| 7 本地日志、ADIF 和 LoTW | 待开始 | Room、ADIF、幂等上传、签名安全 | 待提交 |
| 8 Kotlin/Compose Material 3 UI 和全局优化 | 待开始 | UI/旋转/恢复、泄漏、宏基准、DSP 回归 | 待提交 |
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
