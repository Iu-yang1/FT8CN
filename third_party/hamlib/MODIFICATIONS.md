# FT8CN 修改说明

FT8CN 不修改 Hamlib 固定上游工作树。构建时只在隔离副本执行以下处理：

1. 将 `configure.ac`、`Makefile.am`、M4 和 bootstrap 文件的 CRLF 规范化为 LF，避免 Autoconf 把 `\r` 识别为头文件名的一部分。
2. 固定 Autoconf 2.71，禁用 readline、libusb、C++ 和脚本绑定。
3. 使用 Android NDK arm64 API 28 编译共享库，与应用 `minSdk=28` 对齐并保持 `-O2`。
4. 输出库使用 `llvm-strip --strip-unneeded`，不提交二进制产物。

FT8CN 自有的 Kotlin `RigctldRadioController` 仅实现公开 rigctld 协议，不复制 Hamlib GPL 命令行工具源码。现有 CAT adapter 的实现仍属于 FT8CN 自有代码。
