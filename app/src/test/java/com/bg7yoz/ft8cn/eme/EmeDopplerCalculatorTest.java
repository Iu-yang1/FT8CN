package com.bg7yoz.ft8cn.eme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class EmeDopplerCalculatorTest {
    private static final double FREQUENCY_HZ = 1_000_000_000.0;
    private static final double EPSILON_HZ = 1.0e-9;

    @Test
    public void ownEchoKeepsTransmitFixedAndTracksRoundTrip() {
        EmeDopplerCalculator.CorrectionPlan plan = EmeDopplerCalculator.calculatePlan(
                FREQUENCY_HZ,
                100.0,
                Double.NaN,
                EmeDopplerCalculator.PathMode.OWN_ECHO);

        assertEquals(2.0 * plan.localOneWayHz, plan.receiveCorrectionHz, EPSILON_HZ);
        assertEquals(0.0, plan.transmitCorrectionHz, EPSILON_HZ);
    }

    @Test
    public void constantFrequencyOnMoonSplitsOneWayCorrection() {
        EmeDopplerCalculator.CorrectionPlan plan = EmeDopplerCalculator.calculatePlan(
                FREQUENCY_HZ,
                100.0,
                Double.NaN,
                EmeDopplerCalculator.PathMode.CONSTANT_FREQUENCY_ON_MOON);

        assertEquals(plan.localOneWayHz, plan.receiveCorrectionHz, EPSILON_HZ);
        assertEquals(-plan.localOneWayHz, plan.transmitCorrectionHz, EPSILON_HZ);
    }

    @Test
    public void fullDopplerUsesBothStationsAndOppositeTransmitCorrection() {
        EmeDopplerCalculator.CorrectionPlan plan = EmeDopplerCalculator.calculatePlan(
                FREQUENCY_HZ,
                100.0,
                -50.0,
                EmeDopplerCalculator.PathMode.FULL_DOPPLER_TO_DX);

        assertEquals(plan.localOneWayHz + plan.dxOneWayHz,
                plan.receiveCorrectionHz,
                EPSILON_HZ);
        assertEquals(-plan.receiveCorrectionHz, plan.transmitCorrectionHz, EPSILON_HZ);
    }

    @Test
    public void fullDopplerRequiresDxRangeRate() {
        assertThrows(IllegalArgumentException.class, () -> EmeDopplerCalculator.calculatePlan(
                FREQUENCY_HZ,
                100.0,
                Double.NaN,
                EmeDopplerCalculator.PathMode.FULL_DOPPLER_TO_DX));
    }
}
