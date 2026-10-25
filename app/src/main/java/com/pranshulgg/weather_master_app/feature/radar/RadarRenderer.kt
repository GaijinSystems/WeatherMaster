package com.pranshulgg.weather_master_app.feature.radar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

class RadarColors(
    val states: Int,
    val counties: Int,
    val roads: Int,
    val water: Int,
    val location: Int,
    val locationRing: Int,
    val site: Int,
    val siteLabel: Int
)

/**
 * Draws a radar scan, state/county outlines and markers to a bitmap centred on a location,
 * [widthKm] across. Uses a local flat projection around the centre, which is accurate enough at
 * radar range
 */
object RadarRenderer {

    private const val KM_PER_DEGREE = 111.2

    fun render(
        scan: RadarScan,
        outlines: RadarOutlines,
        centerLatitude: Double,
        centerLongitude: Double,
        widthKm: Double,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        colors: RadarColors
    ): Bitmap {
        val bitmap = createBitmap(widthPx, heightPx)
        val canvas = Canvas(bitmap)
        val pxPerKm = widthPx / widthKm
        val lonKmScale = cos(Math.toRadians(centerLatitude)) * KM_PER_DEGREE

        val toX = { lon: Double -> (widthPx / 2.0 + (lon - centerLongitude) * lonKmScale * pxPerKm).toFloat() }
        val toY = { lat: Double -> (heightPx / 2.0 - (lat - centerLatitude) * KM_PER_DEGREE * pxPerKm).toFloat() }

        val siteX = toX(scan.longitude)
        val siteY = toY(scan.latitude)

        // Visible area in degrees, for skipping polylines that can't be on screen
        val view = floatArrayOf(
            (centerLatitude - heightPx / 2.0 / pxPerKm / KM_PER_DEGREE).toFloat(),
            (centerLongitude - widthPx / 2.0 / pxPerKm / lonKmScale).toFloat(),
            (centerLatitude + heightPx / 2.0 / pxPerKm / KM_PER_DEGREE).toFloat(),
            (centerLongitude + widthPx / 2.0 / pxPerKm / lonKmScale).toFloat()
        )

        drawScan(canvas, scan, siteX, siteY, pxPerKm, widthPx, heightPx)
        drawLayer(canvas, outlines.water, view, toX, toY, colors.water, 0.8f * density)
        drawLayer(canvas, outlines.counties, view, toX, toY, colors.counties, 0.6f * density)
        drawLayer(canvas, outlines.roads, view, toX, toY, colors.roads, 0.8f * density)
        drawLayer(canvas, outlines.states, view, toX, toY, colors.states, 1.2f * density)
        drawSite(canvas, scan.station, siteX, siteY, density, colors)
        drawLocation(canvas, widthPx / 2f, heightPx / 2f, density, colors)

        return bitmap
    }

    private fun drawScan(
        canvas: Canvas,
        scan: RadarScan,
        siteX: Float,
        siteY: Float,
        pxPerKm: Double,
        widthPx: Int,
        heightPx: Int
    ) {
        // Only bins whose range can be on screen
        val viewRadiusPx = hypot(widthPx / 2.0, heightPx / 2.0)
        val siteDistancePx = hypot(siteX - widthPx / 2.0, siteY - heightPx / 2.0)
        val binPx = scan.binSizeKm * pxPerKm
        val firstBin = floor((siteDistancePx - viewRadiusPx) / binPx).toInt().coerceAtLeast(0)
        val lastBin = ceil((siteDistancePx + viewRadiusPx) / binPx).toInt().coerceAtMost(scan.binCount)
        if (firstBin >= lastBin) return

        // One path per data level, each radial split into runs of equal level
        val paths = arrayOfNulls<Path>(256)
        val levels = scan.levels

        for (radial in 0 until scan.radialCount) {
            val start = Math.toRadians(scan.startAngles[radial].toDouble())
            val end = Math.toRadians((scan.startAngles[radial] + scan.angleWidths[radial]).toDouble())
            val sinStart = sin(start).toFloat()
            val cosStart = cos(start).toFloat()
            val sinEnd = sin(end).toFloat()
            val cosEnd = cos(end).toFloat()
            val offset = radial * scan.binCount

            var bin = firstBin
            while (bin < lastBin) {
                val level = levels[offset + bin].toInt() and 0xFF
                var runEnd = bin + 1
                while (runEnd < lastBin && (levels[offset + runEnd].toInt() and 0xFF) == level) runEnd++

                if (level >= 2) {
                    val near = (bin * binPx).toFloat()
                    val far = (runEnd * binPx).toFloat()
                    val path = paths[level] ?: Path().also { paths[level] = it }
                    path.moveTo(siteX + near * sinStart, siteY - near * cosStart)
                    path.lineTo(siteX + far * sinStart, siteY - far * cosStart)
                    path.lineTo(siteX + far * sinEnd, siteY - far * cosEnd)
                    path.lineTo(siteX + near * sinEnd, siteY - near * cosEnd)
                    path.close()
                }
                bin = runEnd
            }
        }

        val paint = Paint().apply { style = Paint.Style.FILL }
        paths.forEachIndexed { level, path ->
            if (path != null) {
                paint.color = ReflectivityPalette.colors[level]
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun drawLayer(
        canvas: Canvas,
        layer: OutlineLayer,
        view: FloatArray,
        toX: (Double) -> Float,
        toY: (Double) -> Float,
        color: Int,
        strokeWidth: Float
    ) {
        val path = Path()
        val bounds = layer.bounds
        for (line in 0 until layer.polylineCount) {
            val b = line * 4
            if (bounds[b + 2] < view[0] || bounds[b] > view[2] ||
                bounds[b + 3] < view[1] || bounds[b + 1] > view[3]
            ) continue
            val first = layer.starts[line]
            path.moveTo(toX(layer.longitudes[first].toDouble()), toY(layer.latitudes[first].toDouble()))
            for (point in first + 1 until layer.starts[line + 1]) {
                path.lineTo(toX(layer.longitudes[point].toDouble()), toY(layer.latitudes[point].toDouble()))
            }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            this.color = color
            this.strokeWidth = strokeWidth
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSite(
        canvas: Canvas,
        station: String,
        x: Float,
        y: Float,
        density: Float,
        colors: RadarColors
    ) {
        val size = 5f * density
        val marker = Path().apply {
            moveTo(x, y - size)
            lineTo(x + size, y + size * 0.8f)
            lineTo(x - size, y + size * 0.8f)
            close()
        }
        canvas.drawPath(marker, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.site })
        canvas.drawText(
            station,
            x + size * 1.6f,
            y + size * 0.6f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.siteLabel
                textSize = 11f * density
            }
        )
    }

    private fun drawLocation(canvas: Canvas, x: Float, y: Float, density: Float, colors: RadarColors) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = colors.locationRing
        canvas.drawCircle(x, y, 7f * density, paint)
        paint.color = colors.location
        canvas.drawCircle(x, y, 5f * density, paint)
    }
}
