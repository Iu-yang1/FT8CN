# 阶段 0 功能升级基线 - 2026-07-26

## 仓库与保护

- 分支、本地 HEAD、远程 HEAD：`wsjtx-ft8ft4-core-port` / `10b2c62e4c75021eb559825c3da85f77b10a052d` / `10b2c62e4c75021eb559825c3da85f77b10a052d`。
- 领先/落后：`0/0`；仅执行只读 fetch，没有 push、rebase、merge 或远程修改。
- 用户原有工作区：`app/build.gradle` 的 `versionName ver0.7-WSJT-Lib-b2 -> b3`，未还原、未暂存。
- 仓库外保护：`H:/tools/ft8cn-backups/20260726-182508-10b2c62/` 中保存该 diff 和分支 bundle。
- symlink/submodule/LFS pointer：0/0/0；tracked APK、`.so`、对象、CMake/Ninja 输出：0。
- 主仓库没有 Git link。已忽略 probe 目录中存在 4 个嵌套 Git checkout，按安全约束保留，不属于主仓库索引。

## 语料与 oracle

大型 WAV 不进入 Git；以下文件仅记录相对本地路径与 SHA256：

| 模式 | 文件 | SHA256 | 结果 | 完整结果 SHA256 |
|---|---|---|---:|---|
| FT8 | `.tmp_wsjtx/samples/FT8/210703_133430.wav` | `9feb99c275770a6618538026da7decc6b09eb6cf63121e5168fa86dcdf00c2f5` | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` |
| FT4 | `.tmp_wsjtx/samples/FT4/000000_000002.wav` | `d9e91fa04ba138a7b9f41b4103823c77ca1c3a9775101f6b14d60935bcd3813b` | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` |
| Q65A/60 | `.tmp_wsjtx/samples/Q65/60A_EME_6m/210106_1621.wav` | `7ece98b1a0c3593b054c6309c4e05fbf65517fef4a21c7a5ddac6d3b2dbf0ce8` | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` |

官方 WSJT-X 3.0.1 `jt9` 对 FT8/FT4 按消息多重集合、3.2 Hz 频率容差、0.06 秒 DT 容差严格比较：20/20 与 16/16，逐条 PASS。`test-corpus.json` 中指定消息使用更严格的 0.11 Hz/0.011 秒门禁并通过。

## 稳态基线

Host Release `-O2 -DNDEBUG`，关闭 tracing，1 次预热后连续 20 次；结果数与完整哈希每次一致：

| 模式 | p50 / p95 | 峰值 working set / private |
|---|---:|---:|
| FT8 | 513.474 / 516.580 ms | 27,209,728 / 71,057,408 B |
| FT4 | 258.967 / 266.237 ms | 16,592,896 / 69,332,992 B |
| Q65A/60 | 212.572 / 222.730 ms | 49,225,728 / 98,533,376 B |

阶段开始前已有同设备 Release 证据：FT8 CPU p50 178.48%，FT4 91.27%，Q65 97.31%；Java/native/PSS/RSS 峰值分别为 FT8 21.3/41.8/89.1/238.6 MB、FT4 9.5/46.6/92.9/242.1 MB、Q65 10.8/120.4/173.2/322.2 MB。当前 ADB 未连接，因此没有把旧设备数据冒充本轮重测。

## 门禁结果

- `scripts/check-toolchain.ps1`：PASS。
- strict Release O2 host CMake/Ninja 与 CTest：1/1 PASS。
- codec、CRC/LDPC、pack/unpack、synthetic FT8/FT4、resampler、OSD、Q65 capacity/averaging/TX-RX：PASS。
- Gradle unit、Debug APK、Release APK、internal androidTest APK：PASS。
- 官方 `jt9` cross-oracle：PASS。
- `BLOCKED_DEVICE`：本轮无授权 ADB 设备，未执行冷启动、前后台、20 live slot 与设备内存矩阵。
- `BLOCKED_SANITIZER`：当前 MSYS2 UCRT/GFortran 缺少 ABI 兼容 ASan/UBSan runtime。
- `BLOCKED_Q65_STREAMING`：有状态分块 resampler 已验证，但生产 RX capture 和 TX 仍是完整 slot 路径。

机器可读原始数据保存在忽略目录 `.tmp_phase0/`；不会提交样本、APK、日志或工具输出。
