# LLVM runtime upstream

- 仓库：https://github.com/llvm/llvm-project
- 固定版本：22.1.5（Flang 编译器和隔离构建的 flang-rt）；Android NDK r26.1 自带 LLVM/OpenMP 17.0.2
- 本机源目录：通过脚本参数发现，不写入构建默认值
- 获取日期：2026-07-26
- 许可证：Apache-2.0 WITH LLVM-exception
- 项目范围：Android `flang_rt.runtime` 和 NDK `libomp` 静态链接；编译器本身不进入 APK。
