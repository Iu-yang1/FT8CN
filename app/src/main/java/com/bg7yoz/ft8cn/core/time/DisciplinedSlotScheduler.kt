package com.bg7yoz.ft8cn.core.time

enum class SlotMode {
    FT8,
    FT4,
    Q65,
}

data class SlotBoundary(
    val index: Long,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
)

/** 纯 UTC 算法，便于用虚拟时钟验证 FT8、FT4 和 Q65 的边界。 */
class DisciplinedSlotScheduler(
    mode: SlotMode,
    q65PeriodSeconds: Int = 60,
) {
    val periodMillis: Long = when (mode) {
        SlotMode.FT8 -> 15_000L
        SlotMode.FT4 -> 7_500L
        SlotMode.Q65 -> {
            require(q65PeriodSeconds in setOf(15, 30, 60, 120, 300)) {
                "Q65 period must be 15/30/60/120/300 seconds"
            }
            q65PeriodSeconds * 1_000L
        }
    }

    fun boundaryAt(utcMillis: Long): SlotBoundary {
        val index = Math.floorDiv(utcMillis, periodMillis)
        val start = index * periodMillis
        return SlotBoundary(index, start, start + periodMillis)
    }

    fun nextBoundaryAfter(utcMillis: Long): SlotBoundary {
        val current = boundaryAt(utcMillis)
        return SlotBoundary(current.index + 1, current.endUtcMillis, current.endUtcMillis + periodMillis)
    }

    fun sequenceAt(utcMillis: Long): Int = Math.floorMod(boundaryAt(utcMillis).index, 2L).toInt()
}
