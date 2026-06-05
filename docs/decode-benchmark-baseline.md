# FT8 / FT4 / Q65 解码性能基线

## 基线用途

本文件记录 2026-06-05 在真实 Android 设备上的解码结果，作为后续性能优化的正确性门槛。
优化不得以减少 raw、merged、native batch 或 Java published 结果数量为代价。

运行指标仅保存在 Logcat 和内存状态中，不应长期写入手机磁盘。

## 当前真实设备基线

| Case | Raw / merged / native / published | 耗时 | 备注 |
| --- | --- | --- | --- |
| FT8 multi sample | 20 / 20 / 20 / 20 | 约 7.27s | 当前实时风险最高 |
| FT4 multi sample | 16 / 16 / 16 / 16 | 约 1.51s | 无链路丢失 |
| Q65A/60s real sample | 1 / 1 / 1 / 1 | 约 306ms | 解出 `W7GJ W1VD FN31` |
| Q65F/60s generated sample | 1 / 1 / 1 / 1 | 约 9.94s | 当前 Q65 最慢路径 |

Q65A 的 15/30/60/120/300s 和 Q65B/C/D/E/F 的 60s 参数链路均已通过生成样本验证。
Q65A/300s 的 `expectedSamples` 必须为 `3600000`。

## 设备测试边界

ColorOS 可能在应用退到后台后终止较重的 Q65 诊断前台服务。这属于系统后台策略，不代表
decoder chain 失败。运行 Q65E/F 或长周期 Q65 diagnostics 时，应保持应用在前台。

## 回归门槛

- FT8 multi sample 不低于 `20/20/20/20`。
- FT4 multi sample 不低于 `16/16/16/16`。
- Q65 已验证 case 保持 `1/1/1/1`。
- Q65 不运行 early decode 或 deep supplement。
- `PARALLEL_NATIVE` 保持禁用，native batch decode 保持串行保护。
- 新增计时和诊断不得显著增加总解码耗时。

## Timing 字段

Listener 的 `decodeBenchmark` 将总耗时拆为：

- `queuedMs`：排队等待。
- `prepareMs`：重采样和输入准备。
- `nativeHandleMs`：长期 native decoder handle 获取或重建。
- `nativeLockWaitMs`：等待 Java native batch 串行锁。
- `decoderProcessMs`：完整 JNI batch 调用。
- `resultGetterMs`：raw/merged count getter。
- `javaMessagePostMs`：Java 消息元数据补充。
- `dedupeMs`：Java slot 去重。
- `callbackMs`：上层 listener/UI 回调。

Debug 构建中的 `FtxJniBenchmark` 进一步拆分 JNI batch 的 `setupMs`、`inputMs`、
`coreMs` 和 `resultObjectMs`。
