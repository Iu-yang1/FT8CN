# FT8CN / FT8AF 内存与生命周期联合审查

## 固定范围

- FT8CN：`10b2c62e4c75021eb559825c3da85f77b10a052d`。
- FT8AF：`c2f63e8b37fcd484fd2eb2049494425dd2414971`（main，2026-07-07）。
- FT8AF 内嵌 ft8_lib pin：`6f528128ee3ebf4d08ba2313f6c5d3913eda5608`。
- 审查目标：录音生命周期、level meter、JNI 数组、slot 分配、resampler、waterfall/candidate/workspace；不是比较哪一个 decoder “更现代”。

## 可直接借鉴

| 模式 | FT8AF 证据 | FT8CN 现状 | 执行方向 |
|---|---|---|---|
| exactly-once completion | `HamRecorder` 使用 `AtomicBoolean completed`、buffer lock 和 stall watchdog | `HamRecorder` 缺少一次完成语义，录音中断可能留下半活状态 | 在 Q65 流式接线前加入 session token、一次完成与取消门禁 |
| listener 并发 | monitor 使用 `CopyOnWriteArrayList` | 每个 callback `new ArrayList<>(listeners)`，add/remove 未同步 | 使用快照友好的并发容器，消除每 callback 列表分配 |
| audio level | 固定 3000 样本窗口、原地增益、限制 UI 更新率 | level 与 slot copy 混合，存在额外遍历/对象 | 固定有界 meter，不把 PCM 放入 UI state |
| workspace 生命周期 | decoder/scratch 与 context 绑定并明确释放 | official core 已有 context；Java Q65 仍持完整 slot | scratch 继续复用，页面/取消时释放 direct/ring buffer |
| bounded data | waterfall 和 candidate 有协议上界 | official core 自身有候选边界，结果再做安全边界 | 只对接口/展示做上界报告，不削减官方搜索 |

## 只能借鉴思想

- FT8AF 使用量化 waterfall 和轻量候选结构，适合其 kgoba decoder；FT8CN 可借鉴固定容量和数据布局，但不能直接替换 official WSJT-X soft metrics、OSD、subtract 或 AP 流程。
- FT8AF JNI 使用 `GetFloatArrayElements`/`JNI_ABORT` 避免回写；FT8CN 对短 FT8/FT4 slot 可继续使用受控数组，Q65 长周期应改为 direct/ring chunk，避免 pin/copy 整个 300 秒输入。
- FT8AF resampler 仍会分配 mono/output 完整临时数组，不适合作为 Q65 300 秒生产实现。FT8CN 已有小于 2 KiB 状态的分块 FIR，应接入 AudioRecord。
- FT8AF 的 remainder 递归会创建新数组；FT8CN 应采用 offset/slice/ring index，不复制尾部。

## 禁止整体替换

FT8AF/kgoba 接收器更轻量，但替换会改变弱信号灵敏度、拥挤信道、AP、OSD、subtract 与官方 oracle 行为。FT8CN 的 FT8/FT4/Q65 RX 必须继续使用裁剪后的 official WSJT-X 3.0 core；允许的内存优化只能发生在录音、重采样、JNI、结果/UI 和经数学等价证明的核心热路径。

## FT8CN 当前高风险路径

1. `HamRecorder.VoiceDataMonitor` 按真实采样率预分配完整 slot；Q65 300 秒/48 kHz 为 14,400,000 float，约 57.6 MB。
2. 生产 Q65 重采样还会生成 3,600,000 float 的 12 kHz 输出，约 14.4 MB，形成至少 72 MB 双持有，不含 JNI/Fortran workspace。
3. Q65 TX 仍可能同时持有完整 Java waveform、JNI/native waveform 与 `AudioTrack.MODE_STATIC` buffer。
4. recorder callback 每次复制 listener list；slot remainder 逐样本/递归数组处理会制造 GC 压力。
5. Compose 迁移后禁止把 PCM、waterfall 全帧或 native handle 放入 `StateFlow`。

## 可执行验收

- 阶段 1：核心 state 只包含小型摘要，fake controller 可注入。
- 阶段 5：Q65 AudioRecord chunk→有状态 12 kHz ring；48 kHz/300 秒不再出现 57.6+14.4 MB Java 双 buffer。
- 阶段 5：TX chunk generator→有界队列→`AudioTrack.MODE_STREAM`，停止能在 chunk 边界生效。
- 阶段 8：listener、meter、remainder、JNI direct buffer 与页面生命周期统一回归。
- 所有优化继续要求 FT8/FT4 固定结果哈希、官方 oracle 和 p95 门槛，不以减少搜索换内存。
