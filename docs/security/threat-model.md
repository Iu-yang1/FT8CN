# FT8CN 威胁模型

## 保护资产

- 用户呼号、网格、精确位置、QSO 日志和导入文件。
- LoTW `.tq8`、账户状态，以及不应进入 APK 的证书私钥和密码。
- 麦克风 PCM、USB/Bluetooth 权限、CAT 会话和真实电台 PTT。
- WSJT-X 解码完整性、时隙 UTC、自动化状态与发布签名材料。

## 信任边界

外部输入包括麦克风、GNSS、SNTP、TLE/SatNOGS、ADIF/TQ8、HTTP、USB、
Bluetooth、rigctld 和 JNI。Compose/UI 不直接信任这些输入；解析、限长、时间戳
和状态转换均在 repository/controller 层完成。native Fortran 非可重入，因此
Java lock、C mutex 与 Q65 serial lane 是安全边界，不得绕过。

## 主要威胁与缓解

- **误发射或 PTT 悬挂**：显式 armed、单一状态机、CAT/PTT 读回、generation、
  cancellation、动态 watchdog、`finally` 回滚和 emergency stop。
- **错误时间触发自动 TX**：单调 UTC、SNTP/GNSS 校验、uncertainty/age 门禁、
  模式独立阈值；系统时间或单个网络样本不能直接触发自动 TX。
- **本地 HTTP 越权**：默认关闭、loopback 默认、LAN token、CSRF、POST 破坏性
  操作、参数化 SQL、输出转义和资源上限。
- **恶意导入/OOM**：SAF 用户确认、MIME/格式/大小限制、流式读取、稳定哈希
  幂等；FileProvider 只开放专用导出目录。
- **凭据泄露**：普通 DataStore、日志、Git、备份和测试 fixture 均不保存私钥、
  密码或 token；Release 签名只从环境变量或忽略的本地 properties 读取。
- **不可信网络**：LoTW、CelesTrak/SatNOGS 使用平台 TLS；rigctld 明确限定可信
  LAN/VPN；不关闭证书校验。
- **native 内存/竞态**：decoder release 先停止提交、等待 JNI、按固定锁序释放；
  Q65 使用有界 chunk/direct buffer；`PARALLEL_NATIVE` 禁用。
- **供应链混淆**：固定第三方 SHA、许可证矩阵、source manifest、SBOM 和工具
  SHA256；临时上游 clone 不进入 Git。

## 发布门禁与残余风险

Debug receiver/service 为 `exported=false`，FileProvider 不使用全 external path，
全局 cleartext 已移除。当前 Release 没有外部签名凭据，因此只生成 unsigned APK
并标记 `BLOCKED_RELEASE_SIGNING`。sanitizer runtime、高精度 EME oracle、实体电台
假负载和 LoTW 真实账户仍属于明确阻塞，不能写成 PASS。
