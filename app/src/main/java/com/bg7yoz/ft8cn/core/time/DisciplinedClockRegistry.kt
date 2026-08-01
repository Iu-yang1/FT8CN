package com.bg7yoz.ft8cn.core.time

import kotlinx.coroutines.flow.StateFlow

/** Java/JNI 旧路径统一从这里读取应用 UTC，避免各自读取可跳变的 wall clock。 */
object DisciplinedClockRegistry {
    private val disciplinedClock = SystemDisciplinedClock()

    @JvmStatic
    fun nowMillis(): Long = disciplinedClock.nowMillis()

    @JvmStatic
    fun refreshState(): ClockSnapshot = disciplinedClock.refresh()

    @JvmStatic
    fun snapshot(): ClockSnapshot = disciplinedClock.snapshot()

    @JvmStatic
    fun state(): StateFlow<ClockSnapshot> = disciplinedClock.state

    @JvmStatic
    fun submitSample(sample: ClockSample): Boolean = disciplinedClock.submitSample(sample)

    @JvmStatic
    fun isAutomaticTransmitAllowed(mode: AutomaticTransmitMode): Boolean =
        disciplinedClock.automaticTransmitAllowed(mode)

    @JvmStatic
    fun isAutomaticTransmitAllowed(): Boolean =
        disciplinedClock.automaticTransmitAllowed(AutomaticTransmitMode.FT8)

    @JvmStatic
    fun automaticTransmitBlockReason(mode: AutomaticTransmitMode): String =
        disciplinedClock.automaticTransmitBlockReason(mode)

    @JvmStatic
    fun automaticTransmitBlockReason(): String =
        disciplinedClock.automaticTransmitBlockReason(AutomaticTransmitMode.FT8)
}
