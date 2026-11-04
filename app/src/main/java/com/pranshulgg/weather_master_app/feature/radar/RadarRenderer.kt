package com.pranshulgg.weather_master_app.feature.radar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
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

private const val KM_PER_DEGREE = 111.2
private const val EARTH_RADIUS_KM = KM_PER_DEGREE * 180 / Math.PI

// Level of detail: view widths (km) above which less is drawn
private const val DETAIL_FULL_KM = 150.0
private const val DETAIL_MEDIUM_KM = 400.0

/** Distance in km on the same local flat projection the renderer uses */
internal fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val east = (lon2 - lon1) * cos(Math.toRadians(lat1)) * KM_PER_DEGREE
    val north = (lat2 - lat1) * KM_PER_DEGREE
    return hypot(east, north)
}

/** Local flat projection around a centre point, degrees to pixels */
private class Projection(centerLat: Double, centerLon: Double, pxPerKm: Double, widthPx: Int, heightPx: Int) {
    private val scaleX = (cos(Math.toRadians(centerLat)) * KM_PER_DEGREE * pxPerKm).toFloat()
    private val scaleY = (-KM_PER_DEGREE * pxPerKm).toFloat()
    private val offsetX = (widthPx / 2.0 - centerLon * scaleX).toFloat()
    private val offsetY = (heightPx / 2.0 - centerLat * scaleY).toFloat()

    // Visible area in degrees: minLat, minLon, maxLat, maxLon
    val view = floatArrayOf(
        ((heightPx - offsetY) / scaleY), -offsetX / scaleX, -offsetY / scaleY, (widthPx - offsetX) / scaleX
    )

    fun x(lon: Float) = lon * scaleX + offsetX
    fun y(lat: Float) = lat * scaleY + offsetY
}

/**
 * Draws radar echoes and the map (outlines and markers) to separate bitmaps centred on a
 * location, [widthKm] across. Uses a local flat projection around the centre, which is accurate enough at radar range
 */
object RadarRenderer {

    /**
     * Radar echoes only, on a transparent bitmap. [scale] below 1 draws at lower resolution (for
     * loop frames); the bitmap is then shown enlarged by 1 / [scale]
     */
    fun renderEchoes(
        scan: RadarScan,
        centerLatitude: Double,
        centerLongitude: Double,
        widthKm: Double,
        widthPx: Int,
        heightPx: Int,
        scale: Float = 1f
    ): Bitmap {
        val width = (widthPx * scale).toInt().coerceAtLeast(1)
        val height = (heightPx * scale).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val pxPerKm = width / widthKm
        drawScan(Canvas(bitmap), scan, Projection(centerLatitude, centerLongitude, pxPerKm, width, height), pxPerKm)
        return bitmap
    }

    /**
     * Map outlines, the radar site and the location marker on a transparent bitmap, drawn over the
     * echoes. Shared by every loop frame
     */
    suspend fun renderMap(
        site: RadarScan,
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
        val projection = Projection(centerLatitude, centerLongitude, widthPx / widthKm, widthPx, heightPx)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        val layers = buildList {
            add(Triple(outlines.majorWater, colors.water, 0.8f))
            when {
                widthKm <= DETAIL_FULL_KM -> {
                    add(Triple(outlines.minorWater, colors.water, 0.8f))
                    add(Triple(outlines.counties, colors.counties, 0.6f))
                    add(Triple(outlines.stateRoutes, colors.roads, 0.8f))
                    add(Triple(outlines.usRoutes, colors.roads, 0.8f))
                }

                widthKm <= DETAIL_MEDIUM_KM -> {
                    add(Triple(outlines.counties, withHalfAlpha(colors.counties), 0.6f))
                    add(Triple(outlines.usRoutes, colors.roads, 0.8f))
                }
                // Wider: no counties, Interstates only
            }
            add(Triple(outlines.interstates, colors.roads, 0.8f))
            add(Triple(outlines.states, colors.states, 2f))
        }
        for ((layer, color, widthDp) in layers) {
            currentCoroutineContext().ensureActive() // stop early if a newer render replaced this one
            linePaint.color = color
            linePaint.strokeWidth = widthDp * density
            drawLayer(canvas, layer, projection, linePaint)
        }
        val siteX = projection.x(site.longitude.toFloat())
        val siteY = projection.y(site.latitude.toFloat())
        drawSite(canvas, site.station, siteX, siteY, density, colors, markerPaint)
        drawLocation(canvas, widthPx / 2f, heightPx / 2f, density, colors, markerPaint)

        return bitmap
    }

    private fun withHalfAlpha(color: Int) =
        Color.argb(Color.alpha(color) / 2, Color.red(color), Color.green(color), Color.blue(color))

