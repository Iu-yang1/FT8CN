# Gated FT8 OSD 优化原型设计

## 不可变化的基线

当前热点基线已经冻结：

- OSD 占 LDPC 总耗时约 94%–96%
- order-1 search 占 OSD 约 69.6%–70.7%
- Gaussian elimination 占 OSD 约 27.5%–28.4%
- 失败 OSD 总耗时占各 pass 的 91.94% / 96.02% / 99.17%
- 排序、分配、CRC 和矩阵复制不是主要热点

OSD calls 是 `osd174_91()` 的实际调用次数，不等于候选数或 LDPC calls。任何优化不得通过
减少候选、pass、灵敏度、OSD 深度或 test pattern 数量换取速度。

## 候选方案审查

### 缓冲区与矩阵复用

每次 OSD call 都会分配工作数组，但 tracing 显示 allocation/init 耗时极小。跨 call 复用还会
引入共享可变状态和未来 native 并行风险，因此本轮不实现。

`gen` 已经是 `allocatable, save`，首次调用后复用。Gaussian elimination 的输入列顺序取决于
每次 call 的 reliability sort，消元结果不能安全跨 call 缓存。

### Gaussian elimination

pivot search、列交换和行消元位于同一紧密循环。可以后续研究位打包或减少整行 copy，但这会
显著改变实现结构，需要更广的等价性测试。本轮只保留设计，不实现。

### Order-1 重复 encode 复用

order-1 搜索中，当 `n1 == iflag` 时，代码已经执行：

`mrbencode91(me, ce, g2, N, k)`

随后若 `nd1kpt <= ntheta`，原路径会对完全相同的 `me`、`g2`、`N`、`k` 再调用一次
`mrbencode91()`。两次调用之间没有修改这些输入，也没有修改 `ce` 所依赖的状态。

因此最低风险原型是：实验开关开启时，仅在该分支复用第一次计算的 `ce`；其他分支、pattern、
搜索顺序、距离计算和成功判定保持原样。

## 实验开关

- Android property：`log.tag.FT8OSDExperimental`
- 开启：`adb shell setprop log.tag.FT8OSDExperimental DEBUG`
- 关闭：`adb shell setprop log.tag.FT8OSDExperimental WARN`
- 默认：关闭

开关在进程首次读取后缓存，A/B 测试必须在修改 property 后重启应用进程。

## 准入与回退

原型只有在以下条件全部满足时才保留：

1. 默认关闭路径保持 FT8 `20/20/20/20`
2. 实验开启路径保持 FT8 `20/20/20/20`
3. FT4 保持 `16/16/16/16`
4. Q65A/60s 保持 `1/1/1/1`
5. tracing-off 无 OSD/LDPC/FT8b 日志
6. Java `nativeBatchDecodeLock`、C bridge mutex、`g_active_context` fallback 和
   `PARALLEL_NATIVE` 拒绝策略保持不变

即使 A/B 显示性能改善，也不得基于单一样本默认开启。

## 高风险方案

失败 OSD fast-reject、减少 test pattern、降低 `norder/maxosd`、改变搜索顺序或 CRC/距离阈值
都可能降低多信号解码能力，本轮不实现。
