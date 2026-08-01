# FT8CN modifications

相对官方 `v3.0.0`，FT8CN 对被编译的 vendor 文件做了移动端移植和经 golden/oracle 门禁保护的优化，主要包括：

- C-compatible bridge、Android Flang/KISS FFT 适配和 0-3000 Hz FT8/FT4 搜索边界；
- FT8 `sync8` 频率行的确定性 1-2 线程自适应并行；
- FT4 Costas 模板复用，FT4 仍串行；
- OSD/subtract/downsample 热路径的数学等价优化；
- Q65 移动端文件 I/O 隔离、容量和 averaging 生命周期修复；
- 请求上下文、QSO/TX 频率、live/disk 标志和结果回调接入。

完整差异以 `git diff ab976b1` 不适用于跨仓库，因此以导入提交 `b6acf183` 到当前提交的 vendor 路径历史为权威修改记录。任何后续修改都必须继续通过官方 `jt9`、固定结果哈希、OSD 等价、纯噪声和语料门禁。
