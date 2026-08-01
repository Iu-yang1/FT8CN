# QSO、ADIF 与 LoTW 数据流

## 权威数据源

Room `qso_records` 是新写入的权威 QSO 数据源。FT8、FT4、Q65、卫星和 EME
完成通联后先在 Room 事务中写入稳定 ID、内容哈希和完整 ADIF 字段，再由专用
有界 executor 镜像到旧 SQLite，维持现有 Web 日志与统计兼容。兼容库不是新的
业务决策来源；迁移和 reconciliation 测试负责发现差异。

日志列表、统计、ADIF、LoTW 和 HTTP 都通过 repository 读取。导出按稳定顺序
分页扫描全部匹配记录，不依赖 UI 最近 250 条，也不把整个数据库装入内存。

## 导入与导出

外部 ADIF 通过 SAF 选择、大小上限、格式校验和内容 SHA256 幂等后流式解析。
禁止依赖 `InputStream.available()`、单次 `read()` 或无界正则拆分。FT8、FT4、
Q65、SAT/PROP_MODE 等字段按 ADIF 3.1.5 映射；损坏 UTF-8 或超限输入会在写库
前失败。

本地 HTTP 服务默认关闭。本机模式只绑定 loopback；LAN 模式需要显式开启、
随机 token、鉴权和 CSRF。修改/删除使用 POST，接口具备分页、请求体、速率、
线程数和超时上限，SQL 参数化且 HTML 输出转义。`/CONFIG` 不返回密码、token
或隐私配置。

## LoTW 边界

FT8CN 不在 APK 中保存 Callsign Certificate 私钥或密码，也不把未签名 ADIF
伪装成 LoTW 上传文件。生产流程为：导出 ADIF、由外部 TrustedQSL/TQSL 数字
签名、通过 SAF 导入 `.tq8`、结构预检、用户确认后上传官方 HTTPS endpoint。

`.tq8` 结构预检不是密码学签名验证；最终签名有效性由 TrustedQSL/LoTW
确认。任务状态为 LOCAL、PENDING_SIGN、SIGNED、UPLOADING、ACCEPTED、
REJECTED、CONFIRMED，使用稳定批次 ID、事务 outbox、网络约束和指数退避，
进程重启后重新调度 pending job。

内置签名保持 `BLOCKED_TQSL_EMBEDDED_SIGNING`，真实账户上传保持
`BLOCKED_LOTW_ACCOUNT`；mock 响应、幂等、拒绝、超时和恢复路径已自动测试。
