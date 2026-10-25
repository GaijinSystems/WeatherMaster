package com.pranshulgg.weather_master_app.feature.radar

import android.content.Context
import com.pranshulgg.weather_master_app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * US state and county outlines drawn under the radar
 *
 * Data from wX (https://gitlab.com/joshua.tee/wx, GPL-3.0), res/raw/statev2 and res/raw/county:
 * big-endian floats, 4 per line segment (lat1, lon1, lat2, lon2) with longitudes positive west
 */
class RadarOutlines(val states: FloatArray, val counties: FloatArray) {

    companion object {
        private val mutex = Mutex()
        private var loaded: RadarOutlines? = null

        suspend fun load(context: Context): RadarOutlines = mutex.withLock {
            loaded ?: withContext(Dispatchers.IO) {
                RadarOutlines(
                    states = readSegments(context, R.raw.radar_states),
                    counties = readSegments(context, R.raw.radar_counties)
                )
            }.also { loaded = it }
        }

        private fun readSegments(context: Context, resourceId: Int): FloatArray {
            val bytes = context.resources.openRawResource(resourceId).use { it.readBytes() }
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer()
            return FloatArray(floats.remaining()).also { floats.get(it) }
        }
    }
}
