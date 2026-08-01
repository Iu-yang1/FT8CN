# 日志、ADIF 与 LoTW 加固验证（2026-08-01）

## 修复范围

- 自动通联完成后先通过稳定 SHA256 ID 写入 Room，随后镜像旧 SQLite，因而现代日志/LoTW 与旧 WebUI/统计都能看到新记录。
- 自动通联回调快照 operating profile，后台写入不读取下一时隙的可变模式。
- ADIF 分享导入与 HTTP 导入统一使用 32 MiB 有界 UTF-8 读取和 `AdifCodec`，移除 `available()`、单次 `read()`、无界正则拆分和临时 raw thread。
- LoTW 外部签名导出按 `startedUtcMillis, stableId` 分页扫描全库并直接写入 SAF 输出流；UI 最近 250 条仅用于展示，不再限制导出。
- 旧 Web/统计读取模型暂时保留为兼容镜像，Room 是新功能与 LoTW 的主写入源；任何 Room 写入失败会记录错误并保住旧库记录，不静默丢失 QSO。

## 新增回归

- `LegacyQsoPersistenceTest`：FT4/Q65、频率、报告、EME 标记与卫星元数据不臆造。
- `BoundedAdifReaderTest`：`available()==0` 仍读至 EOF；超限和损坏 UTF-8 被拒绝。
- `LotwWorkflowTest.externalSigningExportPagesAcrossEntireRepository`：305 条记录以 37 条分页完整导出，并全部进入 `PENDING_SIGN`。

## 验证结果

- 定向 JVM 测试：PASS。
- Debug/Release 全量 JVM、APK、Debug lint：PASS。
- 严格 Release O2 CTest：PASS。
- 官方 `jt9`：FT8 20/20、FT4 16/16，消息多重集合、频率与 DT 严格匹配。
- 真机 Debug/Release 12/24/48 kHz 与 Q65 流式门禁：PASS。
- 最终状态：`HOST_RC_PASS,BLOCKED_SANITIZER,DEVICE_RELEASE_PASS`。

| 模式 | 结果 | SHA256 | p50 / p95 |
|---|---:|---|---:|
| FT8 | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` | 517.488 / 527.289 ms |
| FT4 | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` | 260.095 / 264.990 ms |
| Q65A/60 | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` | 218.837 / 225.336 ms |

相对阶段 0 固定门槛，FT8/FT4 结果和哈希未变化，p95 未出现超过 3% 的稳定回退。本阶段未修改 decoder 搜索、pass、round、BP/OSD、阈值或频宽。

## 精确阻塞

- `BLOCKED_SANITIZER`：本机 MSYS2 未发现兼容的 host ASan/UBSan runtime；不将普通 CTest 冒充 sanitizer PASS。
- `BLOCKED_TQSL_EMBEDDED_SIGNING`：继续使用外部 TrustedQSL 生成的数字签名 `.tq8`；结构预检不宣称密码学签名验证。
- `BLOCKED_LOTW_ACCOUNT`：没有使用真实 LoTW 凭据，mock/队列/恢复门禁通过。
