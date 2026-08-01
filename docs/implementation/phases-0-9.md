# FT8CN 阶段 0-9 实施台账

## 本轮基线

- 仓库：`H:/iu_yang1/study/FT8CN/ft8cn`
- 产品分支：`wsjtx-ft8ft4-core-port`
- 起始 SHA：`4cae0d5b7a071a7404a674dcb6e43ec81ed25a7c`
- 起始远程 SHA：`4cae0d5b7a071a7404a674dcb6e43ec81ed25a7c`
- 仓库外备份：`H:/tools/ft8cn-backups/20260801-160056`
- 本地备份分支：`backup/wsjtx-hardening-20260801-160056-4cae0d5`
- 起始工作区：1679 tracked / 6 modified / 0 staged / 0 untracked / 66 ignored
- 用户资产：`app/build.gradle` 的 `versionName b4 -> b5` 以及设置/UI 测试调整；不还原，按语义归入阶段 8/9。

## 当前进度

| 阶段 | 状态 | 核心门禁 | 提交 |
|---|---|---|---|
| 0 Git 安全、临时文件、工具链与基线 | 完成 | Host/oracle/Gradle/device PASS；sanitizer 阻塞 | `91ea3f35` |
| 1 native 生命周期、PTT 与安全 | 完成 | release/PTT/HTTP/Manifest/oracle/device PASS | `c7172367` |
| 2 NTP/GNSS 纪律化 UTC | 完成 | clock/slot/SNTP/GNSS/oracle/device PASS | `13b16f20` |
| 3 Hamlib、Split 与 Fake It | 完成 | radio transaction/APK/ELF/oracle/device PASS | `1f8721b8` |
| 4 Q65 流式内存与 EME 门禁 | 完成 | 300 秒 RX/TX、A-E、oracle/device PASS | `02faa29a` |
| 5 卫星与 Doppler | 完成 | TLE/pass/cache/UTC/oracle/device PASS | `94ce7da6` |
| 6 QSO、LoTW、导入与 HTTP | 完成 | Room 主写、旧库兼容镜像、全库分页导出、受限导入、oracle/device PASS | `a244c28b` |
| 7 FT8/FT4 呼叫与自动化 | 完成，待提交 | 有限 CQ、会话 generation、slot 去重、oracle/device PASS | 本阶段提交 |
| 8 Compose、深色模式与性能 | 待开始 | 待执行 | - |
| 9 最终门禁、文档与推送 | 待开始 | 待执行 | - |

## 固定正确性基线

| 模式 | 结果数 | 完整结果 SHA256 |
|---|---:|---|
| FT8 | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` |
| FT4 | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` |
| Q65A/60 | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` |

## 阶段记录

### 阶段 0

- 建立仓库外事故备份、备份分支、临时目录门禁、工具链锁、第三方许可证矩阵与 SBOM。
- 严格 O2 host CTest、官方 `jt9`、Gradle Debug/Release 和真机门禁通过。
- 证据：`docs/verification/hardening-baseline-2026-08-01.md`。

### 阶段 1

- decoder 关闭顺序固定为停止提交、取消队列、等待 JNI、获取固定锁序并释放句柄。
- PTT 事务加入 generation、CAT/PTT 读回、动态 watchdog 和完整回滚。
- 本地日志服务默认关闭；LAN 模式使用令牌，修改接口要求 POST/CSRF。

### 阶段 2

- FT8/FT4/Q65 使用单调时钟锚定的 UTC；自动 TX 阈值按模式区分。
- SNTP 保存 RTT/共识并支持多源重新捕获；GNSS 不记录位置隐私。

### 阶段 3

- Hamlib 优先，旧 transport 仅作已连接设备兼容层；全部 PTT/调频进入全局 radio transaction。
- Debug/Release 均包含动态 LGPL `libhamlib.so`，rigctld-only 能力显式可选。

### 阶段 4

- Q65 24/48 kHz 实时录音以 4096-sample chunk 写入 native-owned 12 kHz slot。
- Q65 A-E TX 使用 `AudioTrack.MODE_STREAM`；F 保持诊断限定。
- 高精度 EME oracle 尚未满足，自动 CAT 保持 `BLOCKED_EME_EPHEMERIS_ORACLE`。

### 阶段 5

- TLE 逐条容错、绝对 age、未来 epoch 拒绝；24 小时 pass/轨迹使用有界缓存。
- 实时 observation/Doppler 为 1 秒周期，统一读取纪律化 UTC，不在 PTT 中调频。

### 阶段 6

- 实际自动通联记录先写 Room，再在专用有界 executor 中镜像旧 SQLite，保留 WebUI/统计兼容。
- 外部 ADIF 经同一解析结果同时写 Room 与兼容库；不再依赖 `InputStream.available()`、单次读取或无界正则拆分。
- LoTW 外部签名导出按稳定顺序分页扫描全库并直写目标，不再受 UI 最近 250 条限制。
- 305 条分页导出、稳定 ID、FT4/Q65 映射、超限与损坏 UTF-8 单测通过。
- 统一验证：`HOST_RC_PASS,BLOCKED_SANITIZER,DEVICE_RELEASE_PASS`。
- 阶段 6 O2 p50/p95：FT8 517.488/527.289 ms，FT4 260.095/264.990 ms，Q65A/60 218.837/225.336 ms。

### 阶段 7

- FT8/FT4 自动通联以 `mode + band + target + session generation` 标识当前会话；换模式、波段、目标或停止后，旧任务不能修改新会话。
- CAT/PTT 准备前、late-decode 等待后和音频启动前均复核会话；同一目标内的 early/full/deep 更新仍可推进消息序列。
- 自动 CQ 默认连续 6 次后退避 2 个时隙，构造参数不再允许用 0 关闭安全上限。
- 1000 个确定性随机种子验证同一会话/时隙最多认领一次发射，Q65 独立状态机不受影响。
- 统一验证：`HOST_RC_PASS,BLOCKED_SANITIZER,DEVICE_RELEASE_PASS`。
- 阶段 7 O2 p50/p95：FT8 518.012/524.740 ms，FT4 258.692/260.046 ms，Q65A/60 221.585/229.733 ms。

## 外部阻塞

- `BLOCKED_SANITIZER`：当前 MSYS2 未发现与 host 构建兼容的 ASan/UBSan runtime；其余门禁不冒充 sanitizer PASS。
- `BLOCKED_HARDWARE_RIG`：未在安全假负载条件下操作实体电台；dummy/事务回滚测试已完成。
- `BLOCKED_EME_EPHEMERIS_ORACLE`：当前月面星历仅显示级，未解除自动 CAT。
- `BLOCKED_TQSL_EMBEDDED_SIGNING`：APK 不保存证书私钥或密码，采用外部 TQSL `.tq8` 流程。
- `BLOCKED_LOTW_ACCOUNT`：未使用真实账户凭据执行上传。

历史实现细节与原始测量保留在 `docs/verification/` 和 Git 历史中；本台账不改写既有测量结果。
