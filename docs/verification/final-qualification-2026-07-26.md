# 发布资格验收 - 2026-07-26

## 范围与状态

- 产品分支：`wsjtx-ft8ft4-core-port`
- 起始提交：`20a8472fd018ec22db8e5c30a8bd7341f9417586`
- 已验代码提交：`8fe6e1449e69fcb82b82231533f51ec1dfaf1f72`
- 最终状态：`HOST_RC_PASS`、`BLOCKED_DEVICE`、`BLOCKED_SANITIZER`、`BLOCKED_Q65_STREAMING`
- 用户原有 `app/build.gradle` 版本号 `b2 -> b3` 未暂存、未还原。
- 未执行 merge、rebase、cherry-pick、push、PR 或远程修改。

`DEVICE_RELEASE_PASS` 未授予：验收时没有授权 ADB 设备，且 Q65 长周期生产流式路径尚未接入。

## 工具与官方 oracle

| 工具 | 版本 |
|---|---|
| JDK | OpenJDK 17.0.19 |
| Android NDK / Clang | NDK 26.1.10909125 / Clang 17.0.2 |
| Android CMake / Ninja | 3.22.1 / 1.10.2 |
| Flang | 22.1.5 |
| ADB | 1.0.41 |
| Host GCC/GFortran | MSYS2 UCRT 16.1.0 |
| 官方 jt9 | WSJT-X 3.0.1 |

官方安装器来自 WSJT-X 官方下载页指向的 SourceForge 3.0.1 发布目录，安装器 SHA256 为 `4e3c5006bf919553f79784c7a512c0a9706e78937f0b9ab037078d73d951dc0b`。`jt9.exe` SHA256 为 `fc3a1dcd0fcbc05752d3e8fca4527ac5b7bbc2b8a60b8cfee181536d68d78a1a`。`verify.ps1` 在未传 `-Jt9Path` 时已从 `H:/tools` 自动发现该工具。

严格 oracle 规范：消息按多重集合比较，不吞重复项；频率容差 3.2 Hz，DT 容差 0.06 秒；FT8/FT4 信号的完整占用带宽必须落在产品 0-3000 Hz 搜索范围。

- FT8：官方 20，FT8CN 20，消息/频率/DT 均匹配。
- FT4：官方带内 16，FT8CN 16，消息/频率/DT 均匹配。
- 官方 FT4 另有起始频率 2995、3159、3337 Hz 三条，完整 FT4 占用带宽不在 0-3000 Hz 内；报告保留为 `official_out_of_band`，没有静默丢弃。

## 正确性与性能

Release 使用 `-O2 -DNDEBUG`，未启用 fast-math、CPU 专用指令、LTO 或 `PARALLEL_NATIVE`。此前同语料 O2/O3 比较的结果数和哈希一致，但 O3 的 FT8/FT4 p95 更慢，因此保持 O2。

