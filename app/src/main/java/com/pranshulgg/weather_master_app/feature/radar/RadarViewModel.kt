package com.pranshulgg.weather_master_app.feature.radar

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pranshulgg.weather_master_app.core.managers.WeatherManager
import com.pranshulgg.weather_master_app.core.model.domain.AppException
import com.pranshulgg.weather_master_app.core.model.domain.location.Location
import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import com.pranshulgg.weather_master_app.core.model.domain.toAppException
import com.pranshulgg.weather_master_app.core.model.domain.toMessageRes
import com.pranshulgg.weather_master_app.core.model.sources.Capability
import com.pranshulgg.weather_master_app.core.ui.snackbar.SnackbarManager
import com.pranshulgg.weather_master_app.data.repository.radar.RadarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class RadarUiState(
    val locationKey: String? = null,
    val scan: RadarScan? = null,
    val error: AppException? = null,
    /**
     * The last hour of scans for the time lapse, oldest first. Plays as it is until stopped; newer
     * scans are added when it is played again
     */
    val loop: List<RadarScan>? = null,
    val isLoopOn: Boolean = false
)

/** Identifies a location and its position: the device location keeps its id when it moves */
val Location.radarKey: String get() = "$id@$latitude,$longitude"

@HiltViewModel
class RadarViewModel @Inject constructor(
    private val repository: RadarRepository,
    weatherManager: WeatherManager
) : ViewModel() {

    var uiState by mutableStateOf(RadarUiState())
        private set

    private var lastFetchMilli = 0L // SystemClock.elapsedRealtime()
    private var fetchJob: Job? = null
    private var loopJob: Job? = null

    init {
        // Fetch the radar together with the weather, whether or not the radar page is open: when a
        // location is set and whenever its weather is refreshed (manually, automatically, new source)
        weatherManager.requests
            .onEach { location -> if (Capability.RADAR in location.source.capabilities) refreshNow(location) }
            .launchIn(viewModelScope)
    }

    /**
     * Keeps the scan for [location] up to date while called (the radar page runs it while visible
     * and the app is started). Doesn't fetch again within the refresh interval of the last fetch,
     * including one made by [refreshNow]
     */
    suspend fun keepUpdated(location: Location) {
        selectLocation(location)
        while (true) {
            // Another location was selected (e.g. by a weather request) before the page caught up:
            // its fetch result would be dropped, so don't fetch again and again
            if (uiState.locationKey != location.radarKey) {
                delay(REFRESH_INTERVAL)
                continue
            }
            val wait = REFRESH_INTERVAL.inWholeMilliseconds - millisSince(lastFetchMilli)
            if (lastFetchMilli != 0L && wait > 0) {
                delay(wait)
                continue // re-check: refreshNow may have fetched meanwhile
            }
            // Wait for a fetch already running rather than start a second one
            (fetchJob?.takeIf { it.isActive } ?: startFetch(location)).join()
        }
    }

    /**
     * Fetches the latest scan now, with the weather and for Retry. Skipped if the same location
     * was fetched in the last [MIN_REFETCH_GAP] (repeated pull to refresh, the replayed request
     * when this view model is created)
     */
    fun refreshNow(location: Location) {
        selectLocation(location)
        val recent = lastFetchMilli != 0L && millisSince(lastFetchMilli) < MIN_REFETCH_GAP.inWholeMilliseconds
        if (recent && uiState.error == null) return
        if (fetchJob?.isActive == true && uiState.error == null) return // one is already on its way
        startFetch(location)
    }

    /** Starts or stops the time lapse, loading the last hour of scans if the kept loop won't do */
    fun toggleLoop(location: Location) {
        selectLocation(location)
        if (uiState.isLoopOn) {
            stopLoop()
            return
        }
        val loop = reusableLoop()
        uiState = uiState.copy(isLoopOn = true, loop = loop)
        if (loop == null) loadLoop(location)
    }

    /** Stops the time lapse; [keepScans] false also frees the loaded scans (the page closed) */
    fun stopLoop(keepScans: Boolean = true) {
        loopJob?.cancel()
        uiState = uiState.copy(isLoopOn = false, loop = uiState.loop.takeIf { keepScans })
    }

    /** The kept loop brought up to date with the latest scan, or null to load it again */
    private fun reusableLoop(): List<RadarScan>? {
        val kept = uiState.loop ?: return null
        val latest = uiState.scan ?: return kept
        return extendLoop(kept, latest)
    }

    private fun loadLoop(location: Location) {
        loopJob = viewModelScope.launch {
            val scans = try {
                repository.getRecentScans(location, LOOP_PERIOD).takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                val error = e.toAppException()
                if (uiState.locationKey == location.radarKey) SnackbarManager.show(error.toMessageRes())
                null
            }
            if (uiState.locationKey != location.radarKey) return@launch

            // Include a scan fetched while the loop was loading
            val loop = scans?.let { loaded -> uiState.scan?.let { extendLoop(loaded, it) } ?: loaded }
            uiState = uiState.copy(loop = loop, isLoopOn = loop != null)
        }
    }

    private fun selectLocation(location: Location) {
        if (uiState.locationKey == location.radarKey) return
        fetchJob?.cancel()
        loopJob?.cancel()
        uiState = RadarUiState(locationKey = location.radarKey)
        lastFetchMilli = 0L
    }

    private fun startFetch(location: Location): Job {
        fetchJob?.cancel()
        return viewModelScope.launch { fetch(location) }.also { fetchJob = it }
    }

    private suspend fun fetch(location: Location) {
        val result = try {
            Result.success(repository.getLatestScan(location))
        } catch (e: Exception) {
            Result.failure(e.toAppException())
        }
        // Drop results for a location that is no longer shown
        if (uiState.locationKey != location.radarKey) return

        uiState = result.fold(
            onSuccess = { scan ->
                val current = uiState.scan
                // Keep the same instance when unchanged (not drawn again); never step back to an
                // older scan (a lagging server)
                val keep = current != null && current.station == scan.station &&
                        scan.productTimeMilli <= current.productTimeMilli
                uiState.copy(scan = if (keep) current else scan, error = null)
            },
            // Keep showing the last scan if a refresh fails
            onFailure = { uiState.copy(error = it as AppException) }
        )
        lastFetchMilli = SystemClock.elapsedRealtime()
    }

    /**
     * Adds a newer [scan] to the [loop] and drops scans older than the loop period. Null if the
     * scan doesn't follow on directly (another station, or more than [MAX_EXTEND_GAP] later, so
     * scans in between would be missing): the loop is loaded again
     */
    private fun extendLoop(loop: List<RadarScan>, scan: RadarScan): List<RadarScan>? {
        val last = loop.last()
        if (scan.station != last.station) return null
        val gap = scan.productTimeMilli - last.productTimeMilli
        if (gap <= 0) return loop
        if (gap > MAX_EXTEND_GAP.inWholeMilliseconds) return null
        val cutoff = scan.productTimeMilli - LOOP_PERIOD.inWholeMilliseconds
        return (loop + scan).filter { it.productTimeMilli >= cutoff }
    }

    private fun millisSince(elapsedRealtimeMilli: Long) = SystemClock.elapsedRealtime() - elapsedRealtimeMilli

    private companion object {
        val REFRESH_INTERVAL = 3.minutes
        val MIN_REFETCH_GAP = 30.seconds
        val LOOP_PERIOD = 1.hours
        val MAX_EXTEND_GAP = 6.minutes // about one volume scan
    }
}
