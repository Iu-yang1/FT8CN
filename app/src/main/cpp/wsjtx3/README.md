# WSJT-X 3.0 Official Core

这个目录用于把官方 `WSJTX/wsjtx` `v3.0.0` 的 FT8/FT4 核心逐步接入 FT8CN。

当前阶段已经完成：

- 将官方 `lib/`、`commons.h`、`README`、`COPYING` 引入本地仓库
- 新增 `wsjtx3_backend.*` 作为 FT8CN native 分发表中的官方 backend 占位
- 新增 host 侧构建入口，后续用于验证官方 core 在本机 Fortran 工具链下可编译

当前阶段尚未完成：

- Android NDK 下的 Fortran 交叉编译
- 官方 `multimode_decoder` 到 FT8CN `decoder_t` / `ft8_message` 的结果桥接
- 现有 JNI 与官方 backend 的正式联调

说明：

- Android 现阶段已经统一到官方 `WSJT-X 3.0` backend
- `experimental` 模块不经过这里，继续保留独立责任边界
