package com.bg7yoz.ft8cn.satellite

import kotlin.math.roundToLong

/** 卫星远离时下行变低、上行补偿变高；两条链路的符号必须分别处理。 */
class SatelliteDopplerPlanner(
    private val radioStepHz: Long = 1L,
) {
    init {
        require(radioStepHz in 1L..10_000L)
    }

    fun plan(
        generatedUtcMillis: Long,
        rangeRateMetersPerSecond: Double,
        nominalDownlinkHz: Long,
        nominalUplinkHz: Long,
    ): SatelliteFrequencyTarget {
        validateFrequency(nominalDownlinkHz)
        validateFrequency(nominalUplinkHz)
        require(rangeRateMetersPerSecond.isFinite() && kotlin.math.abs(rangeRateMetersPerSecond) < 20_000.0)
        val receiveDoppler = -rangeRateMetersPerSecond * nominalDownlinkHz / SPEED_OF_LIGHT_MPS
        val transmitDoppler = rangeRateMetersPerSecond * nominalUplinkHz / SPEED_OF_LIGHT_MPS
        return SatelliteFrequencyTarget(
            generatedUtcMillis = generatedUtcMillis,
            rxFrequencyHz = quantize(nominalDownlinkHz + receiveDoppler),
            txFrequencyHz = quantize(nominalUplinkHz + transmitDoppler),
            receiveDopplerHz = receiveDoppler,
            transmitDopplerHz = transmitDoppler,
            rangeRateMetersPerSecond = rangeRateMetersPerSecond,
        )
    }

    fun mapDownlinkToUplink(selectedDownlinkHz: Long, transponder: SatelliteTransponder): Long {
        val downLow = requireNotNull(transponder.downlinkLowHz) { "转发器缺少下行范围" }
        val downHigh = requireNotNull(transponder.downlinkHighHz) { "转发器缺少下行范围" }
        val upLow = requireNotNull(transponder.uplinkLowHz) { "转发器缺少上行范围" }
        val upHigh = requireNotNull(transponder.uplinkHighHz) { "转发器缺少上行范围" }
        require(downHigh > downLow && upHigh > upLow)
        require(selectedDownlinkHz in downLow..downHigh)
        val fraction = (selectedDownlinkHz - downLow).toDouble() / (downHigh - downLow).toDouble()
        val uplinkFraction = if (transponder.inverted) 1.0 - fraction else fraction
        return quantize(upLow + uplinkFraction * (upHigh - upLow))
    }

    private fun quantize(frequencyHz: Double): Long =
        (frequencyHz / radioStepHz).roundToLong() * radioStepHz

    private fun validateFrequency(frequencyHz: Long) {
        require(frequencyHz in 100_000L..100_000_000_000L) { "卫星频率超出安全范围" }
    }

    private companion object {
        const val SPEED_OF_LIGHT_MPS = 299_792_458.0
    }
}
