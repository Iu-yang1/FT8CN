package com.bg7yoz.ft8cn.feature.shell

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class ModernShellComposeBenchmarkTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ModernShellActivity>()

    @Test
    fun repeatedNavigationHasBoundedTimeAndMemory() {
        navigateToSettingsAndBack()

        val elapsed = ArrayList<Long>(MEASURED_ROUNDS)
        var minimumPssKb = Long.MAX_VALUE
        var maximumPssKb = 0L
        var peakJavaBytes = 0L
        var peakNativeBytes = 0L
        repeat(MEASURED_ROUNDS) {
            val started = SystemClock.elapsedRealtimeNanos()
            navigateToSettingsAndBack()
            elapsed += (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
            val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            minimumPssKb = minOf(minimumPssKb, memory.totalPss.toLong())
            maximumPssKb = maxOf(maximumPssKb, memory.totalPss.toLong())
            val runtime = Runtime.getRuntime()
            peakJavaBytes = maxOf(peakJavaBytes, runtime.totalMemory() - runtime.freeMemory())
            peakNativeBytes = maxOf(peakNativeBytes, Debug.getNativeHeapAllocatedSize())
        }
        elapsed.sort()
        val p50 = percentile(elapsed, 0.50)
        val p95 = percentile(elapsed, 0.95)
        val pssGrowthKb = maximumPssKb - minimumPssKb
        Log.i(
            TAG,
            "rounds=$MEASURED_ROUNDS p50Ms=$p50 p95Ms=$p95 " +
                "peakJava=$peakJavaBytes peakNative=$peakNativeBytes pssGrowthKb=$pssGrowthKb",
        )
        assertTrue("两次页面切换 p95 超过 2 秒: $p95", p95 < 2_000L)
        assertTrue("重复导航 PSS 增长超过 32 MiB: $pssGrowthKb KiB", pssGrowthKb < 32L * 1024L)
    }

    private fun navigateToSettingsAndBack() {
        composeRule.onNodeWithContentDescription("打开功能导航").performClick()
        composeRule.onNodeWithTag("nav-settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("打开功能导航").performClick()
        composeRule.onNodeWithTag("nav-call").performClick()
        composeRule.waitForIdle()
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long {
        val index = kotlin.math.ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    private companion object {
        const val TAG = "FT8CN-UI-Benchmark"
        const val MEASURED_ROUNDS = 10
    }
}
