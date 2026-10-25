package com.pranshulgg.weather_master_app.feature.radar

import android.graphics.Color

/**
 * Colors for N0Q base reflectivity data levels (dBZ = level / 2 - 33). Levels 0 and 1 (below
 * threshold, range folded) are transparent
 *
 * Color stops from wX's default "CODENH" palette (https://gitlab.com/joshua.tee/wx, GPL-3.0),
 * interpolated in HSV like wX's UtilityNexradColors.interpolateColor
 */
object ReflectivityPalette {

    private val stops = listOf(
        -20.0 to Color.rgb(64, 64, 80),
        -6.0 to Color.rgb(100, 100, 130),
        -5.0 to Color.rgb(31, 42, 104),
        0.0 to Color.rgb(53, 77, 164),
        11.0 to Color.rgb(79, 118, 236),
        12.0 to Color.rgb(3, 101, 31),
        20.0 to Color.rgb(41, 172, 49),
        31.0 to Color.rgb(80, 245, 69),
        32.0 to Color.rgb(255, 247, 39),
        40.0 to Color.rgb(255, 176, 28),
        50.0 to Color.rgb(255, 84, 13),
        63.0 to Color.rgb(188, 0, 0),
        64.0 to Color.rgb(231, 7, 203),
        75.0 to Color.rgb(253, 132, 250),
        76.0 to Color.rgb(190, 158, 245),
        80.0 to Color.rgb(0, 235, 230),
        81.0 to Color.rgb(255, 255, 255),
        95.0 to Color.rgb(255, 255, 255),
    )

    val colors: IntArray = IntArray(256) { level ->
        if (level < 2) Color.TRANSPARENT else colorFor(level / 2.0 - 33)
    }

    private fun colorFor(dbz: Double): Int {
        if (dbz <= stops.first().first) return stops.first().second
        if (dbz >= stops.last().first) return stops.last().second
        val upper = stops.indexOfFirst { it.first > dbz }
        val (lowDbz, lowColor) = stops[upper - 1]
        val (highDbz, highColor) = stops[upper]
        return interpolate(lowColor, highColor, ((dbz - lowDbz) / (highDbz - lowDbz)).toFloat())
    }

    private fun interpolate(from: Int, to: Int, fraction: Float): Int {
        val a = FloatArray(3).also { Color.colorToHSV(from, it) }
        val b = FloatArray(3).also { Color.colorToHSV(to, it) }
        var hueDiff = b[0] - a[0]
        if (hueDiff > 180) hueDiff -= 360 else if (hueDiff < -180) hueDiff += 360
        val hue = (a[0] + hueDiff * fraction + 360) % 360
        return Color.HSVToColor(
            floatArrayOf(hue, a[1] + (b[1] - a[1]) * fraction, a[2] + (b[2] - a[2]) * fraction)
        )
    }
}
