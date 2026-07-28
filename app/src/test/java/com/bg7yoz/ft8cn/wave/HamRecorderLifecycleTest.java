package com.bg7yoz.ft8cn.wave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HamRecorderLifecycleTest {
    @Test
    public void oneShotMonitorPublishesOnceAndRemovesItself() {
        AtomicInteger monitorCount = new AtomicInteger();
        AtomicReference<float[]> completed = new AtomicReference<>();
        HamRecorder recorder = new HamRecorder(monitorCount::set);
        recorder.setDataFromLan();
        recorder.startRecord();

        assertNotNull(recorder.getVoiceData(160, true, completed::set));
        assertEquals(1, recorder.getVoiceMonitorCount());
        float[] samples = new float[1920];
        java.util.Arrays.fill(samples, 0.25f);
        recorder.doOnWaveDataReceived(samples.length, samples, 12000);

        assertNotNull(completed.get());
        assertEquals(1920, completed.get().length);
        assertEquals(0.25f, completed.get()[1000], 0.0f);
        assertEquals(0, recorder.getVoiceMonitorCount());
        assertEquals(0, monitorCount.get());
        recorder.stopRecord();
    }
}
