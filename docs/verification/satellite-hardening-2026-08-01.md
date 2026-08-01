# 卫星目录、过境缓存与纪律化 UTC 加固

## 修复内容

- TLE 目录按 2LE/3LE 记录逐条解析；损坏、校验失败或异常未来 epoch 的记录被隔离，同一目录中的有效卫星仍可导入。
- TLE stale 使用当前纪律化 UTC 与 epoch 的绝对差，未来或过去超过 14 天均显示过期警告。
- 选中卫星的 24 小时过境和极坐标轨迹使用包含完整 TLE、观察站的有界缓存；缓存最多 15 分钟，并在当前 pass 结束后失效。
- 实时 observation、红色设备方位标记和 Doppler 目标按 1 秒更新，不再每 5 秒重算完整 24 小时过境。
- 页面倒计时、目录刷新、手动导入和 CAT target age 统一使用 `DisciplinedClockRegistry`。自动调频继续经过 `RadioTransactionCoordinator`，不会 armed 或触发 PTT，失败/LOS 恢复原频率。

## 测试与门禁

- 卫星定向 JVM：TLE 2LE/3LE、坏记录隔离、未来 epoch、绝对 stale、缓存命中/过期、SGP4 golden、过境、Doppler、反向转发器和 radio rollback 全部通过。
- `testDebugUnitTest`、`testReleaseUnitTest`、`assembleDebug`、`assembleRelease`：PASS。
- 完整 `scripts/verify.ps1`：`HOST_RC_PASS`、`DEVICE_RELEASE_PASS`；`BLOCKED_SANITIZER`。
- 官方 WSJT-X `jt9`：FT8 20/20、FT4 16/16，消息多重集合、频率和 DT 匹配。

| 模式 | 结果数 | 完整结果 SHA256 | p50 / p95 |
|---|---:|---|---:|
| FT8 | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` | 527.928 / 534.156 ms |
| FT4 | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` | 262.298 / 267.334 ms |
| Q65A/60 | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` | 228.076 / 235.666 ms（2 次预热、15 次复测） |

五次初测的 Q65 p95 曾受单次系统调度抖动影响达到 295.040 ms；按门禁规则追加 15 次后最大正式测量为 235.666 ms，结果数和哈希每轮一致，未发现稳定回退。

## 外部阻塞

- `BLOCKED_HARDWARE_RIG`：未在已知假负载环境对实体电台执行自动 Doppler CAT；fake radio 已覆盖过期拒绝、读回、LOS 和失败回滚。
- `BLOCKED_SANITIZER`：本机没有与当前 Windows Fortran/C host 链兼容的 ASan/UBSan runtime。
