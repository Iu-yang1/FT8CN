package com.bg7yoz.ft8cn.core.radio

import kotlin.math.abs

data class TimedFrequencyTarget(
    val generatedUtcMillis: Long,
    val rxFrequencyHz: Long,
    val txFrequencyHz: Long,
)

/** 拒绝过期 Doppler 目标，并限制细小抖动形成的 CAT storm。 */
class FrequencyUpdateLimiter(
    private val minimumIntervalMillis: Long = 200L,
    private val minimumStepHz: Long = 2L,
    private val maximumTargetAgeMillis: Long = 1_000L,
) {
    private var lastAppliedAtMillis = Long.MIN_VALUE
    private var lastRxFrequencyHz = Long.MIN_VALUE
    private var lastTxFrequencyHz = Long.MIN_VALUE

    fun shouldApply(target: TimedFrequencyTarget, nowUtcMillis: Long): Boolean {
        if (target.rxFrequencyHz <= 0 || target.txFrequencyHz <= 0) return false
        val age = nowUtcMillis - target.generatedUtcMillis
        if (age < 0 || age > maximumTargetAgeMillis) return false
        if (lastAppliedAtMillis != Long.MIN_VALUE &&
            nowUtcMillis - lastAppliedAtMillis < minimumIntervalMillis
        ) {
            return false
        }
        if (lastRxFrequencyHz != Long.MIN_VALUE &&
            abs(target.rxFrequencyHz - lastRxFrequencyHz) < minimumStepHz &&
            abs(target.txFrequencyHz - lastTxFrequencyHz) < minimumStepHz
        ) {
            return false
        }
        return true
    }

    fun markApplied(target: TimedFrequencyTarget, nowUtcMillis: Long) {
        lastAppliedAtMillis = nowUtcMillis
        lastRxFrequencyHz = target.rxFrequencyHz
        lastTxFrequencyHz = target.txFrequencyHz
    }

    fun reset() {
        lastAppliedAtMillis = Long.MIN_VALUE
        lastRxFrequencyHz = Long.MIN_VALUE
        lastTxFrequencyHz = Long.MIN_VALUE
    }
}
