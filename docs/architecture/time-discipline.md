# 纪律化 UTC 与时隙调度

## 目标

FT8CN 不修改 Android 系统时间。`SystemDisciplinedClock` 以
`SystemClock.elapsedRealtimeNanos()` 为单调锚点，将通过校验的 SNTP 或 GNSS
样本转换为连续 UTC。系统 wall clock 跳变只会触发重新捕获，不会直接让正在
运行的 FT8、FT4 或 Q65 时隙倒退。

时钟状态包含 UTC、offset、drift、uncertainty、source、sample age、RTT、
共识成员和最近拒绝原因。界面只读取状态摘要；decoder、自动化和 CAT 在创建
请求时取得快照，处理中不读取会变化的 UI 全局值。

## 来源与健康判定

- SNTP 使用四时间戳 offset/delay，校验 originate timestamp、mode、version、
  stratum、leap、KoD 和 root dispersion。多服务器结果经过 RTT 与离群值筛选。
- GNSS 只接受带硬件时间与 uncertainty 的样本；位置权限、位置 fix 与可信 GNSS
  时间是三个不同状态，坐标不写入诊断日志。
- 首个样本也必须通过数值、时序和协议检查。可信时钟出现持续大残差时，需要
  多个一致样本才重新捕获，避免恶意或错误服务器造成瞬时跳变。
- 样本超过 2 分钟进入 HOLDOVER，uncertainty 按最大漂移速率增长；超过 30
  分钟不再允许自动发射。

默认自动发射 uncertainty 上限为：FT4 250 ms、FT8 500 ms、Q65 1000 ms。
这些是安全门禁，不是对解码时窗的缩减。RX 在时钟不健康时仍可运行；自动
回答、自动 CQ、卫星/EME 自动 CAT 会被阻断，手动 TX 必须显式确认覆盖。

## 时隙与并发

FT8 采用 15 秒、FT4 采用 7.5 秒、Q65 采用所选合法周期。所有边界都从同一
纪律化 UTC 计算。高频 `nowMillis()` 读取不会以 10 ms 频率发布 `StateFlow`；
状态发布、网络同步和 GNSS 回调不占用音频或 native decode lane。

## 验证

单元测试覆盖 wall clock 跳变、漂移、恶意偏移、KoD、首样本、持续残差重新
捕获、网络中断、GNSS 丢失、holdover、跨时隙和时间源切换。阶段 9 的 host、
oracle 与真机 Debug 解码结果没有因时间服务产生回退。
