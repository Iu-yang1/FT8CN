package com.bg7yoz.ft8cn.satellite

import kotlin.math.abs

/** 有界的过境搜索；细化 AOS/LOS 时不会改变 SGP4 的传播精度。 */
class SatellitePassPredictor(
    private val propagator: Sgp4OrbitPropagator,
    private val observer: ObserverPosition,
    private val minimumElevationDegrees: Double = 0.0,
    private val coarseStepMillis: Long = 30_000L,
    private val staleAfterMillis: Long = 14L * 24L * 60L * 60L * 1_000L,
) {
    init {
        require(minimumElevationDegrees in -5.0..90.0)
        require(coarseStepMillis in 5_000L..300_000L)
        require(staleAfterMillis > 0)
    }

    fun predict(
        startUtcMillis: Long,
        endUtcMillis: Long,
        maximumPasses: Int = 16,
    ): List<SatellitePass> {
        require(endUtcMillis > startUtcMillis)
        require(endUtcMillis - startUtcMillis <= MAXIMUM_WINDOW_MILLIS) { "过境搜索窗口不能超过 7 天" }
        require(maximumPasses in 1..64)
        val output = ArrayList<SatellitePass>(maximumPasses)
        var previousTime = startUtcMillis
        val initialObservation = propagator.observe(observer, previousTime)
        var inPass = initialObservation.elevationDegrees >= minimumElevationDegrees
        var aosTime = if (inPass) startUtcMillis else 0L
        var aosAzimuth = if (inPass) initialObservation.azimuthDegrees else 0.0
        var maximum = initialObservation
        val ongoing = inPass
        var samples = 1

        while (previousTime < endUtcMillis && output.size < maximumPasses) {
            val currentTime = minOf(endUtcMillis, previousTime + coarseStepMillis)
            val current = propagator.observe(observer, currentTime)
            samples++
            check(samples <= MAXIMUM_SAMPLES) { "过境搜索超过安全样本上限" }

            if (!inPass && current.elevationDegrees >= minimumElevationDegrees) {
                aosTime = refineCrossing(previousTime, currentTime, rising = true)
                val aos = propagator.observe(observer, aosTime)
                aosAzimuth = aos.azimuthDegrees
                maximum = aos
                inPass = true
            }
            if (inPass && current.elevationDegrees > maximum.elevationDegrees) maximum = current
            if (inPass && current.elevationDegrees < minimumElevationDegrees) {
                val losTime = refineCrossing(previousTime, currentTime, rising = false)
                val los = propagator.observe(observer, losTime)
                val tca = refineMaximum(aosTime, losTime, maximum.utcMillis)
                output += SatellitePass(
                    aosUtcMillis = aosTime,
                    tcaUtcMillis = tca.utcMillis,
                    losUtcMillis = losTime,
                    maximumElevationDegrees = tca.elevationDegrees,
                    aosAzimuthDegrees = aosAzimuth,
                    losAzimuthDegrees = los.azimuthDegrees,
                    ongoingAtWindowStart = ongoing && aosTime == startUtcMillis,
                    tleStale = abs(startUtcMillis - propagator.record.epochUtcMillis) > staleAfterMillis,
                )
                inPass = false
            }
            previousTime = currentTime
        }
        return output
    }

    private fun refineCrossing(lowTime: Long, highTime: Long, rising: Boolean): Long {
        var low = lowTime
        var high = highTime
        repeat(22) {
            if (high - low <= 100L) return@repeat
            val middle = low + (high - low) / 2
            val above = propagator.observe(observer, middle).elevationDegrees >= minimumElevationDegrees
            if (above == rising) high = middle else low = middle
        }
        return if (rising) high else low
    }

    private fun refineMaximum(aos: Long, los: Long, coarseMaximum: Long): SatelliteObservation {
        var left = maxOf(aos, coarseMaximum - coarseStepMillis)
        var right = minOf(los, coarseMaximum + coarseStepMillis)
        repeat(24) {
            if (right - left <= 100L) return@repeat
            val third = (right - left) / 3
            val firstTime = left + third
            val secondTime = right - third
            val firstElevation = propagator.observe(observer, firstTime).elevationDegrees
            val secondElevation = propagator.observe(observer, secondTime).elevationDegrees
            if (firstElevation < secondElevation) left = firstTime else right = secondTime
        }
        return propagator.observe(observer, left + (right - left) / 2)
    }

    private companion object {
        const val MAXIMUM_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val MAXIMUM_SAMPLES = 121_000
    }
}
