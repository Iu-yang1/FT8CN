# FT8 LDPC 内部热点分析（2026-06-06）

## 目标与边界

本轮只细分 `decode174_91()` 内部耗时，没有修改候选数量、pass、round、灵敏度、
BP 迭代上限、OSD 深度、CRC 判断或成功条件。

诊断由 `adb shell setprop log.tag.WSJTX3Phase DEBUG` 开启，只输出每个 FT8 pass
一条聚合 `ldpcTrace` 到 Logcat。关闭 tracing 后不计时、不输出日志。FT4 等未进入
FT8b 聚合上下文的调用也不会启用该细分计时。

## 聚合阶段

- `setupUs`：`decode174_91()` 初始化和初始 check message 设置
- `bpLlrSyndromeUs`：BP LLR 更新、hard decision、syndrome 和 CRC 检查
- `bpBitToCheckUs`：bit-to-check message 更新
- `bpCheckToVarUs`：check-to-variable message 更新
- `osdUs`：`osd174_91()` 调用
- `otherUs`：上述阶段外的少量控制与收尾

## 设备样本结果

样本：`210703_133430.wav`

配置保持原样：FT8 native diagnostic，pass=2，round=1，deep=false。

| Pass | LDPC calls | BP iterations | OSD calls | BP success | OSD success | LDPC total | OSD | OSD 占比 | BP 三段占比 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 82 | 1381 | 139 | 10 | 6 | 2,757,454 us | 2,595,392 us | 94.12% | 5.74% |
| 2 | 64 | 876 | 118 | 3 | 4 | 2,286,937 us | 2,186,659 us | 95.62% | 4.27% |
| 3 | 64 | 921 | 127 | 0 | 1 | 2,352,024 us | 2,246,708 us | 95.52% | 4.37% |

BP 内部最大的阶段是 `bpCheckToVarUs`，但它只占每个 pass 的约 3.2%–4.4%。
当前样本中，OSD 才是 `decode174_91()` 的决定性耗时来源。

## 回归结果

- tracing 开启：FT8 `20/20/20/20`，FT4 `16/16/16/16`
- tracing 关闭：FT8 `20/20/20/20`
- tracing 关闭时：`ldpcTraceCount=0`、`ft8bTraceCount=0`
- FT4 在全局 phase tracing 开启时不产生 FT8 LDPC 细分日志

## 下一步

下一轮应只读审查并细分 `osd174_91()`，重点区分：

1. generator matrix 首次初始化
2. reliability sort 与 generator matrix 重排
3. Gaussian elimination
4. order-0 encode
5. order-1/高阶 test pattern 搜索
6. second preprocessing rule

在得到 OSD 内部聚合数据前，不应减少 OSD 调用、降低 `norder/maxosd`、削减候选，
或调整 pass/灵敏度来换取性能。
