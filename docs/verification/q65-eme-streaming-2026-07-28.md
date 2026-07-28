# Q65/EME 流式生产路径验收（2026-07-28）

## 实现边界

- Q65 RX 在录音回调中按实际采样率分块抽取到最终 12 kHz slot 缓冲区。24/48 kHz 源数据不再先组成完整 Java 数组。
- Q65 TX 由官方 core 生成一次 85 个 tone，JNI 按连续相位分块合成；本地音频使用 `AudioTrack.MODE_STREAM`、阻塞写入和 4096-sample 有界队列。
- Q65 A-E 为生产模式；Q65F 在构造器、测试和 UI 三处保持诊断限定。
- FT8/FT4 仍走原有官方 WSJT-X 3.0 core，不经过 Q65 专用流式路径。Java native 全局锁、C bridge mutex 与 Q65 串行 lane 均未改变。

## 内存模型

300 秒、48 kHz、float RX 的旧模型可能同时持有 14,400,000 个源样本（57,600,000 bytes）和 3,600,000 个 12 kHz 样本（14,400,000 bytes），合计 72,000,000 bytes，尚未计 JNI 临时对象。

新模型只常驻：

- 最终 12 kHz slot：3,600,000 float，14,400,000 bytes；
- Java/AudioRecord 源块：最多 4096 float，16,384 bytes；
- JNI 重采样输入/输出 scratch：各 4096 float，共 32,768 bytes；
- 固定大小 FIR history/phase。

因此已消除完整 48 kHz 源数组，源侧额外工作区从约 57.6 MB 降为约 49 KB（Java chunk 加 JNI 双 scratch）。最终 12 kHz decoder frame 仍按协议容量保留，没有削减解码时窗。

Q65A/300、48 kHz TX 实测生成 14,100,480 个样本。旧完整 float 波形需 56,401,920 bytes，另可能存在完整 PCM/AudioTrack static 副本；新路径只持有 4096-float Java chunk、可选 4096-short PCM chunk和 4096-float native scratch，且支持 chunk 边界取消和 PTT 回滚。

## 正确性与真机证据

`scripts/verify.ps1 -ReportPath .tmp_verify_run/stage5-verification-final.json`：

- 最终状态：`HOST_RC_PASS`、`DEVICE_RELEASE_PASS`、`BLOCKED_SANITIZER`。
- FT8：20 条，`de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3`，host p50/p95 521.562/537.412 ms。
- FT4：16 条，`877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14`，host p50/p95 258.660/263.437 ms。
- Q65A/60：4 条，`76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de`，host p50/p95 229.647/232.733 ms。
- 官方 `jt9`：FT8 20/20、FT4 16/16，消息多重集合、频率和 DT 严格通过。
- 真机：arm64、Android 16，Debug/Release 在 12/24/48 kHz 下结果数和完整结果哈希一致；未记录设备序列号。
- 300 秒 48 kHz RX：输入 chunk 4096，最终输出 3,600,000；Debug/Release 分别约 4644/473 ms 完成容量测试。
- 300 秒 48 kHz TX：chunk 4096，总样本 14,100,480；Debug/Release 分别约 197/91 ms 完成分块生成测试。
- 7 项 Q65 流式 instrumentation 在 Debug/Release 均通过，包括分块与一次性重采样逐 bit 一致、容量、Nyquist、Q65F 拒绝及流式/既有官方波形逐样本比较。

设备完整 Debug 矩阵会持续约 8 分钟并使后续 Release 尾延迟受设备调频影响。冷态 10 次与持续 30 次 FT8 重测的 p50/p95 分别约 595/682 ms 和 772/790 ms，同一 APK、同一结果哈希下波动明显；因此不将真机尾延迟描述为本阶段性能提升。host O2 回归和完整设备结果一致性均通过，阶段 9 将以固定温度/电源状态重新建立最终发布表。

## EME Doppler

`EmeDopplerCalculator` 明确实现并测试 WSJT-X 3.0.2 `astro.cpp` 的三种校正组合：Full DX、Own Echo、Constant Frequency on Moon。range rate 为正表示距离增加；校正符号和单程/双程关系由 JVM 测试固定。

当前 `MoonEphemeris` 仍是显示用途的紧凑低精度模型，没有把它冒充高精度天文 oracle。已完成校正组合和符号验证；与权威 JPL/WSJT-X 指定时刻的高精度方位、距离和 range-rate golden 对照记为 `BLOCKED_EME_EPHEMERIS_ORACLE`，将在卫星/天文统一 golden corpus 中补齐。

## 明确限制

- `BLOCKED_SANITIZER`：本机 MSYS2 未发现可运行的 host ASan/UBSan runtime；普通严格 host CTest 和真机门禁均已执行。
- `BLOCKED_Q65_EXTERNAL_STREAMING`：现有外部网络/CAT wave transport 没有有界流式协议。为避免重新分配完整 Q65 波形，该组合现在明确拒绝，不会静默退回双 buffer；本地 AudioTrack 生产路径已流式完成。
- Q65F 仍不进入生产 UI/TX。
