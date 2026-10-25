package com.pranshulgg.weather_master_app.core.network.sources.radar.nws

import com.pranshulgg.weather_master_app.core.model.domain.location.Location
import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import com.pranshulgg.weather_master_app.core.network.calls.safeApiCall
import com.pranshulgg.weather_master_app.core.network.sources.weather.nws.NwsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NwsRadarRepository @Inject constructor(
    private val api: NwsApi,
    private val radarApi: NwsRadarApi
) {

    // Radar station per location id (e.g. "KEAX"); it doesn't change for a location
    private val stations = ConcurrentHashMap<String, String>()

    suspend fun getRadarStation(location: Location): String {
        stations[location.id]?.let { return it }

        val gridPoint = safeApiCall {
            api.fetchGridPoints(location.latitude, location.longitude)
        }.getOrThrow()

        val station = gridPoint.points.radarStation?.takeIf { it.isNotBlank() }
            ?: throw IOException("No radar station for this location")

        stations[location.id] = station
        return station
    }

    suspend fun getLatestScan(station: String): RadarScan {
        val bytes = safeApiCall {
            radarApi.fetchLatestProduct(BASE_REFLECTIVITY, station.lowercase(Locale.US))
        }.getOrThrow().use { it.bytes() }

        return withContext(Dispatchers.Default) {
            NexradLevel3Decoder.decode(bytes, station)
        }
    }

    companion object {
        private const val BASE_REFLECTIVITY = "DS.p94r0" // N0Q
    }
}
