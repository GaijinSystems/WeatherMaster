package com.pranshulgg.weather_master_app.data.repository.radar

import com.pranshulgg.weather_master_app.core.model.domain.location.Location
import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import kotlin.time.Duration

interface RadarRepository {
    suspend fun getLatestScan(location: Location): RadarScan

    /** Scans from the last [period], oldest first */
    suspend fun getRecentScans(location: Location, period: Duration): List<RadarScan>
}
