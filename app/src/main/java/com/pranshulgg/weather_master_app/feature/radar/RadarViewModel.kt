package com.pranshulgg.weather_master_app.feature.radar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.pranshulgg.weather_master_app.core.model.domain.location.Location
import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import com.pranshulgg.weather_master_app.core.network.sources.radar.nws.NwsRadarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class RadarUiState(
    val locationId: String? = null,
    val scan: RadarScan? = null,
    val isLoading: Boolean = false,
    val isUnavailable: Boolean = false
)

@HiltViewModel
class RadarViewModel @Inject constructor(
    private val repository: NwsRadarRepository
) : ViewModel() {

    var uiState by mutableStateOf(RadarUiState())
        private set

    suspend fun refresh(location: Location) {
        if (uiState.locationId != location.id) {
            uiState = RadarUiState(locationId = location.id)
        }
        uiState = uiState.copy(isLoading = true)

        uiState = try {
            val station = repository.getRadarStation(location)
            uiState.copy(scan = repository.getLatestScan(station), isLoading = false, isUnavailable = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep showing the last scan if a refresh fails
            uiState.copy(isLoading = false, isUnavailable = uiState.scan == null)
        }
    }
}
