package com.pranshulgg.weather_master_app.core.network.sources.radar.nws

import com.pranshulgg.weather_master_app.core.model.domain.AppException
import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes NEXRAD Level 3 8-bit digital radial products (N0Q base reflectivity)
 *
 * Adapted from wX by Joshua Tee (https://gitlab.com/joshua.tee/wx, GPL-3.0):
 * NexradLevel3.decodeAndPlot, UtilityIO.uncompress and NexradDecodeEightBit.forCanvas
 *
 * Throws [AppException.Server] for data that isn't a valid product
 */
object NexradLevel3Decoder {

    private const val PRODUCT_BASE_REFLECTIVITY = 94
    private const val PACKET_DIGITAL_RADIAL = 0x10
    private const val COMPRESSION_BZIP2 = 1
    private const val MESSAGE_HEADER_BYTES = 18
    private const val SECONDS_PER_DAY = 86_400L
    private const val BIN_SIZE_KM = 1.0 // N0Q: 0.54 nmi
    private const val MAX_BINS = 1840
    private const val MAX_RADIALS = 720

    fun decode(data: ByteArray, station: String): RadarScan = try {
        decodeProduct(data, station)
    } catch (e: AppException) {
        throw e
    } catch (e: Exception) {
        // Truncated or corrupt data (buffer underflow, bzip2 errors, EOF)
        throw AppException.Server()
    }

    private fun decodeProduct(data: ByteArray, station: String): RadarScan {
        val header = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        // Two WMO header lines ending in CR CR LF, then the 18-byte message header, then the
        // product description block, which starts with a -1 divider
        header.position(wmoHeaderLength(data) + MESSAGE_HEADER_BYTES)
        ensureValid(header.short.toInt() == -1)
        val descriptionStart = header.position()

        val latitude = header.int / 1000.0
        val longitude = header.int / 1000.0
        ensureValid(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        header.short // radar height
        val productCode = header.short.toInt() and 0xFFFF
        header.short // operational mode
        header.short // volume coverage pattern
        header.short // sequence number
        header.short // volume scan number
        val scanDate = header.short.toInt() and 0xFFFF // days since 1970-01-01, starting at 1
        val scanTimeSeconds = header.int // seconds since midnight UTC
        val productDate = header.short.toInt() and 0xFFFF // when this file was generated
        val productTimeSeconds = header.int

        val compression = header.getShort(descriptionStart + 80).toInt() // halfword 51
        ensureValid(productCode == PRODUCT_BASE_REFLECTIVITY && compression == COMPRESSION_BZIP2)

        // The symbology block is bzip2 compressed and starts 100 bytes after the divider
        val compressedStart = descriptionStart + 100
        val symbology = DataInputStream(
            BZip2CompressorInputStream(
                ByteArrayInputStream(data, compressedStart, data.size - compressedStart)
            ).buffered()
        )

        return symbology.use { input ->
            input.skipFully(16) // block divider, block id, length, layer count, layer divider, layer length
            ensureValid(input.readUnsignedShort() == PACKET_DIGITAL_RADIAL)
            ensureValid(input.readUnsignedShort() == 0) // index of first range bin
            val binCount = input.readUnsignedShort()
            input.skipFully(6) // i/j center of sweep, range scale factor (display scale, not km)
            val radialCount = input.readUnsignedShort()
            ensureValid(binCount in 1..MAX_BINS && radialCount in 1..MAX_RADIALS)

            val startAngles = FloatArray(radialCount)
            val angleWidths = FloatArray(radialCount)
            val levels = ByteArray(radialCount * binCount)
            val values = ByteArray(0xFFFF)

            repeat(radialCount) { radial ->
                val byteCount = input.readUnsignedShort()
                startAngles[radial] = input.readUnsignedShort() / 10f
                angleWidths[radial] = input.readUnsignedShort() / 10f
                ensureValid(startAngles[radial] < 360f && angleWidths[radial] > 0f && angleWidths[radial] <= 2f)
                input.readFully(values, 0, byteCount)
                values.copyInto(levels, radial * binCount, 0, minOf(byteCount, binCount))
            }

            RadarScan(
                station = station,
                latitude = latitude,
                longitude = longitude,
                scanTimeMilli = ((scanDate - 1) * SECONDS_PER_DAY + scanTimeSeconds) * 1000,
                productTimeMilli = ((productDate - 1) * SECONDS_PER_DAY + productTimeSeconds) * 1000,
                binCount = binCount,
                binSizeKm = BIN_SIZE_KM,
                startAngles = startAngles,
                angleWidths = angleWidths,
                levels = levels
            )
        }
    }

    private fun wmoHeaderLength(data: ByteArray): Int {
        var lines = 0
        for (i in 2 until minOf(data.size, 256)) {
            if (data[i - 2] == '\r'.code.toByte() && data[i - 1] == '\r'.code.toByte() && data[i] == '\n'.code.toByte()) {
                if (++lines == 2) return i + 1
            }
        }
        throw AppException.Server()
    }

    private fun ensureValid(valid: Boolean) {
        if (!valid) throw AppException.Server()
    }

    private fun DataInputStream.skipFully(count: Int) {
        readFully(ByteArray(count))
    }
}
