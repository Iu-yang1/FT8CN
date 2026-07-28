package com.bg7yoz.ft8cn.satellite

data class ObserverPosition(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val altitudeMeters: Double = 0.0,
) {
    init {
        require(latitudeDegrees in -90.0..90.0) { "纬度必须在 -90..90 度" }
        require(longitudeDegrees in -180.0..180.0) { "经度必须在 -180..180 度" }
        require(altitudeMeters.isFinite() && altitudeMeters >= -500.0) { "海拔无效" }
    }
}

data class TleRecord(
    val name: String,
    val catalogNumber: Int,
    val line1: String,
    val line2: String,
    val epochUtcMillis: Long,
    val source: String,
    val fetchedUtcMillis: Long,
)

data class EciState(
    val utcMillis: Long,
    val positionKilometers: DoubleArray,
    val velocityKilometersPerSecond: DoubleArray,
)

data class SatelliteObservation(
    val utcMillis: Long,
    val azimuthDegrees: Double,
    val elevationDegrees: Double,
    val rangeKilometers: Double,
    /** 正值表示卫星正在远离观察者。 */
    val rangeRateMetersPerSecond: Double,
    val subpointLatitudeDegrees: Double,
    val subpointLongitudeDegrees: Double,
    val altitudeKilometers: Double,
)

data class SatellitePass(
    val aosUtcMillis: Long,
    val tcaUtcMillis: Long,
    val losUtcMillis: Long,
    val maximumElevationDegrees: Double,
    val aosAzimuthDegrees: Double,
    val losAzimuthDegrees: Double,
    val ongoingAtWindowStart: Boolean,
    val tleStale: Boolean,
)

data class SatelliteTransponder(
    val name: String,
    val uplinkLowHz: Long?,
    val uplinkHighHz: Long?,
    val downlinkLowHz: Long?,
    val downlinkHighHz: Long?,
    val mode: String,
    val inverted: Boolean,
)

data class SatelliteFrequencyTarget(
    val generatedUtcMillis: Long,
    val rxFrequencyHz: Long,
    val txFrequencyHz: Long,
    val receiveDopplerHz: Double,
    val transmitDopplerHz: Double,
    val rangeRateMetersPerSecond: Double,
)
