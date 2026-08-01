# FT8/FT4 自动通联加固验证（2026-08-01）

## 修改范围

- `OperatingAutomationController` 的会话身份增加模式、波段、目标呼号和递增 generation。
- 自动发射在 CAT/PTT 准备前、late-decode 等待后、音频启动前三次验证会话仍然有效。
- 停止自动、切换模式/波段或切换目标会使旧 generation 失效；旧完成回调不能覆盖新会话。
- 自动 CQ 默认连续上限为 6 次，达到上限后退避 2 个时隙；配置值 0 被明确拒绝。
- 本阶段未修改 candidate、pass、round、同步阈值、LDPC/BP/OSD 或搜索带宽。

## 自动化测试

- early/full/deep 同时隙重复回调只推进一次。
- 同一时隙只允许一次自动发射认领。
- 模式、波段、目标不匹配时拒绝陈旧回调。
- 旧 session generation 的发射完成不能修改新目标。
- 默认 CQ 上限不可关闭，并验证退避恢复。
- 1000 个确定性随机种子验证时隙认领唯一性。
- Q65 自动序列与 FT8/FT4 自动状态保持隔离。

## 门禁结果

执行命令：

```powershell
./gradlew.bat :app:testDebugUnitTest --tests com.bg7yoz.ft8cn.core.automation.OperatingAutomationControllerTest --tests com.bg7yoz.ft8cn.core.automation.Q65AutomationControllerTest
./gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-toolchain.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
```

结果：

| 模式 | 结果数 | 完整结果 SHA256 | p50 / p95 |
|---|---:|---|---:|
| FT8 | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` | 518.012 / 524.740 ms |
| FT4 | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` | 258.692 / 260.046 ms |
| Q65A/60 | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` | 221.585 / 229.733 ms |

- Host O2 CTest：PASS。
- 官方 jt9：FT8 20/20、FT4 16/16，多重集合、频率和 DT 严格对照 PASS。
- Debug/Release 单测、APK、lint：PASS。
- Android Debug/Release native device gate：PASS。
- Sanitizer：`BLOCKED_SANITIZER`，当前 MSYS2 未发现兼容 runtime，未冒充 PASS。