    /**
     * Radar cells are placed by computing the latitude/longitude of their corners on the sphere
     * (distance and bearing from the site) and projecting them like the outlines, so echoes line
     * up with the map at any distance from the site
     */
    private fun drawScan(
        canvas: Canvas,
        scan: RadarScan,
        projection: Projection,
        pxPerKm: Double
    ) {
        val widthPx = canvas.width
        val heightPx = canvas.height
        val siteX = projection.x(scan.longitude.toFloat())
        val siteY = projection.y(scan.latitude.toFloat())
        // Only bins whose range can be on screen
        val viewRadiusPx = hypot(widthPx / 2.0, heightPx / 2.0)
        val siteDistancePx = hypot(siteX - widthPx / 2.0, siteY - heightPx / 2.0)
        val binPx = scan.binSizeKm * pxPerKm
        // The flat projection differs from true range by a few percent far from the site, so keep
        // a generous margin: culling is only an optimisation
        val marginBins = 2 + (0.05 * (siteDistancePx + viewRadiusPx) / binPx).toInt()
        val firstBin = (floor((siteDistancePx - viewRadiusPx) / binPx).toInt() - marginBins).coerceAtLeast(0)
        val lastBin = (ceil((siteDistancePx + viewRadiusPx) / binPx).toInt() + marginBins).coerceAtMost(scan.binCount)
        if (firstBin >= lastBin) return

        // Screen positions of range boundaries firstBin..lastBin along each distinct edge angle
        val boundaries = lastBin - firstBin + 1
        val sinDistance = FloatArray(boundaries)
        val cosDistance = FloatArray(boundaries)
        for (i in 0 until boundaries) {
            val angularDistance = (firstBin + i) * scan.binSizeKm / EARTH_RADIUS_KM
            sinDistance[i] = sin(angularDistance).toFloat()
            cosDistance[i] = cos(angularDistance).toFloat()
        }
        val siteLat = Math.toRadians(scan.latitude)
        val siteLon = Math.toRadians(scan.longitude)
        val sinSiteLat = sin(siteLat)
        val cosSiteLat = cos(siteLat)
        val edgeIndex = IntArray(3600) { -1 }
        val edgeX = ArrayList<FloatArray>()
        val edgeY = ArrayList<FloatArray>()

        fun edge(angleDegrees: Float): Int {
            val key = Math.floorMod(Math.round(angleDegrees * 10), 3600)
            if (edgeIndex[key] >= 0) return edgeIndex[key]
            val bearing = Math.toRadians(key / 10.0)
            val sinBearing = sin(bearing)
            val cosBearing = cos(bearing)
            val xs = FloatArray(boundaries)
            val ys = FloatArray(boundaries)
            for (i in 0 until boundaries) {
                val sinLat = sinSiteLat * cosDistance[i] + cosSiteLat * sinDistance[i] * cosBearing
                val lat = asin(sinLat)
                val lon = siteLon + atan2(sinBearing * sinDistance[i] * cosSiteLat, cosDistance[i] - sinSiteLat * sinLat)
                xs[i] = projection.x(Math.toDegrees(lon).toFloat())
                ys[i] = projection.y(Math.toDegrees(lat).toFloat())
            }
            edgeIndex[key] = edgeX.size
            edgeX.add(xs)
            edgeY.add(ys)
            return edgeIndex[key]
        }

        // One path per data level, each radial split into runs of equal level
        val palette = ReflectivityPalette.colors
        val paths = arrayOfNulls<Path>(palette.size)
        val levels = scan.levels

        for (radial in 0 until scan.radialCount) {
            val start = edge(scan.startAngles[radial])
            val end = edge(scan.startAngles[radial] + scan.angleWidths[radial])
            val startX = edgeX[start]
            val startY = edgeY[start]
            val endX = edgeX[end]
            val endY = edgeY[end]
            val offset = radial * scan.binCount

            var bin = firstBin
            while (bin < lastBin) {
                val level = levels[offset + bin].toInt() and 0xFF
                var runEnd = bin + 1
                while (runEnd < lastBin && (levels[offset + runEnd].toInt() and 0xFF) == level) runEnd++

                if (Color.alpha(palette[level]) != 0) {
                    val near = bin - firstBin
                    val far = runEnd - firstBin
                    val path = paths[level] ?: Path().also { paths[level] = it }
                    path.moveTo(startX[near], startY[near])
                    path.lineTo(startX[far], startY[far])
                    path.lineTo(endX[far], endY[far])
                    path.lineTo(endX[near], endY[near])
                    path.close()
                }
                bin = runEnd
            }
        }

        val paint = Paint().apply { style = Paint.Style.FILL }
        paths.forEachIndexed { level, path ->
            if (path != null) {
                paint.color = palette[level]
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun drawLayer(canvas: Canvas, layer: OutlineLayer, projection: Projection, paint: Paint) {
        val path = Path()
        val bounds = layer.bounds
        val view = projection.view
        for (line in 0 until layer.polylineCount) {
            val b = line * 4
            if (bounds[b + 2] < view[0] || bounds[b] > view[2] ||
                bounds[b + 3] < view[1] || bounds[b + 1] > view[3]
            ) continue
            val first = layer.starts[line]
            val last = layer.starts[line + 1] - 1
            var lastX = projection.x(layer.longitudes[first])
            var lastY = projection.y(layer.latitudes[first])
            path.moveTo(lastX, lastY)
            for (point in first + 1..last) {
                val x = projection.x(layer.longitudes[point])
                val y = projection.y(layer.latitudes[point])
                // Skip points within a pixel of the last one drawn (most of them when zoomed out)
                if (point != last && abs(x - lastX) < 1f && abs(y - lastY) < 1f) continue
                path.lineTo(x, y)
                lastX = x
                lastY = y
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSite(
        canvas: Canvas,
        station: String,
        x: Float,
        y: Float,
        density: Float,
        colors: RadarColors,
        paint: Paint
    ) {
        val size = 5f * density
        val marker = Path().apply {
            moveTo(x, y - size)
            lineTo(x + size, y + size * 0.8f)
            lineTo(x - size, y + size * 0.8f)
            close()
        }
        paint.color = colors.site
        canvas.drawPath(marker, paint)
        paint.color = colors.siteLabel
        paint.textSize = 11f * density
        canvas.drawText(station, x + size * 1.6f, y + size * 0.6f, paint)
    }

    private fun drawLocation(
        canvas: Canvas,
        x: Float,
        y: Float,
        density: Float,
        colors: RadarColors,
        paint: Paint
    ) {
        paint.color = colors.locationRing
        canvas.drawCircle(x, y, 7f * density, paint)
        paint.color = colors.location
        canvas.drawCircle(x, y, 5f * density, paint)
    }
}
