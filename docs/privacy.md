# FT8CN 隐私说明

FT8CN 不把用户呼号、日志、音频、证书或精确位置作为遥测上传。只有用户主动启用的网络功能会与对应服务通信。

## 本地数据

- QSO、station/radio profile、自动化历史、卫星缓存和 LoTW 状态保存在应用私有 Room/DataStore 中。
- 普通 DataStore 不保存 LoTW 私钥或证书密码。
- 导入的已签名 `.tq8` 复制到应用 `noBackupFilesDir` 并以 SHA256 标识；日志不打印正文、密码或私钥。
- PCM、瀑布和解码 scratch 仅用于当前会话；Q65 长周期使用有界 chunk，页面/取消时释放引用。

## 时间和位置

- NTP 请求会向用户配置的服务器及默认公共服务器暴露网络地址和请求时间，这是协议必要信息。
- GNSS 仅在获得 Android 权限后用于可信时间和站点计算；坐标不写入诊断日志。
- 手动网格和 station 位置用于 EME/卫星本地计算，除用户主动访问地图/数据服务外不会自动上传。

## 外部服务

- CelesTrak：按需获取 TLE/OMM，使用 HTTPS、缓存条件头、大小上限和刷新间隔。
- SatNOGS：按需获取转发器资料；UI 保留数据归属。
- Google Maps：启用地图页面时受 Google APIs 条款和设备服务隐私设置约束。
- LoTW：只将用户明确选择、且经 TQSL 数字签名的 `.tq8` 发送到官方 HTTPS endpoint；未签名 ADIF 不上传。
- rigctld、网络电台和 NTP 地址由用户配置，连接信息不会进入 Git 或测试 fixture。

## 权限与安全

麦克风、位置、USB 和 Bluetooth 权限按功能请求。自动发射必须显式 armed；时间不健康、电台错误、后台停止、USB detach 或 watchdog 会阻止/撤销 PTT。Debug sample receiver/service 均为 `exported=false`，外部应用不能用公开 intent 触发诊断解码。

开发和发布报告不得包含设备序列号、Wi-Fi 密码、精确家庭位置、LoTW 凭据或私钥。仓库只保存语料 SHA256 和期望结果，不提交大型 WAV。
