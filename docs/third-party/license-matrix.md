# FT8CN 第三方许可证矩阵

更新时间：2026-07-26。该矩阵区分“当前进入 APK”和“仅设计参考/后续候选”。顶层 MIT 只描述 FT8CN 自有代码；由于当前 APK 静态包含 GPL-3.0-or-later 的 WSJT-X core，组合发行必须满足 GPL 对完整对应源码、许可证和修改说明的要求，不能宣称整个 APK 仅 MIT。

| 组件 | 用途与固定版本 | 来源 / 固定 SHA | 许可证 | 链接/修改 | APK 包含 | 义务与证据 |
|---|---|---|---|---|---:|---|
| WSJT-X core | FT8/FT4/Q65 RX，v3.0.0 | `WSJTX/wsjtx@ab976b1b4b72a96aaa3259591f68ad772af7d7f9` | GPL-3.0-or-later | 静态；有 Android/性能修改 | 是 | 提供完整对应源码、GPL、修改说明；`third_party/wsjtx` 与唯一 source manifest |
| kgoba ft8_lib | FT8/FT4 codec/TX，`6f528128` | `kgoba/ft8_lib@6f528128ee3ebf4d08ba2313f6c5d3913eda5608` | MIT | 源码编译；长期修改 | 是 | 保留版权/许可；`third_party/kgoba-ft8` |
| KISS FFT | FFT backend | `mborgerding/kissfft`，经上项 pin | BSD-3-Clause | 源码编译；Android 日志/shim 适配 | 是 | 保留版权、条件和免责声明；`third_party/kissfft` |
| LLVM flang-rt/libomp | Fortran/OpenMP runtime，22.1.5 / NDK 17.0.2 | `llvm/llvm-project` | Apache-2.0 WITH LLVM-exception | 静态；隔离 patch | 是 | 许可证、NOTICE、patch；`third_party/llvm-runtime` |
| Boost headers | official core 构建头文件，1.91.0 | `boostorg/boost@1a80576d` | BSL-1.0 | header-only | 是（可能展开） | 随包保留许可证；`third_party/boost` |
| MPAndroidChart | 图表，v3.1.0 | `PhilJay/MPAndroidChart@0c2ac2d9` | Apache-2.0 | 未修改 JAR | 是 | LICENSE/NOTICE；artifact SHA 见 UPSTREAM |
| Apache Commons Net | 旧 NTP 网络支持，3.6 | `apache/commons-net@163fe46c` | Apache-2.0 | 未修改 JAR | 是 | LICENSE/NOTICE；artifact SHA 见 UPSTREAM |
| NanoHTTPD | 本地 HTTP，2.2.0 | `NanoHttpd/nanohttpd@a90fe203` | BSD-3-Clause | 未修改 JAR | 是 | 二进制发行保留条款；`third_party/nanohttpd` |
| osmdroid | 地图显示，6.1.14 | `osmdroid/osmdroid@5c3809d2` | Apache-2.0 | 未修改 AAR | 是 | LICENSE；OpenStreetMap/瓦片来源另做归属 |
| AndroidX / Material | Android UI/架构，Gradle 锁定版本 | Google Maven | Apache-2.0 | 动态 Gradle 依赖 | 是 | Gradle dependency report 与 Android notices |
| Guava | 集合/工具，31.1-jre | Maven `com.google.guava` | Apache-2.0 | Gradle 依赖 | 是 | LICENSE/NOTICE |
| Google Play services Maps | Google 地图，18.1.0 | Google Maven | Google APIs Terms | Gradle 依赖 | 是 | 遵守服务条款、API key/隐私要求；不表述为开源组件 |
| FT8AF | 内存/生命周期 clean-room 参考 | `patrickrb/FT8AF@c2f63e8b` | 顶层 MIT；依赖另计 | 不链接、不复制应用源码 | 否 | 边界见 `third_party/ft8af-reference` 和联合审查 |
| Look4Sat | 卫星功能 clean-room 参考 | `rt-bishop/Look4Sat@b17ea2d9` | GPL-3.0 | 当前仅参考 | 否 | 若直接复制必须按 GPL 处理组合发行；阶段 6 前更新 |
| Hamlib | 阶段 3 电台控制候选 | `Hamlib/Hamlib@c7fb0fa1` | library 多为 LGPL-2.1-or-later；工具多为 GPL-2.0-or-later | 当前不链接 | 否 | 只选逐文件确认的 LGPL library；提供修改源码与可替换/重链接方案 |
| TQSL/tqsllib | LoTW 签名候选 | 尚未固定 | 待阶段 7 法律/构建复核 | 当前不链接 | 否 | 未完成前只允许外部 `.tq8` 工作流，禁止未签名 ADIF 直传 |

## 分发边界

- `radio_experimental` 是 FT8CN 自有实验模块，与 WSJT-X core 分离，不能混入其协议实现。
- FT2 未集成；已删除的三个 FT2 预编译归档不在 source manifest、链接 map 或 APK 中。
- Look4Sat 当前选择 clean-room 路线；若阶段 6 发现直接复用更合适，必须在代码进入前切换许可证决策，而不是事后补声明。
- Hamlib 的顶层 `LICENSE` 明确区分 LGPL library 与 GPL tools；不能仅凭仓库总许可证选择文件。
- LoTW 上传对象必须是 TQSL 数字签名的 `.tq8`。tqsllib 未经许可证和 Android 构建复核前不得打包。
