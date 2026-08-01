# 自动程序模式策略

## 当前模式能力

- FT8：支持标准自动 QSO、自动 CQ、Hound/Fox 与 DXpedition。
- FT4：支持标准自动跟呼和回复；不支持 FT8-only DXpedition。
- Q65：当前仅允许手动目标、手动 TX/RX，不执行自动 QSO 状态推进。
- EME Tracking：只负责安全条件满足时的频率修正，不参与自动 QSO 决策。

## 已有保护

- `AutoSessionUiPolicy.supportsAutomaticQso()` 对 Q65 返回 false。
- `AutoSessionUiPolicy.supportsDxpedition()` 仅对 FT8 返回 true。
- `FT8TransmitSignal.parseMessageToFunction()` 在 Q65 下直接返回。
- `refreshSessionModeByCurrentTarget()` 在不支持自动 QSO 的模式下只绑定手动目标。
- DXpedition、split 或手动 slot frequency 与 EME Tracking 冲突时，tracking 进入暂停。

## 后续整理方向

后续应将自动行为从散落的 mode 判断收敛为 mode capability/policy：

- automatic CQ
- automatic reply
- DXpedition Hound/Fox
- CQ queue auto reply
- structured function order
- manual target
- manual raw text

Q65 自动行为需要独立设计和样本验证，不应复用 FT8 function order 状态机。

