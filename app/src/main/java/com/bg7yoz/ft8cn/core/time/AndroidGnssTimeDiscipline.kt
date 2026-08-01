package com.bg7yoz.ft8cn.core.time

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssClock
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import kotlin.math.max

/**
 * 仅提交可信 GNSS 时间，不记录或暴露坐标。位置 fix 只用于判断 GNSS 是否真实可用。
 */
class AndroidGnssTimeDiscipline @JvmOverloads constructor(
    context: Context,
    private val submitSample: (ClockSample) -> Boolean = DisciplinedClockRegistry::submitSample,
) {
    private val applicationContext = context.applicationContext
    private val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val directExecutor = Executor { command -> command.run() }
    private var started = false
    private var lastTrustedFixElapsedNanos = Long.MIN_VALUE
    private var lastDiscontinuityCount: Int? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!location.isFromMockProvider) {
                lastTrustedFixElapsedNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    location.elapsedRealtimeNanos
                } else {
                    SystemClock.elapsedRealtimeNanos()
                }
            }
        }

        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            acceptGnssClock(eventArgs.clock)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): StartResult {
        if (started) return StartResult.ALREADY_STARTED
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return StartResult.UNSUPPORTED
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return StartResult.PERMISSION_REQUIRED
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return StartResult.PROVIDER_DISABLED
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1_000L,
            0f,
            locationListener,
        )
        val registered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.registerGnssMeasurementsCallback(directExecutor, measurementsCallback)
        } else {
            @Suppress("DEPRECATION")
            locationManager.registerGnssMeasurementsCallback(measurementsCallback)
        }
        if (!registered) {
            locationManager.removeUpdates(locationListener)
            return StartResult.REGISTRATION_FAILED
        }
        started = true
        return StartResult.STARTED
    }

    fun stop() {
        if (!started) return
        locationManager.removeUpdates(locationListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationManager.unregisterGnssMeasurementsCallback(measurementsCallback)
        }
        started = false
        lastTrustedFixElapsedNanos = Long.MIN_VALUE
    }

    private fun acceptGnssClock(clock: GnssClock) {
        if (!clock.hasFullBiasNanos() || !clock.hasLeapSecond()) return
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        if (lastTrustedFixElapsedNanos == Long.MIN_VALUE
            || nowElapsed - lastTrustedFixElapsedNanos > MAX_FIX_AGE_NANOS
        ) {
            return
        }
        val discontinuity = clock.hardwareClockDiscontinuityCount
        val previousDiscontinuity = lastDiscontinuityCount
        lastDiscontinuityCount = discontinuity
        if (previousDiscontinuity != null && previousDiscontinuity != discontinuity) {
            return
        }

        val sampleElapsed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            && clock.hasElapsedRealtimeNanos()
        ) {
            clock.elapsedRealtimeNanos
        } else {
            nowElapsed
        }
        val biasNanos = if (clock.hasBiasNanos()) clock.biasNanos else 0.0
        val utcMillis = gnssClockToUtcMillis(
            timeNanos = clock.timeNanos,
            fullBiasNanos = clock.fullBiasNanos,
            biasNanos = biasNanos,
            leapSeconds = clock.leapSecond,
        )
        val timeUncertaintyMillis = when {
            clock.hasTimeUncertaintyNanos() -> max(1.0, clock.timeUncertaintyNanos / 1_000_000.0)
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 20.0
            else -> 5.0
        }
        submitSample(
            ClockSample(
                utcMillis = utcMillis,
                monotonicNanos = sampleElapsed,
                uncertaintyMillis = timeUncertaintyMillis,
                source = ClockSource.GNSS,
                detail = "GNSS time fix",
            ),
        )
    }

    enum class StartResult {
        STARTED,
        ALREADY_STARTED,
        PERMISSION_REQUIRED,
        PROVIDER_DISABLED,
        UNSUPPORTED,
        REGISTRATION_FAILED,
    }

    companion object {
        private const val GPS_EPOCH_UNIX_MILLIS = 315_964_800_000.0
        private const val MAX_FIX_AGE_NANOS = 120_000_000_000L

        @JvmStatic
        fun gnssClockToUtcMillis(
            timeNanos: Long,
            fullBiasNanos: Long,
            biasNanos: Double,
            leapSeconds: Int,
        ): Double {
            val gpsNanos = timeNanos - (fullBiasNanos + biasNanos)
            return GPS_EPOCH_UNIX_MILLIS + gpsNanos / 1_000_000.0 - leapSeconds * 1_000.0
        }
    }
}
