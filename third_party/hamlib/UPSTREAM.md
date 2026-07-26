# Hamlib 上游信息

- 仓库：https://github.com/Hamlib/Hamlib
- 固定提交：`c7fb0fa1482ee836e57fa0247773ad4d4c2dd54e`
- 上游版本标识：`5.0.0~git`
- 获取日期：2026-07-26
- 构建入口：`scripts/build-hamlib-android.ps1`
- Android 验证目标：arm64-v8a、API 28、NDK 26.1.10909125、`-O2`
- 已验证库 SHA256：`5441e4ded7f2485b066eff2748e1709dc848cc4b28e4611ec223a51b668e29ee`

构建脚本要求传入或自动发现上游 Git 工作树，不在仓库内保存来源不明的预编译库。上游源码被复制到隔离工作区后才进行 CRLF 规范化和 Autotools 生成，原始固定源码不被修改。

当前 APK 使用 Hamlib `rigctld` 文本协议控制远端 Hamlib，并通过统一接口适配已有 USB、蓝牙和网络 CAT。交叉编译的 `libhamlib.so` 是 Android 可行性和 ABI 门禁产物，尚未打包进 APK；实体电台与 Android USB fd 直连仍需硬件矩阵验证。
