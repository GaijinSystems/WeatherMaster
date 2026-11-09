package com.pranshulgg.weather_master_app.core.network.sources.radar.nws

import com.pranshulgg.weather_master_app.core.model.domain.AppException
import com.pranshulgg.weather_master_app.core.model.domain.location.Location
import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import com.pranshulgg.weather_master_app.core.network.calls.safeApiCall
import com.pranshulgg.weather_master_app.core.network.sources.weather.nws.NwsApi
import com.pranshulgg.weather_master_app.data.repository.radar.RadarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

@Singleton
class NwsRadarRepository @Inject constructor(
    private val api: NwsApi,
    private val radarApi: NwsRadarApi
) : RadarRepository {

    // Radar station (e.g. "KEAX") per position rounded to ~1 km, not per location id: the device
    // location keeps its id when it moves
    private val stations = ConcurrentHashMap<String, String>()

    override suspend fun getLatestScan(location: Location): RadarScan {
        val station = getRadarStation(location)
        return downloadScan(station) {
            radarApi.fetchProduct(BASE_REFLECTIVITY, station.lowercase(Locale.US), LATEST_FILE)
        }
    }

    /**
     * Picks files by their listed times relative to the newest one (so the listing's time zone
     * doesn't matter), downloads them a few at a time and keeps those within [period] of the newest
     * scan. A file that fails is skipped
     */
    override suspend fun getRecentScans(location: Location, period: Duration): List<RadarScan> {
        val station = getRadarStation(location)
        val stationPath = station.lowercase(Locale.US)

        val listing = safeApiCall {
            radarApi.fetchProductListing(BASE_REFLECTIVITY, stationPath)
        }.getOrThrow().use {
            it.readAtMost(MAX_LISTING_BYTES).toString(Charsets.UTF_8) // ~30 KB
        }

        val files = LISTING_ROW.findAll(listing).mapNotNull { row ->
            val time = row.groupValues[2]
            LISTING_TIMES.firstNotNullOfOrNull { format -> runCatching { LocalDateTime.parse(time, format) }.getOrNull() }
                ?.let { row.groupValues[1] to it }
        }.toList()
        val newest = files.maxOfOrNull { it.second } ?: throw AppException.Server()
        val since = newest.minusMinutes(period.inWholeMinutes + 1)
        val wanted = files.filter { it.second >= since }.sortedBy { it.second }.takeLast(MAX_RECENT_SCANS)

        val downloads = Semaphore(PARALLEL_DOWNLOADS)
        var firstFailure: Exception? = null
        val scans = coroutineScope {
            wanted.map { (file, _) ->
                async {
                    downloads.withPermit {
                        try {
                            downloadScan(station) { radarApi.fetchProduct(BASE_REFLECTIVITY, stationPath, file) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (firstFailure == null) firstFailure = e
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        // None downloaded: report why (e.g. the network dropped), not a server error
        val newestScan = scans.maxOfOrNull { it.productTimeMilli } ?: throw (firstFailure ?: AppException.Server())
        return scans
            .filter { it.productTimeMilli >= newestScan - period.inWholeMilliseconds }
            .distinctBy { it.productTimeMilli }
            .sortedBy { it.productTimeMilli }
    }

    private suspend fun downloadScan(station: String, request: suspend () -> Response<ResponseBody>): RadarScan {
        val body = safeApiCall { request() }.getOrThrow()

        val bytes = body.use { it.readAtMost(MAX_PRODUCT_BYTES) } // N0Q files are ~40 KB

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

        // Used in file paths: only a station id, e.g. "KEAX"
        val station = gridPoint.points.radarStation?.takeIf { it.matches(STATION_ID) }
            ?: throw AppException.NotFound()

        stations[key] = station
        return station
    }

    /** Reads the body, refusing one larger than [limit] bytes (far larger than expected) */
    private suspend fun ResponseBody.readAtMost(limit: Int): ByteArray = withContext(Dispatchers.IO) {
        val input = byteStream()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        if (out.size() > limit) throw AppException.Server()
        out.toByteArray()
    }

    companion object {
        private const val BASE_REFLECTIVITY = "DS.p94r0" // N0Q
        private const val LATEST_FILE = "sn.last"
        private const val MAX_PRODUCT_BYTES = 2 * 1024 * 1024
        private const val MAX_LISTING_BYTES = 256 * 1024
        private const val MAX_RECENT_SCANS = 40 // an hour even with SAILS sweeps every ~1.5 min
        private const val PARALLEL_DOWNLOADS = 4

        private val STATION_ID = Regex("[A-Z]{4}")

        // e.g. <a href="sn.0100">sn.0100</a></td><td align="right">03-Nov-2026 20:19  </td>, or
        // the same without the table, or with the ISO date Apache uses by default
        private val LISTING_ROW = Regex(
            """href="(sn\.\d{4})">[^<]*</a>(?:\s*</td>\s*<td[^>]*>)?\s*""" +
                    """(\d{2}-[A-Za-z]{3}-\d{4} \d{2}:\d{2}|\d{4}-\d{2}-\d{2} \d{2}:\d{2})"""
        )
        private val LISTING_TIMES = listOf(
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)
        )
    }
}
