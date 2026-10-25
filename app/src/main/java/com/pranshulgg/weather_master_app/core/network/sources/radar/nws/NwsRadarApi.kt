package com.pranshulgg.weather_master_app.core.network.sources.radar.nws

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

interface NwsRadarApi {

    // Latest NEXRAD Level 3 file for a radar station (e.g. product "DS.p94r0", station "keax")
    @GET("SL.us008001/DF.of/DC.radar/{product}/SI.{station}/sn.last")
    suspend fun fetchLatestProduct(
        @Path("product") product: String,
        @Path("station") station: String
    ): Response<ResponseBody>

    companion object {
        const val BASE_URL = "https://tgftp.nws.noaa.gov/"

        fun create(): NwsRadarApi {

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .build()
                .create(NwsRadarApi::class.java)
        }
    }
}
