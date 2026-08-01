package com.bg7yoz.ft8cn.feature.satellite

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

internal data class DeviceOrientation(
    val available: Boolean = false,
    val azimuthDegrees: Float = 0f,
    val elevationDegrees: Float = 0f,
    val source: String = "姿态传感器不可用",
)

/**
 * 旋转矢量由陀螺仪、加速度计和磁力计融合得到，比单独积分陀螺仪更适合持续指北。
 * 传感器回调限制为约 20 Hz，避免卫星页面造成无意义的高频重组。
 */
@Composable
internal fun rememberDeviceOrientation(): State<DeviceOrientation> {
    val context = LocalContext.current
    val orientation = remember { mutableStateOf(DeviceOrientation()) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val angles = FloatArray(3)
        val gravity = FloatArray(3)
        val magnetic = FloatArray(3)
        var haveGravity = false
        var haveMagnetic = false
        var lastPublishedNanos = 0L

        fun publish(matrix: FloatArray, source: String, timestampNanos: Long) {
            if (timestampNanos - lastPublishedNanos < ORIENTATION_UPDATE_INTERVAL_NANOS) return
            lastPublishedNanos = timestampNanos
            remapForDisplay(context, matrix, remappedMatrix)
            SensorManager.getOrientation(remappedMatrix, angles)
            val azimuth = ((Math.toDegrees(angles[0].toDouble()) + 360.0) % 360.0).toFloat()
            val elevation = (-Math.toDegrees(angles[1].toDouble())).toFloat().coerceIn(-90f, 90f)
            orientation.value = DeviceOrientation(true, azimuth, elevation, source)
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        publish(rotationMatrix, "陀螺仪融合", event.timestamp)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        lowPass(event.values, gravity)
                        haveGravity = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        lowPass(event.values, magnetic)
                        haveMagnetic = true
                    }
                }
                if (rotationSensor == null && haveGravity && haveMagnetic &&
                    SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnetic)
                ) {
                    publish(rotationMatrix, "加速度计与磁力计", event.timestamp)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            val gravityRegistered = accelerometer != null &&
                sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            val magneticRegistered = magneticField != null &&
                sensorManager.registerListener(listener, magneticField, SensorManager.SENSOR_DELAY_GAME)
            gravityRegistered && magneticRegistered
        }
        if (!registered) orientation.value = DeviceOrientation()

        onDispose { sensorManager.unregisterListener(listener) }
    }
    return orientation
}

private fun remapForDisplay(context: Context, source: FloatArray, target: FloatArray) {
    @Suppress("DEPRECATION")
    val rotation = (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    val axes = when (rotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }
    SensorManager.remapCoordinateSystem(source, axes.first, axes.second, target)
}

private fun lowPass(input: FloatArray, output: FloatArray) {
    for (index in 0 until minOf(input.size, output.size)) {
        output[index] += SENSOR_FILTER_ALPHA * (input[index] - output[index])
    }
}

internal fun DeviceOrientation.summary(): String = if (available) {
    "手机 ${azimuthDegrees.roundToInt()}° / 仰角 ${elevationDegrees.roundToInt()}° · $source"
} else {
    source
}

private const val SENSOR_FILTER_ALPHA = 0.15f
private const val ORIENTATION_UPDATE_INTERVAL_NANOS = 50_000_000L
