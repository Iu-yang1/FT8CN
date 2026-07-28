# FT8CN 第三方许可证矩阵

更新时间：2026-07-26。顶层 MIT 只覆盖 FT8CN 自有代码。APK 静态包含 GPL-3.0-or-later 的 WSJT-X core，因此组合分发不能宣称为“仅 MIT”，必须同步提供完整对应源码、许可证和修改说明。

| 组件 | 固定版本/来源 | 许可证 | 链接或用途 | APK 包含 | 义务与证据 |
|---|---|---|---|---:|---|
| WSJT-X core | `WSJTX/wsjtx@ab976b1b4b72a96aaa3259591f68ad772af7d7f9` | GPL-3.0-or-later | FT8/FT4/Q65 RX，静态链接 | 是 | `third_party/wsjtx` 保存完整对应源码、manifest、许可证和修改说明 |
| kgoba ft8_lib | `kgoba/ft8_lib@6f528128ee3ebf4d08ba2313f6c5d3913eda5608` | MIT | FT8/FT4 codec 与 TX | 是 | 保留版权和许可证，见 `third_party/kgoba-ft8` |
| KISS FFT | `mborgerding/kissfft`，版本见上游登记 | BSD-3-Clause | FFT backend | 是 | 保留版权、条件和免责声明，见 `third_party/kissfft` |
| LLVM flang-rt/libomp | LLVM 22.1.5 / NDK 17.0.2 | Apache-2.0 WITH LLVM-exception | Fortran/OpenMP runtime | 是 | 许可证、NOTICE 和隔离 patch，见 `third_party/llvm-runtime` |
| Boost headers | 1.91.0，`boostorg/boost@1a80576d` | BSL-1.0 | WSJT-X 构建头文件 | 可能展开 | 随包保留许可证，见 `third_party/boost` |
| Hamlib | `Hamlib/Hamlib@c7fb0fa1482ee836e57fa0247773ad4d4c2dd54e` | 库 LGPL-2.1-or-later；工具 GPL-2.0-or-later | rigctld 协议；Android native 构建门禁 | 否 | APK 不含 GPL CLI 或 native 库；来源、构建和替换边界见 `third_party/hamlib` |
| MPAndroidChart | 3.1.0 | Apache-2.0 | 图表 JAR | 是 | 保留 LICENSE/NOTICE 和 artifact SHA |
| Apache Commons Net | 3.6 | Apache-2.0 | 旧 NTP 支持 | 是 | 保留 LICENSE/NOTICE；新 disciplined clock 不依赖其不安全行为 |
| NanoHTTPD | 2.2.0 | BSD-3-Clause | 本地 HTTP | 是 | 二进制分发保留条款 |
| osmdroid | 6.1.14 | Apache-2.0 | 地图 | 是 | 保留许可证，并单独处理地图数据归属 |
| AndroidX / Material / Compose | Gradle 锁定版本 | Apache-2.0 | Android UI 和架构 | 是 | 依赖报告和 Android notices |
| Kotlin / Coroutines | Kotlin 1.8.10、Coroutines 1.6.4 | Apache-2.0 | Kotlin 和异步状态 | 是 | 与 AGP 7.4.1/Compose compiler 1.4.3 锁定 |
| Room / DataStore / WorkManager | 2.5.2 / 1.0.0 / 2.8.1 | Apache-2.0 | 本地数据、设置、任务 | 是 | 跟踪 schema；普通 DataStore 不保存密码或私钥 |
| LeakCanary | 2.12 | Apache-2.0 | Debug 泄漏检测 | 仅 Debug | 禁止进入 Release |
| Robolectric | 4.10.3 | MIT | JVM 测试 | 否 | 仅测试依赖 |
| Guava | 31.1-jre | Apache-2.0 | 集合工具 | 是 | 保留 LICENSE/NOTICE |
| Google Play services Maps | 18.1.0 | Google APIs Terms | 地图服务 | 是 | 遵守服务条款、API key 与隐私要求，不表述为开源组件 |
| FT8AF | `patrickrb/FT8AF@c2f63e8b` | 顶层 MIT；依赖另议 | clean-room 内存/生命周期参考 | 否 | 不复制应用源码，边界见 `third_party/ft8af-reference` |
| Look4Sat | `rt-bishop/Look4Sat@b17ea2d9` | GPL-3.0 | clean-room 卫星功能参考 | 否 | 阶段 6 只参考功能和公开算法；直接复用前必须切换 GPL 决策 |
| aholinch SGP4 | `aholinch/sgp4@552cb1489a52c3023ae70cb6c7e239e84c5950fe` | Unlicense | TLE 解析和 SGP4 数值传播，Java 源码编译 | 是 | 保留 LICENSE、来源、原始/修补 SHA 和修改说明；只修复全局时区副作用 |
| CelesTrak GP data | 运行时 `gp.php?GROUP=amateur&FORMAT=TLE` | 提供方数据政策 | TLE 在线刷新；离线缓存 | 否（用户缓存） | 明示来源、ETag/更新时间和 stale 状态；不随 APK 打包目录快照 |
| SatNOGS DB transmitters | 运行时 DB API | CC BY-SA 4.0（数据） | 转发器在线刷新；离线缓存 | 否（用户缓存） | UI 显示 SatNOGS 归属；不把运行时数据改称 FT8CN 自有数据 |
| Skyfield | 1.54 + python-sgp4 2.27 | MIT | 阶段 6 独立过境 oracle | 否 | 仅位于 `H:/tools`；wheel SHA 记录于工具链锁，不进入 APK |
| TQSL/tqsllib | 尚未固定 | 待阶段 7 复核 | LoTW 签名候选 | 否 | 未完成前只允许外部 `.tq8` 工作流，禁止未签名 ADIF 直传 |

## 分发边界

- `radio_experimental` 是 FT8CN 自有实验模块，与 WSJT-X core、Hamlib 和卫星实现严格分离。
- FT2 未集成；旧 FT2 预编译归档不在 source manifest、链接 map 或 APK 中。
- Hamlib Android ELF 只保存在本机隔离工具目录作为门禁证据；当前 APK 通过 rigctld 协议和既有 transport 工作。
- LoTW 上传对象必须是 TQSL 数字签名的 `.tq8`，不能把未签名 ADIF 冒充可上传文件。
