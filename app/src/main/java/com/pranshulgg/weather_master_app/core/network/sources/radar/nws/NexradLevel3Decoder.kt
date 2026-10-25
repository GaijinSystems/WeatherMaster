package com.pranshulgg.weather_master_app.core.network.sources.radar.nws

import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes NEXRAD Level 3 8-bit digital radial products (N0Q base reflectivity)
 *
 * Adapted from wX by Joshua Tee (https://gitlab.com/joshua.tee/wx, GPL-3.0):
 * NexradLevel3.decodeAndPlot, UtilityIO.uncompress and NexradDecodeEightBit.forCanvas
 */
object NexradLevel3Decoder {

    private const val PRODUCT_BASE_REFLECTIVITY = 94
    private const val PACKET_DIGITAL_RADIAL = 0x10
    private const val SECONDS_PER_DAY = 86_400L

    private val binSizeKm = mapOf(PRODUCT_BASE_REFLECTIVITY to 1.0)

    fun decode(data: ByteArray, station: String): RadarScan {
        val header = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        // The WMO text header is followed by the message header; the product description block
        // starts after the first -1 halfword divider
        do {
            val halfword = header.short.toInt()
        } while (halfword != -1)
        val descriptionStart = header.position()

        val latitude = header.int / 1000.0
        val longitude = header.int / 1000.0
        header.short // radar height
        val productCode = header.short.toInt() and 0xFFFF
        header.short // operational mode
        header.short // volume coverage pattern
        header.short // sequence number
        header.short // volume scan number
        val scanDate = header.short.toInt() and 0xFFFF // days since 1970-01-01, starting at 1
        val scanTimeSeconds = header.int // seconds since midnight UTC

        val binSize = binSizeKm[productCode]
            ?: throw IOException("Unsupported radar product $productCode")

        // The symbology block is bzip2 compressed and starts 100 bytes after the divider
        val compressedStart = descriptionStart + 100
        val symbology = DataInputStream(
            BZip2CompressorInputStream(
                ByteArrayInputStream(data, compressedStart, data.size - compressedStart)
            ).buffered()
        )

        return symbology.use { input ->
            input.skipFully(16) // block divider, block id, length, layer count, layer divider, layer length
            val packetCode = input.readUnsignedShort()
            if (packetCode != PACKET_DIGITAL_RADIAL) {
                throw IOException("Unexpected radar packet $packetCode")
            }
            input.skipFully(2) // index of first range bin
            val binCount = input.readUnsignedShort()
            input.skipFully(6) // i/j center of sweep, range scale factor
            val radialCount = input.readUnsignedShort()

            val startAngles = FloatArray(radialCount)
            val angleWidths = FloatArray(radialCount)
            val levels = ByteArray(radialCount * binCount)

            repeat(radialCount) { radial ->
                val byteCount = input.readUnsignedShort()
                startAngles[radial] = input.readUnsignedShort() / 10f
                angleWidths[radial] = input.readUnsignedShort() / 10f
                val values = ByteArray(byteCount)
                input.readFully(values)
                values.copyInto(levels, radial * binCount, 0, minOf(byteCount, binCount))
            }

            RadarScan(
                station = station,
                latitude = latitude,
                longitude = longitude,
                scanTimeMilli = ((scanDate - 1) * SECONDS_PER_DAY + scanTimeSeconds) * 1000,
                binCount = binCount,
                binSizeKm = binSize,
                startAngles = startAngles,
                angleWidths = angleWidths,
                levels = levels
            )
        }
    }

    private fun DataInputStream.skipFully(count: Int) {
        readFully(ByteArray(count))
    }
}
