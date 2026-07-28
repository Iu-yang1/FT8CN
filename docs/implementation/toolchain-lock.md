# FT8CN 可复现工具链锁定

记录日期：2026-07-26。路径由 `scripts/check-toolchain.ps1` 的显式参数、环境变量、`local.properties` 和候选目录发现，不作为构建源码默认值。

| 工具 | 固定版本 | 可执行文件 SHA256 |
|---|---|---|
| JDK | Eclipse Temurin 17.0.19+10 | `b3afe83e1ab067da4c56f1a7b2ba4c14ec832d694333f35b2b45178e9ac596ef` |
| Gradle wrapper | 7.5 | `af835f98787e9269af5a046edcb821a592fed372139df7b947b471a63cfc236b` |
| Android SDK | compile/target 33 | 由 `local.properties`/环境发现 |
| Android NDK | 26.1.10909125 | 工具链目录由 SDK 发现 |
| Android Clang | 17.0.2, r487747d | `b3d7b6767b747798d05affb68d72d060a1862a1459a885bc11fd16a4464d08ad` |
| CMake | 3.22.1-g37088a8-dirty | `41d609bae2a65a9a8e2060bb222d6e031d33c0546054d354137eb490933cb8ac` |
| Ninja | 1.10.2 | `d5d6705439aac3162ff6cfb1509246cdbbecaf9d10d7c846713af2c07d8d7ee8` |
| Flang | 22.1.5 | `50e2d389f67405b56d34f2759144c7a290bff5949178c49b837d1b16bb1a3733` |
| Host GCC/GFortran | MSYS2 UCRT 16.1.0 | 由 `check-toolchain` 运行时记录 |
| ADB | 1.0.41 | `56656270da132f44e9cb4fb86a12ba965635c80423d43dcdd944d9fec4ab4622` |
| 官方 jt9 oracle | WSJT-X 3.0.1 | `fc3a1dcd0fcbc05752d3e8fca4527ac5b7bbc2b8a60b8cfee181536d68d78a1a` |
| 卫星独立 oracle | Skyfield 1.54 / python-sgp4 2.27 | wheels `c9b313185448963ea7fa4cf8e4298ba028b179b80ebd4c5675497519f21c04a2` / `827c63feb60987ad177c2c80f3a927e721ead2f444d6560cd2a4337ca90d2490` |
| LoTW 签名参考 | TrustedQSL 2.8.6（不进 APK） | 归档 `182e5f2ac35a3db8b409b45d96505e6bd265ae4668ed064754209c4b8e7bdf37`；许可证 `dcaa6b515c503ae57805f0168c65bd5755d7998d092a08144a079099a423ef7d` |

Release native profile 固定为 `-O2 -DNDEBUG`。默认不使用 `-O3`、`-ffast-math`、LTO、主机专用指令或 `PARALLEL_NATIVE`。WSJT-X Android core 的构建指纹包含 manifest、全部源码 SHA256、编译器版本、target/ABI、flags、脚本和 patch hash。

本机工具可以安装在任意位置；复现时优先传递脚本参数：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-toolchain.ps1 `
  -JavaHome <jdk> -AndroidSdkRoot <sdk> -NdkRoot <ndk> `
  -CMakePath <cmake> -NinjaPath <ninja> -FlangPath <flang>
```
