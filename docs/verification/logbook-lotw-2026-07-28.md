# 阶段 7：日志、ADIF 与 LoTW 安全工作流

## 规范边界

- ADIF 基线为官方 3.1.5（2024-11-28）。FT8 导出为 `MODE=FT8`；FT4、Q65
  分别导出为 `MODE=MFSK/SUBMODE=FT4` 与 `MODE=MFSK/SUBMODE=Q65`。
- 卫星记录同时携带 `PROP_MODE=SAT`、`SAT_NAME` 和可选 `SAT_MODE`。
- LoTW 官方端点只接收自验证的数字签名 `.tq8`。FT8CN 不保存私钥或证书密码，
  也不允许未签名 ADIF 进入上传传输层。
- 官方资料：`https://adif.org.uk/315/ADIF_315.htm`、
  `https://lotw.arrl.org/lotw-help/developer-submit-qsos/`、
  `https://lotw.arrl.org/lotw-help/developer-tq8/`。

## 实现

- Room schema v4 保存完整 QSO、卫星字段、可审计 LoTW 状态、错误和幂等任务。
- ADI 解析器限制 32 MiB、20 万记录和单字段长度；异常记录隔离，不让畸形输入拖垮导入。
- `.tq8` 必须是有界 gzip，并包含 TQSL identity、证书、`tCONTACT`、`SIGNDATA`
  和 `SIGN_LOTW_1.0`。导入后复制到 `noBackupFilesDir`，以 SHA256 命名。
- WorkManager 使用 SHA256 唯一任务、网络约束和指数退避；上传只访问
  `https://lotw.arrl.org/lotw/upload` 的 `upfile` multipart 字段，并解析官方
  `.UPL.` / `.UPLMESSAGE.` HTML 注释。
- 状态机为 `LOCAL -> PENDING_SIGN -> SIGNED -> UPLOADING -> ACCEPTED/REJECTED`
  及 `ACCEPTED -> CONFIRMED`，非法迁移会失败关闭。

## 验证和阻塞

- ADIF FT8/FT4/Q65/卫星往返、旧格式导入、畸形/Unicode 输入、Room 3→4 迁移：PASS。
- TQ8 无签名拒绝、SHA 幂等、accepted/rejected/timeout 重试、状态机：PASS。
- Debug/Release JVM 测试、APK、lintVital、host CTest 和真机 Debug/Release 解码门禁：PASS。
- Host O2 固定语料：FT8 20 条，SHA256 `de6b3e97...70cc3`，p50/p95
  522.145/548.953 ms；FT4 16 条，SHA256 `877dd38b...d1d14`，
  261.002/269.214 ms；Q65A/60 4 条，SHA256 `76d34ece...b9de`，
  221.922/233.311 ms。
- 官方 WSJT-X 3.0.1 `jt9` 对 FT8 20 条和 FT4 16 条的消息多重集合、频率与
  DT 严格对照：PASS；阶段 7 未修改 DSP、候选预算、pass、round 或同步阈值。
- TrustedQSL 2.8.6 归档 SHA256：
  `182e5f2ac35a3db8b409b45d96505e6bd265ae4668ed064754209c4b8e7bdf37`。
- `BLOCKED_TQSL_EMBEDDED_SIGNING`：尚未完成 Android 证书生命周期、私钥硬件保护、
  OpenSSL/Expat/zlib/SQLite/config 联合移植和安全审计；使用外部 TQSL 签名代替。
- `BLOCKED_LOTW_ACCOUNT`：没有用户证书和真实待上传 QSO，因此不进行真实账户上传；
  mock 传输与官方响应协议已覆盖，未伪报在线受理。
- `BLOCKED_SANITIZER`：本机 MSYS2 未发现与当前 host 链兼容的 ASan/UBSan runtime；
  CTest、Android 真机和严格 oracle 通过不能替代该门禁。
