# 第三方组件登记

本目录保存 FT8CN 已打包组件和设计参考项目的来源、许可证、修改范围与构建边界。实际 vendor 源码仍保留在原构建路径中；这里的 `SOURCE_MANIFEST.cmake` 只用于合规清单和依赖检查，不创建第二份源码副本。

发布时必须同时查看 `docs/third-party/license-matrix.md` 和 `docs/third-party/sbom.cdx.json`。FT8CN 自有代码采用 MIT 许可证，但 Android 组合产物包含 GPL-3.0 的 WSJT-X core，因此不能把整个 APK 描述为“仅 MIT”。
