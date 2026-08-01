package com.bg7yoz.ft8cn.ft8transmit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.diagnostics.InternalForegroundTestSession;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class Q65WaveStreamInstrumentationTest {
    private static final String TAG = "Q65StreamMemoryTest";
    private static final String MESSAGE = "CQ BG5JSU OL87";

    @BeforeClass
    public static void keepInstrumentationInForeground() {
        InternalForegroundTestSession.start();
    }

    @Test
    public void capacityIsIndependentOfSubmodeForAllProductionModes() {
        for (int period : FT8Common.Q65_SUPPORTED_TR_PERIODS) {
            for (int sampleRate : new int[]{12000, 24000, 48000}) {
                long expected = Q65WaveStream.requiredSamples(period, sampleRate);
                assertTrue(expected > 0L);
                assertTrue(expected <= Integer.MAX_VALUE);
                for (int submode = FT8Common.Q65_SUBMODE_A;
                     submode <= FT8Common.Q65_SUBMODE_E;
                     submode++) {
                    assertEquals(expected, Q65WaveStream.requiredSamples(period, sampleRate));
                }
            }
        }
    }

    @Test
    public void streamedWaveMatchesExistingOfficialWave() {
        GeneralVariables.setQ65Configuration(FT8Common.Q65_SUBMODE_A, 60);
        Ft8Message message = new Ft8Message(FT8Common.Q65_MODE);
        message.setTransmitRawText(MESSAGE);
        float[] expected = GenerateFTx.generateFtX(
                message, 1000.0f, 12000, FT8Common.Q65_MODE);
        float[] actual = new float[expected.length];
        int offset = 0;
        try (Q65WaveStream stream = new Q65WaveStream(
                MESSAGE, 1000.0f, 12000, FT8Common.Q65_SUBMODE_A, 60)) {
            while (offset < actual.length) {
                int produced = stream.read(
                        actual,
                        offset,
                        Math.min(4096, actual.length - offset));
                assertTrue(produced > 0);
                offset += produced;
            }
            assertEquals(stream.getTotalSamples(), offset);
        }
        for (int index = 0; index < expected.length; index++) {
            assertEquals("sample " + index, expected[index], actual[index], 1.0e-6f);
        }
    }

    @Test
    public void q65fRemainsDiagnosticOnly() {
        assertThrows(IllegalArgumentException.class, () -> new Q65WaveStream(
                MESSAGE, 1000.0f, 12000, FT8Common.Q65_SUBMODE_F, 60));
    }

    @Test
    public void occupiedBandwidthMustFitNyquist() {
        assertThrows(IllegalStateException.class, () -> new Q65WaveStream(
                MESSAGE, 1000.0f, 12000, FT8Common.Q65_SUBMODE_E, 15));
    }

    @Test
    public void q65ThreeHundredSecond48kTransmitUsesBoundedChunks() {
        float[] chunk = new float[4096];
        long produced = 0L;
        double checksum = 0.0;
        long startedAt = System.currentTimeMillis();
        try (Q65WaveStream stream = new Q65WaveStream(
                MESSAGE, 1000.0f, 48000, FT8Common.Q65_SUBMODE_A, 300)) {
            int count;
            while ((count = stream.read(chunk, 0, chunk.length)) > 0) {
                produced += count;
                checksum += chunk[0];
            }
            assertEquals(stream.getTotalSamples(), produced);
        }
        assertTrue(Double.isFinite(checksum));
        String evidence = "Q65 TX 300s sampleRate=48000 chunkSamples=4096 totalSamples="
                + produced + " elapsedMs=" + (System.currentTimeMillis() - startedAt);
        Log.i(TAG, evidence);
        Bundle status = new Bundle();
        status.putString("ft8cn_q65_tx_evidence", evidence);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
