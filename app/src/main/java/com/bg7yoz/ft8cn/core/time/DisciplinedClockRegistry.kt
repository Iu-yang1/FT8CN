package com.bg7yoz.ft8cn.core.time

import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Java/JNI 旧路径统一从这里读取应用 UTC，避免各自读取可跳变的 wall clock。 */
object DisciplinedClockRegistry {
    private val disciplinedClock = SystemDisciplinedClock()
    private val refresher = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ft8cn-clock-refresh").apply { isDaemon = true }
    }

    init {
        refresher.scheduleAtFixedRate(
            { disciplinedClock.refresh() },
            1L,
            1L,
            TimeUnit.SECONDS,
        )
    }

    @JvmStatic
    fun nowMillis(): Long = disciplinedClock.snapshot().utcMillis

    @JvmStatic
    fun snapshot(): ClockSnapshot = disciplinedClock.snapshot()

    @JvmStatic
    fun state(): StateFlow<ClockSnapshot> = disciplinedClock.state

    @JvmStatic
    fun submitSample(sample: ClockSample): Boolean = disciplinedClock.submitSample(sample)

    @JvmStatic
    fun isAutomaticTransmitAllowed(): Boolean = disciplinedClock.automaticTransmitAllowed()

    @JvmStatic
    fun automaticTransmitBlockReason(): String = disciplinedClock.automaticTransmitBlockReason()
}
