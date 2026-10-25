package com.pranshulgg.weather_master_app.feature.radar

import android.content.Context
import com.pranshulgg.weather_master_app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.SoftReference
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One outline layer as polylines. Points of polyline i are [starts][i] until [starts][i + 1];
 * [bounds] holds minLat, minLon, maxLat, maxLon per polyline
 */
class OutlineLayer(
    val starts: IntArray,
    val latitudes: FloatArray,
    val longitudes: FloatArray,
    val bounds: FloatArray
) {
    val polylineCount: Int get() = starts.size - 1
}

/**
 * Map outlines drawn with the radar: US states and counties (US Census Bureau 2024 cartographic
 * boundary files, 1:500k), roads (Census TIGER 2024 primary and secondary roads, split into
 * Interstate, US and State routes) and rivers and lakes (Natural Earth 10m; major = its worldwide
 * datasets, minor = its North America supplement). All public domain. Roads and water are split
 * so less is drawn when zoomed out
 *
 * File format (big-endian), per polyline, coordinates in units of 1e-5 degree:
 * int32 point count, int32 lat and int32 lon of the first point, then int16 dLat, int16 dLon steps
 */
class RadarOutlines(
    val states: OutlineLayer,
    val counties: OutlineLayer,
    val interstates: OutlineLayer,
    val usRoutes: OutlineLayer,
    val stateRoutes: OutlineLayer,
    val majorWater: OutlineLayer,
    val minorWater: OutlineLayer
) {

    companion object {
        private val mutex = Mutex()

        // About 17 MB once loaded; soft so it can be reclaimed while the radar isn't used
        private var loaded: SoftReference<RadarOutlines>? = null

        suspend fun load(context: Context): RadarOutlines = mutex.withLock {
            loaded?.get() ?: withContext(Dispatchers.IO) {
                RadarOutlines(
                    states = readLayer(context, R.raw.radar_states),
                    counties = readLayer(context, R.raw.radar_counties),
                    interstates = readLayer(context, R.raw.radar_roads_interstate),
                    usRoutes = readLayer(context, R.raw.radar_roads_us),
                    stateRoutes = readLayer(context, R.raw.radar_roads_state),
                    majorWater = readLayer(context, R.raw.radar_water_major),
                    minorWater = readLayer(context, R.raw.radar_water_minor)
                )
            }.also { loaded = SoftReference(it) }
        }

        private fun readLayer(context: Context, resourceId: Int): OutlineLayer {
            val bytes = context.resources.openRawResource(resourceId).use { it.readBytes() }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            // First pass: count polylines and points to size the arrays
            var polylines = 0
            var points = 0
            while (buffer.remaining() >= 12) {
                val count = buffer.int
                buffer.position(buffer.position() + 8 + (count - 1) * 4)
                polylines++
                points += count
            }
            buffer.rewind()

            val starts = IntArray(polylines + 1)
            val latitudes = FloatArray(points)
            val longitudes = FloatArray(points)
            val bounds = FloatArray(polylines * 4)
            var point = 0

            for (line in 0 until polylines) {
                val count = buffer.int
                var lat = buffer.int
                var lon = buffer.int
                starts[line] = point
                var minLat = lat
                var maxLat = lat
                var minLon = lon
                var maxLon = lon
                latitudes[point] = lat / 1e5f
                longitudes[point] = lon / 1e5f
                point++
                repeat(count - 1) {
                    lat += buffer.short
                    lon += buffer.short
                    if (lat < minLat) minLat = lat else if (lat > maxLat) maxLat = lat
                    if (lon < minLon) minLon = lon else if (lon > maxLon) maxLon = lon
                    latitudes[point] = lat / 1e5f
                    longitudes[point] = lon / 1e5f
                    point++
                }
                bounds[line * 4] = minLat / 1e5f
                bounds[line * 4 + 1] = minLon / 1e5f
                bounds[line * 4 + 2] = maxLat / 1e5f
                bounds[line * 4 + 3] = maxLon / 1e5f
            }
            starts[polylines] = point

            return OutlineLayer(starts, latitudes, longitudes, bounds)
        }
    }
}
