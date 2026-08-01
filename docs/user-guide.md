# FT8CN 用户使用说明

## 首次配置

1. 在 Settings/Time 设置呼号、网格、音频采样率和解码策略。
2. 检查时间来源、offset、uncertainty 和样本 age。RX 始终可用；自动 TX/CQ 的默认 uncertainty 上限分别为 FT4 250 ms、FT8 500 ms、Q65 1000 ms，且同步样本不得超过 30 分钟。
3. 在 Radio 选择连接方式并先验证频率、模式、VFO 与 PTT 读回。没有安全天线/假负载时不要测试发射。

## Call：FT8/FT4

- Call 页面只用于 FT8 和 FT4。切换模式会原子更新 slot、tone、TX offset 和 decode request，不复用错误时序。
- FT8 slot 为 15 秒，FT4 slot 为 7.5 秒；提前解码只跑 fast pass，完整/深度结果仍在正常阶段合并和去重。
- 自动回答/自动 CQ 必须先 armed。页面显示当前状态、下一消息、TX slot、dial/audio frequency、split/Fake It、时钟和电台读回。
- Stop 会立即取消自动状态并撤销 PTT；watchdog、电台异常、时间不健康和应用进入后台也会停止自动发射。

## EME：Q65

- 正式模式为 Q65A-E；F 不在生产 UI/TX 中开放。
- 选择 period、submode、local/DX grid 和 Doppler 模式。averaging 只在同一持久 session 中累积，目标或模式变化、采样间断或手动 reset 会清除。
- 300 秒 24/48 kHz RX 和 TX 均为流式分块，不需要完整高采样率源或完整 Java TX 波形。

## Satellite

- 可从 CelesTrak/SatNOGS 刷新，或手动导入 TLE；页面显示来源、epoch、age 和 stale 状态。
- 选择卫星后显示 pass、polar/ground track 与转发器。Doppler 跟踪只更新频率，不会自动 armed 或 PTT。
- 线性反向转发器会保持相对 passband 位置；过期目标、CAT 读回失败和 LOS 会停止更新并恢复频率计划。

## Logbook/LoTW

- 本地日志支持 FT8、FT4、Q65、Satellite/EME 字段和 ADIF 3.1.5 导入/导出。
- LoTW 只接受外部 TQSL 生成并数字签名的 `.tq8`。先通过 SAF 导入，确认批次后再上传；不要把未签名 ADIF 当作 LoTW 上传文件。
- 上传任务幂等并记录 LOCAL/PENDING_SIGN/SIGNED/UPLOADING/ACCEPTED/REJECTED/CONFIRMED 状态。

## Split 与 Fake It

- `NONE`：dial 不变，以音频 offset 发射。
- `RIG_SPLIT`：使用电台原生 RX/TX VFO 分离。
- `FAKE_IT`：发射前临时移动 dial，使音频保持在配置的清洁通带，结束或失败后恢复。它只能降低音频链产生杂散的风险，不能承诺消除谐波。

Material 3 界面保持 FT8CN 原有配色、信息密度与底部图标导航；成熟 JNI/DSP 路径继续由类型安全入口调用，不为语言统一而重写。当前发布门禁和已知限制见 `docs/verification/full-hardening-2026-08-01.md`。
