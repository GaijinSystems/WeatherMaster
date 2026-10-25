package com.pranshulgg.weather_master_app.core.network.sources.radar.nws

import com.pranshulgg.weather_master_app.core.model.domain.AppException
import com.pranshulgg.weather_master_app.core.model.domain.location.Location
import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import com.pranshulgg.weather_master_app.core.model.sources.Source
import com.pranshulgg.weather_master_app.core.network.calls.safeApiCall
import com.pranshulgg.weather_master_app.core.network.sources.weather.nws.NwsApi
import com.pranshulgg.weather_master_app.data.repository.radar.RadarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NwsRadarRepository @Inject constructor(
    private val api: NwsApi,
    private val radarApi: NwsRadarApi
) : RadarRepository {

    override val radarSource = Source.NWS

    // Radar station (e.g. "KEAX") per position rounded to ~1 km, not per location id: the device
    // location keeps its id when it moves
    private val stations = ConcurrentHashMap<String, String>()

    override suspend fun getLatestScan(location: Location): RadarScan {
        val station = getRadarStation(location)

        val body = safeApiCall {
            radarApi.fetchLatestProduct(BASE_REFLECTIVITY, station.lowercase(Locale.US))
        }.getOrThrow()

        val bytes = body.use {
            withContext(Dispatchers.IO) {
                // N0Q files are ~40 KB; refuse anything far larger
                it.byteStream().readNBytesCompat(MAX_PRODUCT_BYTES + 1)
                    .also { data -> if (data.size > MAX_PRODUCT_BYTES) throw AppException.Server() }
            }
        }

        return withContext(Dispatchers.Default) {
            NexradLevel3Decoder.decode(bytes, station)
        }
    }

    private suspend fun getRadarStation(location: Location): String {
        val key = String.format(Locale.US, "%.2f,%.2f", location.latitude, location.longitude)
        stations[key]?.let { return it }

        val gridPoint = safeApiCall {
            api.fetchGridPoints(location.latitude, location.longitude)
        }.getOrThrow()

        val station = gridPoint.points.radarStation?.takeIf { it.isNotBlank() }
            ?: throw AppException.NotFound()

        stations[key] = station
        return station
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private const val BASE_REFLECTIVITY = "DS.p94r0" // N0Q
        private const val MAX_PRODUCT_BYTES = 2 * 1024 * 1024
    }
}
