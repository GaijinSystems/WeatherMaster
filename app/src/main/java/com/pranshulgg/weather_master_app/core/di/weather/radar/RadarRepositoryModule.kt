package com.pranshulgg.weather_master_app.core.di.weather.radar

import com.pranshulgg.weather_master_app.core.network.sources.radar.nws.NwsRadarRepository
import com.pranshulgg.weather_master_app.data.repository.radar.RadarRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RadarRepositoryModule {

    // Only NWS provides radar (Capability.RADAR)
    @Provides
    @Singleton
    fun provideRadarRepository(nws: NwsRadarRepository): RadarRepository = nws
}
