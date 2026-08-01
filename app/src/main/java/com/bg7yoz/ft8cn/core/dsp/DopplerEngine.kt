package com.bg7yoz.ft8cn.core.dsp

data class DopplerRequest(
    val utcMillis: Long,
    val nominalRxFrequencyHz: Long,
    val nominalTxFrequencyHz: Long,
    val radialVelocityMetersPerSecond: Double,
    val uncertaintyHz: Double,
)

data class DopplerTarget(
    val utcMillis: Long,
    val rxFrequencyHz: Long,
    val txFrequencyHz: Long,
    val correctionHz: Double,
    val uncertaintyHz: Double,
)

interface DopplerEngine {
    fun calculate(request: DopplerRequest): DopplerTarget
}

class FakeDopplerEngine(
    private val correctionHz: Double = 0.0,
) : DopplerEngine {
    override fun calculate(request: DopplerRequest): DopplerTarget = DopplerTarget(
        utcMillis = request.utcMillis,
        rxFrequencyHz = (request.nominalRxFrequencyHz + correctionHz).toLong(),
        txFrequencyHz = (request.nominalTxFrequencyHz - correctionHz).toLong(),
        correctionHz = correctionHz,
        uncertaintyHz = request.uncertaintyHz,
    )
}
