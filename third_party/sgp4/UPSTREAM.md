# aholinch/sgp4

- 仓库：https://github.com/aholinch/sgp4
- 固定提交：`552cb1489a52c3023ae70cb6c7e239e84c5950fe`
- 获取日期：2026-07-28
- 许可证：The Unlicense
- 上游范围：`src/java/TLE.java`、`src/java/SGP4.java`、`src/java/ElsetRec.java`
- 构建方式：作为独立 Java source root 编译进 Android APK，不引入上游测试 runner。
- 校验：
  - `TLE.java`：`ff7ece5f1ff338c3fd09d36a0690b79ffc24217a7822194837fc5e220f5d6180`
  - `SGP4.java`：`12af6e4fce194d7eb3b48cd0afd2a70edf2fcfd9501d775cef08b5fba451d119`
  - `ElsetRec.java`：`562a0b82a8aca425dc22a6be2d6cbf4985b09df9dbb55396def9eb6a489b7036`

Golden 向量来自上游随附、基于 Vallado/CelesTrak SGP4 verification corpus 的 `SGP4-VER.TLE` 与 `tcppver.out`；仓库测试只保存所需的小型确定性子集。

FT8CN 修补后的 `TLE.java` SHA256 为
`6592b70619a4a852811aacc2de47635c8829d3ecb1c01a7a82a8c7fe7c2aa570`；差异仅为显式 GMT
日历，详见 `MODIFICATIONS.md`。
