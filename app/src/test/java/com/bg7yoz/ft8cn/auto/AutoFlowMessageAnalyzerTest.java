package com.bg7yoz.ft8cn.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;

import org.junit.Test;

import java.util.Arrays;

public class AutoFlowMessageAnalyzerTest {
    @Test
    public void resolveIncomingOrderForStandardReport() {
        Ft8Message message = new Ft8Message();
        message.isValid = true;
        message.signalFormat = FT8Common.FT8_MODE;
        message.i3 = 1;
        message.n3 = 0;
        message.callsignFrom = "KH1DX";
        message.callsignTo = "BG7YOZ";
        message.extraInfo = "-13";

        assertEquals(2, AutoFlowMessageAnalyzer.resolveIncomingOrder(
                message,
                "BG7YOZ",
                "KH1DX"
        ));
    }

    @Test
    public void resolveIncomingOrderForDxpeditionCompletion() {
        Ft8Message message = new Ft8Message();
        message.isValid = true;
        message.signalFormat = FT8Common.FT8_MODE;
        message.i3 = 0;
        message.n3 = 1;
        message.callsignTo = "BG7YOZ";
        message.dx_call_to2 = "W9XYZ";
        message.callsignFrom = "KH1DX";
        message.report = -17;

        assertEquals(5, AutoFlowMessageAnalyzer.resolveIncomingOrder(
                message,
                "BG7YOZ",
                "KH1DX"
        ));
    }

    @Test
    public void resolveIncomingOrderForDxpeditionReportToSecondaryHound() {
        Ft8Message message = new Ft8Message();
        message.isValid = true;
        message.signalFormat = FT8Common.FT8_MODE;
        message.i3 = 0;
        message.n3 = 1;
        message.callsignTo = "K1ABC";
        message.dx_call_to2 = "BG7YOZ";
        message.callsignFrom = "KH1DX";
        message.report = -9;

        assertEquals(2, AutoFlowMessageAnalyzer.resolveIncomingOrder(
                message,
                "BG7YOZ",
                "KH1DX"
        ));
    }

    @Test
    public void compoundCallsignMatchUsesMainPart() {
        assertTrue(AutoFlowMessageAnalyzer.callsignMatches("KH1DX", "KH1DX/P"));
        assertTrue(AutoFlowMessageAnalyzer.callsignMatches("BG7YOZ/4", "BG7YOZ"));
        assertFalse(AutoFlowMessageAnalyzer.callsignMatches("KH1DX", "W9XYZ"));
    }

    @Test
    public void currentSessionActivityRequiresCurrentTargetAndBand() {
        Ft8Message message = new Ft8Message();
        message.isValid = true;
        message.signalFormat = FT8Common.FT8_MODE;
        message.i3 = 1;
        message.n3 = 0;
        message.callsignFrom = "KH1DX";
        message.callsignTo = "W9XYZ";
        message.band = 20;
        message.isWeakSignal = false;

        assertTrue(AutoFlowMessageAnalyzer.isCurrentSessionActivity(
                message,
                "KH1DX",
                FT8Common.FT8_MODE,
                20
        ));
        assertFalse(AutoFlowMessageAnalyzer.isCurrentSessionActivity(
                message,
                "JA1ABC",
                FT8Common.FT8_MODE,
                20
        ));
        assertFalse(AutoFlowMessageAnalyzer.isCurrentSessionActivity(
                message,
                "KH1DX",
                FT8Common.FT8_MODE,
                40
        ));
    }

    @Test
    public void currentSessionActivityTracksDxpeditionFoxTraffic() {
        Ft8Message message = new Ft8Message();
        message.isValid = true;
        message.signalFormat = FT8Common.FT8_MODE;
        message.i3 = 0;
        message.n3 = 1;
        message.callsignFrom = "KH1DX";
        message.callsignTo = "K1ABC";
        message.dx_call_to2 = "W9XYZ";
        message.band = 20;
        message.isWeakSignal = false;

        assertTrue(AutoFlowMessageAnalyzer.isCurrentSessionActivity(
                message,
                "KH1DX",
                FT8Common.FT8_MODE,
                20
        ));
    }

    @Test
    public void dxpeditionAutoCanBeDisabled() {
        Ft8Message message = new Ft8Message();
        message.isValid = true;
        message.signalFormat = FT8Common.FT8_MODE;
        message.i3 = 0;
        message.n3 = 1;
        message.callsignTo = "BG7YOZ";
        message.dx_call_to2 = "W9XYZ";
        message.callsignFrom = "KH1DX";
        message.report = -17;
        message.band = 20;

        assertEquals(-1, AutoFlowMessageAnalyzer.resolveIncomingOrder(
                message,
                "BG7YOZ",
                "KH1DX",
                false
        ));
        assertFalse(AutoFlowMessageAnalyzer.isCurrentSessionActivity(
                message,
                "KH1DX",
                FT8Common.FT8_MODE,
                20,
                false
        ));
        assertEquals(AutoSessionType.STANDARD, AutoFlowMessageAnalyzer.resolveSessionType(
                message,
                "BG7YOZ",
                "KH1DX",
                AutoSessionType.STANDARD,
                false
        ));
    }

    @Test
    public void dxpeditionUiPolicyRestrictsOrders() {
        assertTrue(Arrays.equals(
                new int[]{1, 3},
                AutoSessionUiPolicy.getAvailableFunctionOrders(AutoSessionType.FT8_DXPEDITION_HOUND, 3)
        ));
        assertEquals(1, AutoSessionUiPolicy.sanitizeFunctionOrder(
                AutoSessionType.FT8_DXPEDITION_HOUND,
                3,
                2
        ));
        assertEquals(6, AutoSessionUiPolicy.sanitizeFunctionOrder(
                AutoSessionType.STANDARD,
                6,
                3
        ));
        assertTrue(Arrays.equals(
                new int[]{2, 4, 6},
                AutoSessionUiPolicy.getAvailableFunctionOrders(AutoSessionType.FT8_DXPEDITION_FOX, 2)
        ));
    }
}

