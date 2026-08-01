# 电台事务、Split 与安全 PTT

## 单一控制入口

真实调频和发射统一通过 Application 级 `RadioTransactionCoordinator` 与串行
发射状态机。Compose、自动回答、自动 CQ、Q65、卫星和 EME 页面不能直接调用
Hamlib/JNI PTT。CAT/Doppler 调度不运行在音频 callback 或 decoder lane。

事务顺序固定为：获取 radio lease、刷新并保存电台状态、确认 PTT OFF、应用
模式/频率/VFO/split、读回验证、交给发射状态机，最后在成功、失败或取消时
恢复原状态。回滚失败会进入 FAILED 并执行 emergency stop。

发射状态为：

`IDLE -> ARMING -> RADIO_CONFIGURED -> PTT_CONFIRMED -> AUDIO_ACTIVE -> STOPPING -> IDLE/FAILED`

每次请求带 generation/cancellation token。CAT 入队不代表 PTT 成功；只有 PTT
ON 读回确认并经过模式相关 TX delay 后才能播放。所有退出路径在 `finally` 中
停止 AudioTrack、撤销 PTT 并回滚电台状态。watchdog 覆盖 Q65 15–300 秒计划。

## Split 策略

- `NONE`：保持 dial，以目标音频 offset 发射。
- `RIG_SPLIT`：使用电台原生 RX/TX VFO；不支持时明确失败，不静默降级。
- `FAKE_IT`：根据目标 RF、音频清洁通带、passband 和电台步进临时移动 dial，
  发射结束后恢复。负音频、越界或读回不一致会阻断发射。

Fake It 只能降低音频链杂散风险，不能承诺消除谐波。卫星和 EME 在 PTT 期间
不得独立改频；目标过期、时间不健康或 radio lease 丢失时停止 Doppler 更新。

## 后端与线程边界

Android 包含固定版本 LGPL `libhamlib.so`，同时保留显式选择的 rigctld 路径；
rigctld 只适用于可信 LAN/VPN，不提供 TLS。旧 USB/Bluetooth transport 仅作为
已连接设备适配层，最终命令仍经过统一事务。native 全局 handle、Java native
decode lock、C bridge mutex 和 Q65 serial lane 均保持串行，未启用
`PARALLEL_NATIVE`。

dummy rig 测试覆盖 PTT ON/读回失败、音频初始化失败、取消、页面退出、连接
中断、快速重复点击和回滚。实体电台因缺少已确认假负载条件保持
`BLOCKED_HARDWARE_RIG`，不以 dummy 测试冒充射频硬件 PASS。
