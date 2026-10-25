package com.pranshulgg.weather_master_app.core.model.domain.radar

/**
 * One radar sweep: radials around the radar site, each with [binCount] range bins of [binSizeKm]
 * Angles are compass degrees (0 = north, clockwise). [levels] holds radialCount * binCount data levels
 */
class RadarScan(
    val station: String,
    val latitude: Double,
    val longitude: Double,
    val scanTimeMilli: Long,
    val binCount: Int,
    val binSizeKm: Double,
    val startAngles: FloatArray,
    val angleWidths: FloatArray,
    val levels: ByteArray
) {
    val radialCount: Int get() = startAngles.size
}
