package com.bg7yoz.ft8cn.ft8transmit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DxpeditionFrequencyPolicyTest {
    @Test
    public void foxFrequencyRangeIsEnforced() {
        assertEquals(300f, DxpeditionFrequencyPolicy.clampFoxTxFrequency(120f), 0.001f);
        assertEquals(900f, DxpeditionFrequencyPolicy.clampFoxTxFrequency(1200f), 0.001f);
        assertTrue(DxpeditionFrequencyPolicy.isFoxTxFrequency(540f));
        assertFalse(DxpeditionFrequencyPolicy.isFoxTxFrequency(1000f));
    }

    @Test
    public void houndInitialFrequencyRangeIsEnforced() {
        assertEquals(1000f, DxpeditionFrequencyPolicy.clampHoundInitialFrequency(700f), 0.001f);
        assertEquals(4000f, DxpeditionFrequencyPolicy.clampHoundInitialFrequency(4300f), 0.001f);
        assertTrue(DxpeditionFrequencyPolicy.isHoundInitialFrequency(2500f));
        assertFalse(DxpeditionFrequencyPolicy.isHoundInitialFrequency(500f));
    }

    @Test
    public void houndRReportRetriesShiftBy300Hz() {
        float base = 540f;
        assertEquals(540f, DxpeditionFrequencyPolicy.resolveHoundRReportFrequency(base, 0), 0.001f);
        assertEquals(840f, DxpeditionFrequencyPolicy.resolveHoundRReportFrequency(base, 1), 0.001f);
        assertEquals(240f, DxpeditionFrequencyPolicy.resolveHoundRReportFrequency(base, 2), 0.001f);
        assertEquals(1140f, DxpeditionFrequencyPolicy.resolveHoundRReportFrequency(base, 3), 0.001f);
    }
}

