# FT8CN

FT8CN 是一款运行在 Android 上的业余无线电数字模式应用，由 BG7YOZ 开发并由 N0BOY 托管。

## Native 后端

- FT8/FT4 接收使用裁剪后的官方 WSJT-X 3.0 core。
- FT8/FT4 发射继续使用项目内兼容实现，并由 pack/CRC/LDPC/tone、合成波形回环和协议级 golden selftest 保护。
- Q65A-E 是正式开放能力；Q65F 只保留在诊断测试中，不向正式 UI 或 TX 开放。
- FT2 未集成。vendor 中保留上游源码，但不构建 FT2 应用或模式。
- Fortran callback 仍依赖全局活动上下文，因此 native 解码保持串行。Java `nativeBatchDecodeLock`、C bridge mutex 和 Q65 串行 lane 不得移除。
- `radio_experimental` 是独立实验调制解调模块，不属于 WSJT-X core。

## 构建

项目需要 JDK 17、Android SDK/NDK、CMake、Ninja，以及能够为 Android arm64 生成对象的 Flang 工具链。工具路径通过参数、环境变量或 `local.properties` 发现，不写死个人目录。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-toolchain.ps1
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1
```

`scripts/verify.ps1` 会执行工具链检查、Release O2 host CTest、语料结果/哈希回归、官方 `jt9` 逐条 oracle、Java 单测以及 debug/release/internal-test APK 构建。连接授权设备后，它还会运行非导出的 instrumentation harness，对 debug/release 与 12/24/48 kHz 输入执行结果、耗时和内存对照。大型 WAV 语料不进入 Git；语料路径和 SHA256 记录在 `docs/verification/test-corpus.json`。

门禁明确区分 `HOST_RC_PASS`、`DEVICE_RELEASE_PASS`、`BLOCKED_ORACLE`、`BLOCKED_DEVICE`、`BLOCKED_SANITIZER` 和 `FAIL`。缺少官方工具或设备不会被写成通过。机器负载明显变化时，可通过 `-PerformanceBaselinePath` 指定同电源状态、同工具链、同语料生成的基线 JSON；3% p95 阈值本身不会被放宽。

## 使用说明

本项目用于业余无线电研究和学习。使用者应遵守所在地无线电管理法规，并自行承担设备连接、发射频率、功率和自动操作所产生的责任。

感谢 K9AN、G4WJS、K1JT 及 WSJT-X 贡献者对 FT4、FT8、Q65 协议和实现的工作，也感谢 FT8CN 历史贡献者、测试者和翻译者。

## 许可证与第三方源码

FT8CN 自有源码继续采用顶层 `LICENSE` 所示 MIT 许可证。Android 组合产物同时静态包含 GPL-3.0-or-later 的 WSJT-X core，因此发行 APK 时必须满足 GPL 的完整对应源码、许可证和修改说明义务，不能把整个 APK 描述为“仅 MIT”。其他 MIT/BSD/Apache/LGPL 候选与未打包参考项目的边界见 `docs/third-party/license-matrix.md`，机器可读清单见 `docs/third-party/sbom.cdx.json`。
