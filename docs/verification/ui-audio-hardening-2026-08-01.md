# Compose UI 与音频输入发布验证（2026-08-01）

## 修改范围

- 录音配置加载时，只有采样率真实变化才重建 AudioRecord；录音意外停止且权限有效时由 ViewModel 恢复。
- AudioRecord 优先 PCM float，不支持时回退 PCM16，并在录音线程的固定缓冲区内转换为 float，不产生逐块数组分配。
- `bands.txt` 使用 256 KiB 上限的分块读取，损坏单行不会清空整个常用频率列表。
- Hamlib 型号选择器使用有界对话框和 `LazyColumn`，保留 native 返回的完整型号目录并支持搜索。
- 顶部槽位进度以 10 Hz 更新；录音、发射和导入错误持续可见，一般诊断信息四秒后收起，减少内容区挤压。
- 设置页去除重复的常用操作卡；工作频率、发射音量和网格追踪继续由右侧浮动栏提供。

## 真机功能门禁

- 启动后只出现一次 12 kHz AudioRecord 初始化，没有相同采样率导致的二次停录/重启。
- 频谱收到 1920 点输入，FFT 为 6.25 Hz/bin，只渲染 0–3000 Hz 的 480 个 bin。
- 八个底部入口和“当前模式录音槽位进度”均可由无障碍树访问。
- 旧日志统计按钮可打开统计页，等待 Compose 顶层重组后仍停留在统计页。
- native Hamlib 型号目录不少于 50 条、型号 ID 无重复，并包含 Icom、Yaesu、Kenwood；UI 使用懒加载，不截断目录。
- Android Gradle UTP 在本机受 protobuf 版本冲突影响会在 0 tests 前中止；同一已构建 test APK 改由 `adb am instrument` 运行，3 项 UI/Hamlib 测试全部通过。项目自带设备 benchmark 不依赖该失败路径并完整通过。

## 编解码回归

| 模式 | 数量 | 完整结果 SHA256 | host p50/p95 |
|---|---:|---|---:|
| FT8 | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` | 523.480 / 535.973 ms |
| FT4 | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` | 260.136 / 269.125 ms |
| Q65A/60 | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` | 230.326 / 233.704 ms |

FT8/FT4 与官方 WSJT-X 3.0.1 `jt9` 的消息多重集合、频率和 DT 在 0–3000 Hz 范围内逐条匹配。相对固定参考 p95（543.117/269.472/268.751 ms）均无回退。

## 设备与长周期内存

- 设备：Android 16、arm64-v8a、8 核；报告不保存设备序列号。
- Debug/Release 的 FT8、FT4、Q65 在 12/24/48 kHz 下分别保持 20、16、4 条结果，Debug/Release 无结果差异。
- Release 12 kHz p50/p95：FT8 521.086/608.213 ms，FT4 259.205/281.501 ms，Q65A/60 168.365/221.537 ms。
- Q65 300 秒、48 kHz RX：源块 4096 点，最终 12 kHz 数据由 native 持有，Java 最终数组 0 点；Release Java heap 增量 151696 bytes、native heap 增量 14704384 bytes。
- Q65 300 秒 TX：4096 点有界流式块，Release 生成/送流门禁耗时 88 ms，不构造完整 Java 波形。

## 执行命令与状态

- `gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug`
- `adb am instrument ...ModernShellUiTest`
- `adb am instrument ...NativeHamlibCatalogInstrumentedTest`
- `scripts/verify.ps1 -JavaHome H:/tools/jdks/jdk-17.0.19+10 -ReportPath H:/tools/ft8cn-stage8-verification-20260801.json`

最终状态：`HOST_RC_PASS`、`DEVICE_RELEASE_PASS`、`BLOCKED_SANITIZER`。阻塞原因是当前 MSYS2 未找到与 host 混合 C/Fortran 构建兼容的 ASan/UBSan runtime，未冒充 sanitizer PASS。
