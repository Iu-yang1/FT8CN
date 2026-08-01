# Hamlib 上游信息

- 仓库：https://github.com/Hamlib/Hamlib
- 固定提交：`c7fb0fa1482ee836e57fa0247773ad4d4c2dd54e`
- 上游版本标识：`5.0.0~git`
- 获取日期：2026-07-26
- 构建入口：`scripts/build-hamlib-android.ps1`
- Android 验证目标：arm64-v8a、API 28、NDK 26.1.10909125、`-O2`
- 已验证库 SHA256：`5441e4ded7f2485b066eff2748e1709dc848cc4b28e4611ec223a51b668e29ee`

构建脚本要求传入或自动发现上游 Git 工作树，不在仓库内保存来源不明的预编译库。上游源码被复制到隔离工作区后才进行 CRLF 规范化和 Autotools 生成，原始固定源码不被修改。

当前 arm64 APK 动态打包并链接 `libhamlib.so`，同时保留 `rigctld` 文本协议后端。已有 USB、蓝牙和网络 CAT 仅作为兼容 transport 接入统一 radio transaction，不再绕过 PTT/Fake It 锁。实体电台与 Android USB fd 直连仍需硬件矩阵验证。

构建开关为 CMake `FT8CN_ENABLE_NATIVE_HAMLIB`：默认开启；rigctld-only 构建必须显式设为 `OFF`。非 Windows 主机开启时必须通过 `FT8CN_HAMLIB_PREBUILT_ROOT` 提供同一固定提交生成的 Android 安装树，构建不会因宿主系统不同而静默降级。
