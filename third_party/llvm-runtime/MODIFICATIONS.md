# FT8CN modifications

`app/src/main/cpp/wsjtx3/android/patches/flang-rt-android-time.patch` 在隔离 workspace 中修复 Android time intrinsic 和 quadmath 构建；不会修改 `H:/tools` 下的 LLVM 源码。补丁、源码、编译器、target、ABI 和 flags 均进入构建指纹。
