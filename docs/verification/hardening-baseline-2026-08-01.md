# FT8CN 加固基线（2026-08-01）

## 仓库与工作区

- 仓库：`H:/iu_yang1/study/FT8CN/ft8cn`
- 分支：`wsjtx-ft8ft4-core-port`
- 起始本地/远程 SHA：`4cae0d5b7a071a7404a674dcb6e43ec81ed25a7c`
- ahead/behind：`0/0`
- 仓库外备份：`H:/tools/ft8cn-backups/20260801-160056`
- 本地备份分支：`backup/wsjtx-hardening-20260801-160056-4cae0d5`
- 起始索引：1679 个 tracked、6 个 modified、0 个 staged、0 个 untracked、66 个 ignored。
- 用户工作区资产：`app/build.gradle` 的 `versionName b4 -> b5` 以及五处 UI 文案/测试调整，均保留并按后续阶段语义提交。

四个含嵌套 `.git` 的忽略目录已完整移到
`H:/tools/ft8cn-work/repo-inspection-20260801-1615`。官方 WAV 仅以忽略的
`.tmp_wsjtx/samples` 本地语料缓存保留；没有删除样本、上游源码或用户文件。

## 工具与来源

`scripts/check-toolchain.ps1` 通过。实际工具为 JDK 17.0.19、Gradle 7.5、
Android NDK 26.1.10909125、Clang 17.0.2、CMake 3.22.1、Ninja 1.10.2、
Flang 22.1.5、ADB 1.0.41；可执行文件完整 SHA256 继续由
`docs/implementation/toolchain-lock.md` 固定。

只读上游复核：

- FT8AF `main`：`c2f63e8b37fcd484fd2eb2049494425dd2414971`，与既有内存审查 pin 相同。
- Look4Sat `main`：`f9806d7f7f2ad386b1dfac049d83845dcb6d9d47`；产品仍使用既有 clean-room 边界，没有复制 GPL 源码。
- Hamlib `master`：`568b6c1cdefd4892f0ef28434a3cc4c7a03bfbba`；产品继续固定已验证的 `c7fb0fa1`，不在加固期间静默升级。

`scripts/check-third-party.ps1` 通过：15 个登记组件、87 个唯一 WSJT-X
构建输入、CycloneDX 1.5 清单一致。

## 正确性与性能基线

执行：

```powershell
scripts/check-toolchain.ps1
scripts/verify.ps1 -ReportPath H:/tools/ft8cn-hardening-baseline-20260801.json
```

最终状态：`HOST_RC_PASS`、`DEVICE_RELEASE_PASS`、`BLOCKED_SANITIZER`。

| 模式 | 结果数 | 完整结果 SHA256 | host p50/p95 |
|---|---:|---|---:|
| FT8 | 20 | `de6b3e97a8d3d07aa0b40d1ce9f5a82012a99e28ee6268ad4e0c486328970cc3` | 521.952 / 545.190 ms |
| FT4 | 16 | `877dd38b0d05c754d31c7dd3b0610e61489f86d1cb316123012b9b8c148d1d14` | 259.031 / 267.602 ms |
| Q65A/60 | 4 | `76d34ece748e5889f7fab5bd78d05c34baa206bd55de926e53cf3a403ed7b9de` | 217.604 / 221.298 ms |

官方 WSJT-X 3.0.1 `jt9` 对 FT8 20/20、FT4 16/16 的消息多重集合、频率和
DT 严格匹配。真机为 Android 16、arm64-v8a、8 logical CPU；Debug/Release
在 12/24/48 kHz 结果一致，FT8 sync 使用 2 个线程。Q65 300 秒 RX/TX 继续
使用 4096-sample 有界块。

## 仓库卫生

- Git symlink、submodule、LFS pointer、tracked native build output：0。
- 仓库内嵌套 `.git`：清理后仅根 `.git`。
- tracked JAR/AAR 仅为 Gradle wrapper 和明确登记的运行时依赖；没有无来源
  的 APK、ELF、object 或静态归档。
- `.tmp_wsjtx_lib_inspect` 未跟踪、未暂存且不存在于索引。
- sanitizer runtime 仍不可用，保留 `BLOCKED_SANITIZER`，不得用普通 CTest
  冒充 sanitizer PASS。
