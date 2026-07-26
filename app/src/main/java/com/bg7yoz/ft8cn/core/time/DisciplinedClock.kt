package com.bg7yoz.ft8cn.core.time

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ClockSource {
    SYSTEM,
    NTP,
    GNSS,
    HOLDOVER,
}

data class ClockSnapshot(
    val utcMillis: Long,
    val monotonicNanos: Long,
    val offsetMillis: Double,
    val driftPpm: Double,
    val uncertaintyMillis: Double,
    val source: ClockSource,
    val sampleAgeMillis: Long,
    val healthy: Boolean,
)

interface DisciplinedClock {
    val state: StateFlow<ClockSnapshot>

    fun snapshot(): ClockSnapshot = state.value
}

class FakeDisciplinedClock(initial: ClockSnapshot) : DisciplinedClock {
    private val mutableState = MutableStateFlow(initial)

    override val state: StateFlow<ClockSnapshot> = mutableState.asStateFlow()

    fun update(snapshot: ClockSnapshot) {
        mutableState.value = snapshot
    }
}
