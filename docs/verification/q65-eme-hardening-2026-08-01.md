# Q65 流式内存与 EME 安全加固验证

## 结论

- Q65 实时 RX 的 24/48 kHz 录音以 4096-sample chunk 增量重采样，最终 12 kHz 时隙位于 native-owned buffer；生产链路不再创建完整源采样数组，也不再创建最终 Java `float[]`。
- Q65 A-E 的 TX 继续使用 `AudioTrack.MODE_STREAM` 和 4096-sample 有界块；播放 drain 超时、取消或创建失败均返回失败，并由统一发射状态机撤销 PTT。
- Q65 周期只接受 15/30/60/120/300 秒，正式 submode 只接受 A-E。Q65F 仍仅限诊断，不开放 UI/TX。
- 当前 `MoonEphemeris` 仅满足显示用途。高精度月面 oracle 尚未接入，因此自动 EME CAT 明确保持关闭，状态为 `BLOCKED_EME_EPHEMERIS_ORACLE`。

## 内存证据

真机为 arm64、Android 16、8 核设备；序列号未写入报告。300 秒、48 kHz RX 的生产测试结果如下：

| 构建 | source chunk | 最终输出 | 最终 Java 数组 | Java heap delta | native heap delta | 耗时 |
|---|---:|---:|---:|---:|---:|---:|
| Debug | 4096 samples | 3,600,000 samples | 0 samples | 53,248 B | 14,642,784 B | 3,539 ms |
| Release | 4096 samples | 3,600,000 samples | 0 samples | 733,792 B | 14,729,488 B | 534 ms |

旧模型最坏同时持有约 57.6 MB 的 48 kHz/300 秒 Java 源数组和约 14.4 MB 的 12 kHz Java 输出数组。新模型只保留有界输入块和约 14.4 MB 的 native 最终帧；Java heap 不再随完整时隙长度增长。

TX 真机门禁生成 14,100,480 samples，块大小固定为 4096 samples；Debug/Release 分别耗时 153/128 ms。测试只验证分块合成与播放边界，不控制真实电台发射。

## 编解码回归

严格 Release `-O2 -DNDEBUG`，预热后至少五次：

| 模式 | 结果数 | 完整结果 SHA256 | p50 / p95 | 历史 p95 变化 |
|---|---:|---|---:|---:|
| FT8 | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` | 527.540 / 536.749 ms | -1.172% |
| FT4 | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` | 262.565 / 270.460 ms | +0.367% |
| Q65A/60 | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` | 231.910 / 241.937 ms | -9.977% |

官方 WSJT-X `jt9` 对照：FT8 20/20、FT4 16/16；消息多重集合、频率和 DT 均通过。Q65 使用固定 expected result 与完整哈希门禁。

## 执行命令

- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
- `gradlew.bat :app:assembleDebugAndroidTest`
- `adb shell am instrument ...FtxStreamingResamplerInstrumentationTest...`
- `adb shell am instrument ...NativeFloatBufferInstrumentedTest...`
- `powershell -File scripts/verify.ps1`
- `git diff --check`

最终可执行门禁：`HOST_RC_PASS`、`DEVICE_RELEASE_PASS`。本机 MSYS2 工具链没有与当前 Fortran/C host 链兼容的 ASan/UBSan runtime，保留 `BLOCKED_SANITIZER`，不能以普通 CTest 替代。
