# 阶段 8：Compose 工作台与有界 UI/录音内存

## 实现边界

- 新工作台采用 Material 3，提供 Call、EME、Satellite、Logbook、Radio、Settings
  六个独立入口；手机使用抽屉，宽屏使用 NavigationRail，避免把六项压入底栏。
- Call 页面只展示 FT8/FT4、15/7.5 秒周期、时钟健康状态和自动发射门禁；Q65
  继续位于独立 EME 页面。完整旧操作能力通过白名单显式 Intent 打开，未导出任意测试入口。
- 默认 launcher 暂时仍是兼容 `MainActivity`。把全部业务页面和编排迁到 Compose 尚未完成，
  因此记录 `BLOCKED_COMPLETE_COMPOSE_MIGRATION`，不以隐藏旧页面换取形式上的完成。
- 主题使用青绿色/琥珀色语义、动态色、明确的 serif 标题层级和 10/18/28 dp 圆角，
  不沿用默认紫色模板。

## 内存与生命周期

- `MicRecorder` 以 `AtomicBoolean` 和具体 `AudioRecord` 实例共同标识会话。停止后立即重启时，
  旧线程不能再清除新会话状态或释放新 recorder；缓冲区只在每个录音线程创建一次。
- `HamRecorder` 使用 `CopyOnWriteArrayList` 保存低频增删的 monitor，音频热路径不再为每个
  block 复制监听器列表；一次性 monitor 完成后仍按原语义移除。
- 频谱固定复用 640-bin 显示数组；12/24/48 kHz 均保持 0–3000 Hz 坐标。
  `ColumnarView` 复用 `Rect[]`，瀑布和柱状图 bitmap 在尺寸变化/脱离窗口时回收，重新挂载时重建。
- Compose state 只包含小型不可变状态和分页/摘要，不持有 PCM、waterfall 全数组或 native 指针。
- Q65 生产流式边界保持阶段 5 的 4096-sample RX/TX chunk。48 kHz、300 秒 RX 只持有
  4096-sample 源块和最终 3,600,000-sample 12 kHz slot，不持有 14,400,000-sample 源数组；
  TX 使用 `AudioTrack.MODE_STREAM`，不构造完整 Java 波形。

## 正确性与性能

Host O2（1 次预热、20 次计时）：

| 模式 | 结果 / SHA256 | 阶段 7 p50/p95 | 阶段 8 p50/p95 | p95 变化 |
|---|---|---:|---:|---:|
| FT8 | 20 / `de6b3e97...70cc3` | 522.145/548.953 ms | 523.227/530.964 ms | -3.277% |
| FT4 | 16 / `877dd38b...d1d14` | 261.002/269.214 ms | 260.421/271.920 ms | +1.005% |
| Q65A/60 | 4 / `76d34ece...b9de` | 221.922/233.311 ms | 218.259/218.943 ms | -6.158% |

真机 Release（realme RMX5062、Android 16、arm64-v8a、8 processors，1 次预热、10 次计时）：

| 模式 | 输入率 | 结果 | p50/p95 | CPU p50/p95 | Java/native/PSS/RSS 峰值 |
|---|---:|---:|---:|---:|---:|
| FT8 | 12 kHz | 20 | 497.734/566.381 ms | 181.185/195.500% | 20.0/43.5/103.0/262.3 MB |
| FT8 | 24 kHz | 20 | 484.203/496.265 ms | 186.906/195.000% | 26.4/44.9/116.4/267.7 MB |
| FT8 | 48 kHz | 20 | 488.903/560.001 ms | 182.867/190.784% | 15.4/44.6/105.2/263.6 MB |
| FT4 | 12 kHz | 16 | 215.751/232.953 ms | 90.514/110.226% | 16.3/49.0/112.8/270.6 MB |
| Q65A/60 | 12 kHz | 4 | 147.796/160.030 ms | 90.423/95.051% | 8.6/121.3/184.6/342.7 MB |

- Debug/Release 各 88 项 JVM 测试、Debug/Release APK、androidTest APK 和 release lint：PASS。
- Host CTest、固定语料、FT8/FT4 官方 `jt9` 消息多重集合/频率/DT：PASS；完整结果哈希不变。
- 真机 Debug/Release、12/24/48 kHz、Q65 300 秒流式共 7+7 项：PASS。
- 最终 verify 状态：`HOST_RC_PASS`、`DEVICE_RELEASE_PASS`、`BLOCKED_SANITIZER`。

## 未完成门禁

- `BLOCKED_DEVICE_UI_UNLOCK`：设备有安全锁屏。`ModernShellActivity` 能启动且无 FATAL/ANR，
  但 Oplus `AppStartConfirmDialogActivity` 会拦截 AndroidX ActivityScenario；Compose UI 与导航
  benchmark 已编译，无法在无人解锁条件下完成交互和 p50/p95，不记录为 PASS。
- `BLOCKED_BASELINE_PROFILE_GENERATION`：AGP 能合并 ART profile，但在上述真实页面 benchmark
  未完成前不生成无数据依据的应用 profile。
- `BLOCKED_COMPLETE_COMPOSE_MIGRATION`：现代工作台可用，但默认 launcher 和少量完整操作页仍为
  Java/XML 兼容实现；本阶段没有删除稳定功能，也没有虚报“全部页面已迁移”。
- `BLOCKED_SANITIZER`：本机仍缺少与当前 Windows/Fortran host 链兼容的 ASan/UBSan runtime。
