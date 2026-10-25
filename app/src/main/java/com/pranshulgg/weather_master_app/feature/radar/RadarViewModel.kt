package com.pranshulgg.weather_master_app.feature.radar

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pranshulgg.weather_master_app.core.model.domain.AppException
import com.pranshulgg.weather_master_app.core.model.domain.location.Location
import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import com.pranshulgg.weather_master_app.core.model.domain.toAppException
import com.pranshulgg.weather_master_app.data.repository.radar.RadarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

data class RadarUiState(
    val locationKey: String? = null,
    val scan: RadarScan? = null,
    val error: AppException? = null
)

/** Identifies a location and its position: the device location keeps its id when it moves */
val Location.radarKey: String get() = "$id@$latitude,$longitude"

@HiltViewModel
class RadarViewModel @Inject constructor(
    private val repository: RadarRepository
) : ViewModel() {

    var uiState by mutableStateOf(RadarUiState())
        private set

    private var lastFetchMilli = 0L // SystemClock.elapsedRealtime()
    private var retryJob: Job? = null

    /**
     * Keeps the scan for [location] up to date while called (the radar page runs it while visible
     * and the app is started). Reopening the page within the refresh interval doesn't fetch again
     */
    suspend fun keepUpdated(location: Location) {
        if (uiState.locationKey != location.radarKey) {
            retryJob?.cancel()
            uiState = RadarUiState(locationKey = location.radarKey)
            lastFetchMilli = 0L
        }
        while (true) {
            val sinceLast = SystemClock.elapsedRealtime() - lastFetchMilli
            val wait = REFRESH_INTERVAL.inWholeMilliseconds - sinceLast
            if (lastFetchMilli != 0L && wait > 0) delay(wait)
            fetch(location)
        }
    }

    fun retry(location: Location) {
        retryJob?.cancel()
        retryJob = viewModelScope.launch { fetch(location) }
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
                // Keep the same instance when the scan hasn't changed, so it isn't drawn again
                val unchanged = current != null && current.station == scan.station &&
                        current.scanTimeMilli == scan.scanTimeMilli
                uiState.copy(scan = if (unchanged) current else scan, error = null)
            },
            // Keep showing the last scan if a refresh fails
            onFailure = { uiState.copy(error = it as AppException) }
        )
        lastFetchMilli = SystemClock.elapsedRealtime()
    }

    private companion object {
        val REFRESH_INTERVAL = 3.minutes
    }
}
