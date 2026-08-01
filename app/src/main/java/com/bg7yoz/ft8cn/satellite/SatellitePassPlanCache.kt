package com.bg7yoz.ft8cn.satellite

/**
 * 缓存选中卫星的 24 小时过境与极坐标轨迹，实时 observation 仍由调用方按 1 秒更新。
 * 缓存键包含完整 TLE 和观察站，避免目录刷新后继续使用旧轨道。
 */
class SatellitePassPlanCache(
    private val cacheDurationMillis: Long = 15L * 60L * 1_000L,
    private val maximumPolarPoints: Int = 64,
) {
    data class Plan(
        val passes: List<SatellitePass>,
        val polarTrack: List<SatelliteObservation>,
    )

    private data class Key(
        val line1: String,
        val line2: String,
        val observer: ObserverPosition,
    )

    private data class Entry(
        val key: Key,
        val validUntilUtcMillis: Long,
        val plan: Plan,
    )

    private var entry: Entry? = null

    init {
        require(cacheDurationMillis in 60_000L..60L * 60L * 1_000L)
        require(maximumPolarPoints in 8..256)
    }

    @Synchronized
    fun load(
        propagator: Sgp4OrbitPropagator,
        observer: ObserverPosition,
        nowUtcMillis: Long,
    ): Plan {
        val key = Key(propagator.record.line1, propagator.record.line2, observer)
        entry?.takeIf { it.key == key && nowUtcMillis < it.validUntilUtcMillis }?.let { return it.plan }

        val passes = SatellitePassPredictor(propagator, observer).predict(
            nowUtcMillis,
            nowUtcMillis + DAY_MILLIS,
            maximumPasses = 16,
        )
        val firstPass = passes.firstOrNull()
        val polarTrack = firstPass?.let {
            sampleObservations(propagator, observer, it.aosUtcMillis, it.losUtcMillis)
        }.orEmpty()
        val validUntil = minOf(
            nowUtcMillis + cacheDurationMillis,
            firstPass?.losUtcMillis?.plus(1_000L) ?: Long.MAX_VALUE,
        )
        return Plan(passes, polarTrack).also { plan -> entry = Entry(key, validUntil, plan) }
    }

    @Synchronized
    fun clear() {
        entry = null
    }

    private fun sampleObservations(
        propagator: Sgp4OrbitPropagator,
        observer: ObserverPosition,
        startUtcMillis: Long,
        endUtcMillis: Long,
    ): List<SatelliteObservation> {
        if (endUtcMillis <= startUtcMillis) return emptyList()
        val count = maximumPolarPoints.coerceAtLeast(2)
        return List(count) { index ->
            val fraction = index.toDouble() / (count - 1).toDouble()
            val utcMillis = startUtcMillis + ((endUtcMillis - startUtcMillis) * fraction).toLong()
            propagator.observe(observer, utcMillis)
        }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
