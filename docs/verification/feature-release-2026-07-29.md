# FT8CN 阶段 0-9 集成发布验收（2026-07-29）

## 仓库与提交

- 产品分支：`wsjtx-ft8ft4-core-port`。
- 起始本地/远程 SHA：`10b2c62e4c75021eb559825c3da85f77b10a052d`。
- 阶段 0-8 提交：`a4aa090a`、`888738a6`、`fac3c4c0`、`dab521a4`、`68cf7b29`、`16e4a26c`、`2bd9b82e`、`11f8d86b`、`dae3b4e3`。
- 阶段 9 为本报告所在的本地提交；没有 push、merge、rebase 或远程修改。
- 用户原有 `app/build.gradle` 的 `versionName b2 -> b3` 始终未暂存、未还原、未混入阶段提交。

## 最终门禁状态

- `HOST_RC_PASS`：严格 Release O2 host CTest、固定语料及 expected result 门禁通过。
- `DEVICE_RELEASE_PASS`：授权真机 Debug/Release、12/24/48 kHz、Q65 流式内存门禁通过。
- `BLOCKED_SANITIZER`：详见阻塞报告；不能以普通 CTest 或真机结果替代。
- 严格 `lintDebug`、`lintRelease`、Debug/Release APK、androidTest APK、ELF、第三方清单、SBOM 和仓库卫生检查通过。

## 解码正确性

| 模式 | 结果数 | 完整结果 SHA256 | 官方 oracle |
|---|---:|---|---|
| FT8 | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` | jt9 20/20 |
| FT4 | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` | jt9 16/16 |
| Q65A/60 | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` | 固定语料/expected result |

官方 WSJT-X 3.0.1 `jt9.exe` SHA256 为 `fc3a1dcd0fcbc05752d3e8fca4527ac5b7bbc2b8a60b8cfee181536d68d78a1a`。FT8/FT4 按消息多重集合、结果数、频率和 DT 比较；容差为 3.2 Hz/0.06 s。`test-corpus.json` 的指定结果另以 0.11 Hz/0.011 s 检查。双方差集、计数差异和 metric mismatch 均为空。

OSD 门禁覆盖 `include_pre1=0/1`、`ntheta=10/12`、不同 `nt`、零/极值/重复行与 1000 个确定性随机种子，优化实现和参考实现逐项一致。扩展 AWGN 门禁中 FT8 在 +10/0/-10/-16 dB 均为 20/20；FT4 为 20/20、20/20、20/20、14/20。纯噪声 FT8/FT4 各 200 slot，合计约 1.25 等效小时，CRC-valid 误解码 0。

## 产品能力与安全边界

- FT8/FT4 RX 使用官方 WSJT-X 3.0 core；FT8 仅在单请求内部以 1-2 条性能线程并行独立 `sync8` 频率行，后续候选、LDPC/OSD、subtract 与 callback 串行确定。FT4、Q65 和请求级 native 仍串行，`PARALLEL_NATIVE` 未启用。
- Q65A-E 生产 RX/TX 已使用 4096-sample 有界分块；Q65F 仍只可诊断，未进入正式 UI/TX。
- `DisciplinedClock` 使用单调时钟锚点、NTP/GNSS uncertainty 和 holdover。自动 TX/CQ 只在 uncertainty 不超过 500 ms 且样本年龄不超过 30 分钟时允许；RX 不被阻断。
- Radio 层具备 rigctld、既有 USB/Bluetooth/network adapter、NONE/RIG_SPLIT/FAKE_IT、读回、回滚、armed、stop 和 PTT watchdog。没有安全假负载时未执行真实发射。
- Satellite 使用 clean-room 边界与固定 Unlicense SGP4；Vallado 向量和 Skyfield 1.54 pass golden 通过。Look4Sat 源码未复制。
- LoTW 只接受外部 TQSL 已数字签名的 `.tq8`；未签名 ADIF 不能进入上传层，私钥/口令不进入普通 DataStore、日志或 Git。

## 构建、ELF 与合规

- JDK 17.0.19、Gradle 7.5、NDK 26.1.10909125、Android Clang 17.0.2、CMake 3.22.1、Ninja 1.10.2、Flang 22.1.5、ADB 1.0.41 均由参数/环境/`local.properties`/候选目录发现。
- `libft8cn.so`：ELF64 AArch64，SHA256 `81e166d2ac5fdfe23abc377f6fe2aa6903db85206ff2d20f1763b4413655d892`；NEEDED 仅 `liblog.so`、`libm.so`、`libc++_shared.so`、`libdl.so`、`libc.so`。
- 第三方门禁：15 个组件、87 个唯一 WSJT-X build input、CycloneDX 1.5 均通过。组合 APK 含 GPL-3.0-or-later WSJT-X core，不能称为“仅 MIT”。
- 仓库索引中 symlink、submodule、LFS pointer、gitlink、APK/AAB/`.a`/`.so`/对象/CMake/Ninja 输出均为 0。
- 旧 FT2 归档 `libportaudio.a`、`libwsjt_cxx.a`、`libwsjt_fort.a` 均不存在、未跟踪、未进入 manifest/CMake/ELF；删除提交为既有 `dafc1ee`，FT2 源码保留。

## 实际执行的主要命令

```powershell
scripts/check-toolchain.ps1
scripts/check-third-party.ps1
scripts/verify.ps1 -ReportPath H:/tools/ft8cn-final-verify.json
scripts/run-extended-channel-tests.ps1 -NoiseSlotsPerMode 200 -SnrTrials 20 -OutputJson H:/tools/ft8cn-final-extended-channel.json
gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebugAndroidTest :app:assembleDebug :app:assembleRelease
gradlew.bat :app:lintDebug :app:lintRelease
ctest --test-dir app/src/main/cpp/wsjtx3/host/build-release-o2 --output-on-failure
adb shell am instrument -w -r -e class com.bg7yoz.ft8cn.wave.FtxStreamingResamplerInstrumentationTest com.bg7yoz.ft8cn.ft4.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class com.bg7yoz.ft8cn.ft8transmit.Q65WaveStreamInstrumentationTest com.bg7yoz.ft8cn.ft4.test/androidx.test.runner.AndroidJUnitRunner
llvm-readelf -h -d app/build/intermediates/merged_native_libs/release/out/lib/arm64-v8a/libft8cn.so
git diff --check
```

Sanitizer 还实际尝试了 MSYS2 UCRT64 `compiler-rt 22.1.4-4` 和独立 LLVM 22.1.4 混合探针；失败原因与包 SHA256 见阻塞报告。机器可读完整结果保存在仓库外 `H:/tools/ft8cn-final-verify.json` 和 `H:/tools/ft8cn-final-extended-channel.json`，未提交设备序列号、WAV、APK 或日志。
