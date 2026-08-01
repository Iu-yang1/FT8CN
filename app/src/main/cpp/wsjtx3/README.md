# WSJT-X 3.0 mobile core

本目录承载 FT8CN 的官方 WSJT-X 3.0 接收后端。

## 边界

- `vendor/wsjtx-3.0.0` 保留上游源码。
- `wsjtx3-sources.manifest` 是 host 与 Android 唯一的 source manifest。
- `host/wsjtx3_bridge.f90` 将 FT8、FT4、Q65 回调映射为稳定的 C ABI。
- FT8/FT4 搜索范围固定为 0-3000 Hz；Q65 为 0-5000 Hz。
- `input_is_live` 决定 vendor 的 `ldiskdat`：实时输入为 false，文件诊断为 true。
- QSO/TX 频率、pass、round、灵敏度、early、wideband 和 LDPC 深度都在请求创建时快照；native 处理中不读取变化中的 UI 全局值。

## 并发

当前 bridge callback 通过全局活动 context 回传，Q65 上游仍包含 Fortran unit 状态。即使诊断 callback slot 可用，也不能宣称多个 native decode 请求可并行。Android 必须保留 Java native lock、C mutex 和 Q65 独立串行 lane，`PARALLEL_NATIVE` 继续禁用。

FT8 仅在单个官方 decode 请求内部并行 `sync8` 的独立频率行。运行时根据 Android CPU capacity/max-frequency 识别性能簇，保留至少一个在线核心给录音和 UI，并将同步搜索限制为最多两个线程。候选归并、排序、`ft8b`、LDPC/OSD、subtract 和 callback 均保持确定性的串行顺序。该内部 OpenMP 区域不放宽上述请求级锁，也不用于 FT4/Q65。

FT4 的同类频率行 OpenMP 实验在真机上慢 21%-29%，因此没有保留；当前只复用与 `idf` 对应的 Costas 模板。Q65 继续使用独立串行 lane。

## Q65

- 正式能力：Q65A-E，15/30/60/120/300 秒。
- Q65F：只用于诊断兼容性测试，不进入正式 UI/TX。
- 波形容量只由 TR period 和 sample rate 决定，与 submode 无关。
- averaging 状态属于持久 session，仅在会话创建、显式 reset、目标/模式变化或采样间断时清除。
- 移动端关闭上游 Q65 结果文件输出；scratch unit 仅用于满足未使用的接口约束。
- `common/resampler` 已提供固定工作区的有状态分块抽取，并通过 12/24/48 kHz 任意 chunk 边界逐 bit 等价测试。
- 生产 RX 已由录音 chunk 直接写入最终 12 kHz slot；300 秒、48 kHz 不再创建完整高采样率源数组。
- 生产 TX 使用连续相位 JNI 分块合成和 `AudioTrack.MODE_STREAM`，Java/native 工作块均限制为 4096 samples。外部网络/CAT wave transport 尚无等价的有界流协议，因此该组合明确拒绝并记录为 `BLOCKED_Q65_EXTERNAL_STREAMING`。

## 构建与测试

Host：

```powershell
powershell -ExecutionPolicy Bypass -File host/build_host_probe.ps1 -BuildType Release -Optimization O2
ctest --test-dir host/build-release-o2 --output-on-failure
```

Android：`android/build_wsjtx3_android_core.ps1` 从 manifest 生成带完整输入指纹的静态库。对象名包含相对路径哈希；源码、编译器版本、target、ABI、flags 或 patch 变化都会触发重建。Flang runtime patch 在隔离 workspace 应用，不修改工具目录中的 LLVM 源码。

Release 默认使用 `-O2 -DNDEBUG`。`-O3` 在相同语料上结果一致但 p95 更慢，因此未选用。默认禁用 fast-math、CPU 专用指令和 LTO。
