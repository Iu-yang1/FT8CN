# 阶段 6：卫星轨道、过境和双向 Doppler 验证

## 实现边界

- 采用 clean-room 路线：Look4Sat 固定于 `b17ea2d9180632c1a0d8f80d8a8e5ebff09ce67c`，仅参考功能范围，未复制其 GPL-3.0 源码、资源或 UI 表达。
- 数值传播使用 `aholinch/sgp4@552cb1489a52c3023ae70cb6c7e239e84c5950fe` 的 Java 实现（Unlicense）。FT8CN 自行实现严格 TLE 校验、TEME/ECEF/站心转换、过境搜索、转发器映射、双向 Doppler、缓存、电台事务和 Compose 页面。
- 上游 `TLE.java` 仅移除修改 JVM 全局默认时区的副作用；SGP4 方程、常量和传播路径未修改。
- 卫星 CAT 跟踪只更新频率，不会 armed 或触发 PTT；过期目标、读回失败和停止会恢复进入跟踪前的 RX/TX 频率。

## 数据和隐私

- CelesTrak 请求固定使用 HTTPS、显式 `FORMAT=TLE`、ETag/If-Modified-Since、8 MiB 上限和两小时最短刷新间隔。
- SatNOGS 转发器请求使用 HTTPS、条件请求和 2 MiB 上限；UI 显示 CC BY-SA 4.0 数据归属。
- Room schema v3 保存源、ETag、Last-Modified、SHA256、成功/失败时间和下次刷新时间；目录更新保留收藏，离线和手动 2LE/3LE 导入均可用。
- 位置不写日志；Compose 只保留至多 250 个可见摘要、16 次过境、64 个极坐标点和 96 个地面轨迹点。

## 独立 oracle

- Oracle：Skyfield 1.54 + python-sgp4 2.27，隔离安装于 `H:/tools/python-packages/skyfield-1.54`。
- Skyfield wheel SHA256：`c9b313185448963ea7fa4cf8e4298ba028b179b80ebd4c5675497519f21c04a2`；python-sgp4 wheel SHA256：`827c63feb60987ad177c2c80f3a927e721ead2f444d6560cd2a4337ca90d2490`。
- Vallado 00005 向量在 epoch 与 +360 min 的位置/速度逐分量匹配。
- 观察站 40°N, 75°W 的首个过境 Skyfield golden：AOS `2000-06-27T19:17:45.079Z`、TCA `19:39:26.255Z`、LOS `20:06:13.304Z`、最大仰角 `47.387480°`；FT8CN 门禁容差为时间 2.5 s、角度 0.15°。
- 官方运行时快照仅保存在仓库外：CelesTrak amateur 93 颗，SHA256 `266dcc3ffa3c1e39c8456e4213f9cb02ae5070d3404a490be64bc1ec051cd746`；SatNOGS NORAD 7530，SHA256 `d6de026c6103a50623e5eb88b48f997206f24a8290917afcaa8dd8549fd9a599`。

## 门禁结果

- 卫星定向测试：SGP4 Vallado 向量、Skyfield pass golden、跨日/过期 TLE、Doppler 符号、反向转发器、fake rig 回滚、CelesTrak/SatNOGS 解析和 Room 2→3 迁移全部 PASS。仓库外官方快照通过显式环境变量门禁。
- 全量 JVM：Debug 74 项、Release 74 项，失败 0；常规全量运行仅跳过 1 个需要仓库外快照的可选实网快照测试，该测试已在单独命令中 PASS。
- Gradle `testDebugUnitTest`、`testReleaseUnitTest`、`assembleDebug`、`assembleRelease`、`lintVitalRelease`：PASS。
- 严格 O2 host CTest：PASS。FT8 20 条、SHA256 `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3`、p50/p95 `521.303/530.063 ms`；FT4 16 条、`877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14`、`258.856/260.302 ms`；Q65A/60 4 条、`76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de`、`216.749/228.639 ms`。
- 相对阶段 0 host p95：FT8 `+2.61%`、FT4 `-2.23%`、Q65 `+2.65%`，均在 3% 门槛内；本阶段未修改 native decoder、候选、pass、round、LDPC/OSD 或搜索带宽。
- 官方 WSJT-X 3.0.1 `jt9`：FT8 20/20、FT4 16/16，消息多重集合、频率和 DT 严格比较 PASS。
- 真机 Debug/Release 12/24/48 kHz：FT8 均 20 条、FT4 均 16 条、Q65 均 4 条，variant 间无差异；Release p95 范围 FT8 `596.945–606.061 ms`、FT4 `285.866–291.375 ms`、Q65 `182.156–200.525 ms`。设备为 Android 16、arm64-v8a、8 logical CPU，FT8 sync 使用 2 线程。
- 真机整个矩阵峰值：Debug Java/native/PSS/RSS `31.2/126.6/196.8/301.8 MiB`；Release `21.5/122.2/197.4/342.4 MiB`。这些是连续全矩阵进程峰值，不是单独卫星页占用。
- Q65 300 秒生产流式门禁继续 PASS：RX source chunk 4096、最终 12 kHz slot 3,600,000 samples；TX chunk 4096，不恢复完整 Java TX 波形。
- `scripts/verify.ps1` 最终状态：`HOST_RC_PASS`、`DEVICE_RELEASE_PASS`、`BLOCKED_SANITIZER`；JSON 保存在仓库外 `H:/tools/ft8cn-stage6-verification-20260728.json`。
- `git diff --check` 与 CycloneDX JSON 解析 PASS；APK、TLE 快照、wheel 和设备日志均未纳入 Git。

## 外部阻塞

- `BLOCKED_HARDWARE_RIG`：没有在已知天线/假负载环境下对实体电台执行自动 Doppler CAT；fake rig 已覆盖 AOS→LOS、过期拒绝、读回和失败回滚。
- `BLOCKED_PROVIDER_FAILURE_MATRIX`：未主动破坏 CelesTrak/SatNOGS 服务；HTTP 错误、空响应、超限、304 和离线缓存由可注入 transport 测试覆盖。
