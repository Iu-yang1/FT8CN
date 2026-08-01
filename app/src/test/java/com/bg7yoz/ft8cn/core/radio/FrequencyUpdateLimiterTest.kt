package com.bg7yoz.ft8cn.core.radio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrequencyUpdateLimiterTest {
    @Test
    fun rejectsStaleFastAndSubStepTargets() {
        val limiter = FrequencyUpdateLimiter(
            minimumIntervalMillis = 200,
            minimumStepHz = 2,
            maximumTargetAgeMillis = 1_000,
        )
        val first = TimedFrequencyTarget(10_000, 145_900_000, 435_100_000)
        assertTrue(limiter.shouldApply(first, 10_100))
        limiter.markApplied(first, 10_100)

        assertFalse(limiter.shouldApply(first.copy(rxFrequencyHz = 145_900_020), 10_200))
        assertFalse(limiter.shouldApply(first.copy(generatedUtcMillis = 8_000), 10_300))
        assertFalse(limiter.shouldApply(first.copy(generatedUtcMillis = 10_300, rxFrequencyHz = 145_900_001), 10_300))
        assertTrue(limiter.shouldApply(first.copy(generatedUtcMillis = 10_300, rxFrequencyHz = 145_900_002), 10_300))
    }

    @Test
    fun rejectsFutureAndInvalidFrequencies() {
        val limiter = FrequencyUpdateLimiter()
        assertFalse(limiter.shouldApply(TimedFrequencyTarget(2_000, 1, 1), 1_000))
        assertFalse(limiter.shouldApply(TimedFrequencyTarget(1_000, 0, 1), 1_000))
        assertFalse(limiter.shouldApply(TimedFrequencyTarget(1_000, 1, -1), 1_000))
    }
}
