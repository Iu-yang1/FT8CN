package com.bg7yoz.ft8cn.satellite

import sgp4.TLE
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 对固定上游 SGP4 的窄包装。输入检查、坐标转换和站心观测均由 FT8CN 自有代码完成。
 */
class Sgp4OrbitPropagator private constructor(
    val record: TleRecord,
    private val tle: TLE,
) {
    fun propagate(utcMillis: Long): EciState {
        val minutesAfterEpoch = (utcMillis - record.epochUtcMillis) / 60_000.0
        val state = tle.getRV(minutesAfterEpoch)
        check(tle.sgp4Error == 0) { "SGP4 propagation error ${tle.sgp4Error}" }
        require(state.size == 2 && state[0].size == 3 && state[1].size == 3)
        require(state.all { vector -> vector.all(Double::isFinite) }) { "SGP4 返回非有限数值" }
        return EciState(utcMillis, state[0].copyOf(), state[1].copyOf())
    }

    fun observe(observer: ObserverPosition, utcMillis: Long): SatelliteObservation {
        val state = propagate(utcMillis)
        val earthFixed = temeToEarthFixed(state)
        val station = observerEarthFixed(observer)
        val dx = earthFixed.position[0] - station[0]
        val dy = earthFixed.position[1] - station[1]
        val dz = earthFixed.position[2] - station[2]
        val range = sqrt(dx * dx + dy * dy + dz * dz)
        require(range > 0.0 && range.isFinite()) { "站心距离无效" }

        val latitude = Math.toRadians(observer.latitudeDegrees)
        val longitude = Math.toRadians(observer.longitudeDegrees)
        val east = -sin(longitude) * dx + cos(longitude) * dy
        val north = -sin(latitude) * cos(longitude) * dx -
            sin(latitude) * sin(longitude) * dy + cos(latitude) * dz
        val up = cos(latitude) * cos(longitude) * dx +
            cos(latitude) * sin(longitude) * dy + sin(latitude) * dz
        val azimuth = normalizeDegrees(Math.toDegrees(atan2(east, north)))
        val elevation = Math.toDegrees(kotlin.math.asin((up / range).coerceIn(-1.0, 1.0)))
        val rangeRateKmPerSecond = (
            dx * earthFixed.velocity[0] +
                dy * earthFixed.velocity[1] +
                dz * earthFixed.velocity[2]
            ) / range
        val subpoint = earthFixedToGeodetic(earthFixed.position)

        return SatelliteObservation(
            utcMillis = utcMillis,
            azimuthDegrees = azimuth,
            elevationDegrees = elevation,
            rangeKilometers = range,
            rangeRateMetersPerSecond = rangeRateKmPerSecond * 1_000.0,
            subpointLatitudeDegrees = subpoint.latitudeDegrees,
            subpointLongitudeDegrees = subpoint.longitudeDegrees,
            altitudeKilometers = subpoint.altitudeKilometers,
        )
    }

    private data class EarthFixedState(val position: DoubleArray, val velocity: DoubleArray)
    private data class GeodeticPoint(
        val latitudeDegrees: Double,
        val longitudeDegrees: Double,
        val altitudeKilometers: Double,
    )

    private fun temeToEarthFixed(state: EciState): EarthFixedState {
        val theta = greenwichMeanSiderealTime(state.utcMillis)
        val c = cos(theta)
        val s = sin(theta)
        val r = state.positionKilometers
        val v = state.velocityKilometersPerSecond
        val x = c * r[0] + s * r[1]
        val y = -s * r[0] + c * r[1]
        val z = r[2]
        val rotatedVx = c * v[0] + s * v[1]
        val rotatedVy = -s * v[0] + c * v[1]
        return EarthFixedState(
            position = doubleArrayOf(x, y, z),
            velocity = doubleArrayOf(
                rotatedVx + EARTH_ROTATION_RADIANS_PER_SECOND * y,
                rotatedVy - EARTH_ROTATION_RADIANS_PER_SECOND * x,
                v[2],
            ),
        )
    }

    private fun observerEarthFixed(observer: ObserverPosition): DoubleArray {
        val latitude = Math.toRadians(observer.latitudeDegrees)
        val longitude = Math.toRadians(observer.longitudeDegrees)
        val altitudeKm = observer.altitudeMeters / 1_000.0
        val sinLatitude = sin(latitude)
        val normalRadius = WGS84_A_KM / sqrt(1.0 - WGS84_E2 * sinLatitude * sinLatitude)
        return doubleArrayOf(
            (normalRadius + altitudeKm) * cos(latitude) * cos(longitude),
            (normalRadius + altitudeKm) * cos(latitude) * sin(longitude),
            (normalRadius * (1.0 - WGS84_E2) + altitudeKm) * sinLatitude,
        )
    }

    private fun earthFixedToGeodetic(position: DoubleArray): GeodeticPoint {
        val x = position[0]
        val y = position[1]
        val z = position[2]
        val longitude = atan2(y, x)
        val horizontal = sqrt(x * x + y * y)
        var latitude = atan2(z, horizontal * (1.0 - WGS84_E2))
        var altitude = 0.0
        repeat(8) {
            val sinLatitude = sin(latitude)
            val radius = WGS84_A_KM / sqrt(1.0 - WGS84_E2 * sinLatitude * sinLatitude)
            altitude = if (cos(latitude).let { kotlin.math.abs(it) } > 1e-12) {
                horizontal / cos(latitude) - radius
            } else {
                kotlin.math.abs(z) - radius * (1.0 - WGS84_E2)
            }
            latitude = atan2(z, horizontal * (1.0 - WGS84_E2 * radius / (radius + altitude)))
        }
        return GeodeticPoint(
            latitudeDegrees = Math.toDegrees(latitude),
            longitudeDegrees = normalizeLongitude(Math.toDegrees(longitude)),
            altitudeKilometers = altitude,
        )
    }

    companion object {
        private const val WGS84_A_KM = 6_378.137
        private const val WGS84_FLATTENING = 1.0 / 298.257223563
        private const val WGS84_E2 = WGS84_FLATTENING * (2.0 - WGS84_FLATTENING)
        private const val EARTH_ROTATION_RADIANS_PER_SECOND = 7.29211514670698e-5
        private const val JULIAN_UNIX_EPOCH = 2_440_587.5
        private const val MILLIS_PER_DAY = 86_400_000.0

        fun parse(
            name: String,
            line1: String,
            line2: String,
            source: String = "manual",
            fetchedUtcMillis: Long = System.currentTimeMillis(),
        ): Sgp4OrbitPropagator {
            validateLine(line1, '1')
            validateLine(line2, '2')
            require(line1.substring(2, 7) == line2.substring(2, 7)) { "TLE catalog number 不一致" }
            val catalogNumber = line1.substring(2, 7).trim().toIntOrNull()
                ?: throw IllegalArgumentException("当前目录仅接受数字 NORAD catalog number")
            val parsed = TLE(line1, line2)
            require(parsed.parseErrors.isNullOrBlank()) { "TLE 解析失败: ${parsed.parseErrors}" }
            val epoch = parsed.epoch?.time ?: throw IllegalArgumentException("TLE epoch 缺失")
            return Sgp4OrbitPropagator(
                TleRecord(
                    name = name.trim().ifBlank { "NORAD $catalogNumber" }.take(80),
                    catalogNumber = catalogNumber,
                    line1 = line1,
                    line2 = line2,
                    epochUtcMillis = epoch,
                    source = source.take(80),
                    fetchedUtcMillis = fetchedUtcMillis,
                ),
                parsed,
            )
        }

        fun validateLine(line: String, expectedLineNumber: Char) {
            require(line.length == 69) { "TLE line 必须为 69 个 ASCII 字符" }
            require(line[0] == expectedLineNumber && line[1] == ' ') { "TLE line number 无效" }
            require(line.all { it.code in 32..126 }) { "TLE 只能包含 ASCII 可打印字符" }
            val expected = line.last().digitToIntOrNull()
                ?: throw IllegalArgumentException("TLE checksum 字符无效")
            val checksum = line.take(68).sumOf { character ->
                when {
                    character.isDigit() -> character.digitToInt()
                    character == '-' -> 1
                    else -> 0
                }
            } % 10
            require(checksum == expected) {
                String.format(Locale.US, "TLE checksum mismatch: expected %d actual %d", expected, checksum)
            }
        }

        private fun greenwichMeanSiderealTime(utcMillis: Long): Double {
            val julianDate = JULIAN_UNIX_EPOCH + utcMillis / MILLIS_PER_DAY
            val centuries = (julianDate - 2_451_545.0) / 36_525.0
            val seconds = 67_310.54841 +
                (876_600.0 * 3_600.0 + 8_640_184.812866) * centuries +
                0.093104 * centuries * centuries -
                6.2e-6 * centuries * centuries * centuries
            val normalizedSeconds = seconds - floor(seconds / 86_400.0) * 86_400.0
            return normalizedSeconds * (2.0 * PI / 86_400.0)
        }

        private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
        private fun normalizeLongitude(value: Double): Double = ((value + 540.0) % 360.0) - 180.0
    }
}