| 模式 / 语料 | 结果 | 完整结果 SHA256 | 任务前 p50/p95 | 本轮 p50/p95 | 本轮峰值 RSS / Private |
|---|---:|---|---|---|---|
| FT8 `210703_133430.wav` | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` | 540.096 / 543.117 ms | 546.834 / 553.437 ms | 26,845,184 / 70,467,584 B |
| FT4 `000000_000002.wav` | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` | 269.323 / 269.472 ms | 271.311 / 274.080 ms | 16,334,848 / 68,898,816 B |
| Q65A/60 `210106_1621.wav` | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` | 258.688 / 268.751 ms | 251.501 / 256.307 ms | 49,131,520 / 98,127,872 B |

FT8 p95 相对任务前为 +1.90%，FT4 为 +1.71%，均未超过 3%。曾观察到系统热/负载变化使历史绝对值失真，因此额外在同一会话从起始提交构建独立源码快照并与当前实现对比：

- FT8 采用 60 对交替执行，起始 p50/p95 为 574.857/587.354 ms，当前为 573.186/587.764 ms，差异 -0.291%/+0.070%；全部 120 次均为 20 条且完整结果哈希一致。
- FT4 采用 25 次顺序执行，起始 p95 304.065 ms，当前 p95 304.085 ms，差异 +0.007%；全部结果均为 16 条且完整结果哈希一致。

原始逐次耗时记录在 `performance-baseline-2026-07-26.json` 和本地临时机器报告中；没有减少候选、pass、round、BP/OSD 深度、LDPC 迭代、同步阈值或搜索带宽。

## 测试结果

- Release O2 host CMake/Ninja 严格构建：PASS。
- CTest：1/1 PASS；聚合 selftest 包含 codec 11/11、FT8/FT4 12/24/48 kHz synthetic decode、分块重采样逐 bit 等价、Q65 capacity/averaging/TX-RX、OSD 与 request snapshot。
- OSD：`include_pre1=0/1`、`ntheta=10/12`、多种 `nt`、零值/极值/重复行和至少 1000 个确定性随机种子，优化实现与参考实现逐项一致。
- Extended AWGN：FT8 在 10/0/-10/-16 dB 均为 10/10；FT4 为 10/10、10/10、10/10、6/10。
- 纯噪声：FT8 100 slot、FT4 100 slot，共 0.625 等效小时，CRC-valid 误解码 0。
- Gradle `testDebugUnitTest`、`assembleDebug`、`assembleRelease`、`assembleDebugAndroidTest`：PASS。
- Android instrumentation APK：编译/打包 PASS；因无设备未运行 10 次真机计时、debug/release 结果对照和 Java/native/PSS/RSS 采集。
- Debug 合并 manifest：`SampleDecodeReceiver` 与 `SampleDecodeForegroundService` 均为 `exported=false`。
- `llvm-readelf`：Debug/Release `libft8cn.so` 仅依赖 `liblog.so`、`libm.so`、`libc++_shared.so`、`libdl.so`、`libc.so`。
- Sanitizer：BLOCKED。发现的 LLVM 22 Windows ASan runtime 属于 MSVC ABI，不能与当前 MSYS2 UCRT/GFortran official core 安全混链；MSYS2 sanitizer runtime 不存在。

## Q65 长周期内存

`common/resampler` 新增有状态 12/24/48 kHz 到 12 kHz 分块接口。129-tap FIR、phase、边缘状态与环形历史总工作区小于 2 KiB；任意 chunk 边界与一次性实现逐 bit 一致，30/60/120/300 秒容量和溢出检查通过。FT8/FT4 生产路径仍使用原路径。

生产 Q65 RX 尚未接线：48 kHz、300 秒仍可能同时持有 14,400,000 个源 float（57.6 MB）和 3,600,000 个 12 kHz float（14.4 MB），不含录音器/JNI/decoder。生产 TX 仍由 Java 完整波形和 `AudioTrack.MODE_STATIC` 驱动。缺少真机 300 秒低内存、取消和 underrun 证明，因此按安全要求保留 `BLOCKED_Q65_STREAMING`，没有冒险接入。正式 UI/TX 仅 A-E；F 只留诊断测试。

## 仓库卫生

- Git symlink 0、submodule 0、LFS pointer 0、tracked WAV 0、tracked APK/对象/so/CMake/Ninja 输出 0。
- `libportaudio.a`、`libwsjt_cxx.a`、`libwsjt_fort.a` 均不存在、未跟踪、未进入 source manifest/CMake/link。唯一文本引用位于保留的上游 `ft2/g4.cmd` 历史命令，FT2 不构建。
- `git status --ignored --untracked-files=all` 曾列出 25,568 项，而普通 status 仅 23 项（其中 22 项为本任务变更、1 项为用户版本号）。主要来源为已忽略的 `app/build`、`app/.cxx`、`.gradle-local` 和 probe 目录。
- 发现四个已忽略临时源码镜像包含嵌套 `.git`：`.tmp_ft8cnbyqijie`、`.tmp_otto`、`.tmp_wsjtx`、`.tmp_wsjtx2`。按“不删除 .git/用户目录”约束保留；它们不是主仓库变更，IDE 的 7k+ 提示来自生成物/嵌套仓库发现，而非主仓库跟踪异常。
- 本轮没有删除文件。三份 FT2 归档已在起始提交之前的 `dafc1ee` 删除，本轮仅重新验证其无引用状态。

## 主要命令

```powershell
git status --short --branch
git rev-list --left-right --count origin/release...HEAD
powershell -ExecutionPolicy Bypass -File scripts/check-toolchain.ps1
powershell -ExecutionPolicy Bypass -File app/src/main/cpp/wsjtx3/host/build_host_probe.ps1 -BuildType Release -Optimization O2
ctest --test-dir app/src/main/cpp/wsjtx3/host/build-release-o2 --output-on-failure
powershell -ExecutionPolicy Bypass -File scripts/run-extended-channel-tests.ps1 -NoiseSlotsPerMode 100 -SnrTrials 10
gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1
llvm-readelf -d app/build/intermediates/cmake/release/obj/arm64-v8a/libft8cn.so
git diff --check
```

完整机器可读 gate、工具 SHA、APK SHA、oracle 差异、耗时和内存位于本地忽略目录 `.tmp_verify_run/verification-report.json`。
