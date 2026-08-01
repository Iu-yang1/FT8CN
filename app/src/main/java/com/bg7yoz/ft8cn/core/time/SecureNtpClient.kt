package com.bg7yoz.ft8cn.core.time

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

data class NtpMeasurement(
    val server: String,
    val offsetMillis: Double,
    val roundTripDelayMillis: Double,
    val rootDispersionMillis: Double,
    val stratum: Int,
    val sample: ClockSample,
)

class NtpProtocolException(message: String) : IOException(message)

/**
 * 最小化的 SNTPv4 客户端。它校验响应身份和服务器状态，并使用四时间戳公式计算偏差。
 * 该实现没有 NTS 认证，因此不使用“安全 NTP”名称。
 */
class SntpClient(
    private val timeoutMillis: Int = 3_000,
    private val timeSource: MonotonicTimeSource = AndroidMonotonicTimeSource,
) {
    @Throws(IOException::class)
    fun query(host: String, port: Int = NTP_PORT): NtpMeasurement {
        require(host.isNotBlank()) { "NTP server must not be blank" }
        val address = InetAddress.getByName(host)
        val request = ByteArray(PACKET_SIZE)
        request[0] = ((NTP_VERSION shl 3) or MODE_CLIENT).toByte()

        val t1Wall = timeSource.wallClockMillis().toDouble()
        val t1Mono = timeSource.elapsedRealtimeNanos()
        writeNtpTimestamp(request, TRANSMIT_OFFSET, t1Wall)
        val requestTransmit = request.copyOfRange(TRANSMIT_OFFSET, TRANSMIT_OFFSET + 8)

        val response = ByteArray(PACKET_SIZE)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis
            socket.connect(address, port)
            socket.send(DatagramPacket(request, request.size, address, port))
            val packet = DatagramPacket(response, response.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                throw IOException("NTP $host timeout after ${timeoutMillis}ms", e)
            }
            if (packet.length < PACKET_SIZE) {
                throw NtpProtocolException("NTP $host returned ${packet.length} bytes")
            }
        }

        val t4Mono = timeSource.elapsedRealtimeNanos()
        val t4Wall = timeSource.wallClockMillis().toDouble()
        val monotonicElapsedMs = (t4Mono - t1Mono) / 1_000_000.0
        val wallElapsedMs = t4Wall - t1Wall
        if (abs(wallElapsedMs - monotonicElapsedMs) > MAX_WALL_JUMP_DURING_QUERY_MS) {
            throw NtpProtocolException("local wall clock changed during NTP query")
        }

        validateResponse(host, response, requestTransmit)
        val stratum = response[1].toInt() and 0xff
        val t2 = readNtpTimestamp(response, RECEIVE_OFFSET, t4Wall)
        val t3 = readNtpTimestamp(response, TRANSMIT_OFFSET, t4Wall)
        if (t2 == 0.0 || t3 == 0.0) {
            throw NtpProtocolException("NTP $host returned an empty timestamp")
        }

        val offset = ((t2 - t1Wall) + (t3 - t4Wall)) / 2.0
        val delay = (t4Wall - t1Wall) - (t3 - t2)
        if (delay < -MAX_NEGATIVE_DELAY_MS || delay > MAX_ACCEPTED_DELAY_MS) {
            throw NtpProtocolException("NTP $host invalid round-trip delay ${delay.toInt()}ms")
        }
        val rootDispersion = readUnsignedFixed16_16(response, ROOT_DISPERSION_OFFSET) * 1_000.0
        val uncertainty = max(MIN_UNCERTAINTY_MS, max(0.0, delay) / 2.0 + rootDispersion)
        return NtpMeasurement(
            server = host,
            offsetMillis = offset,
            roundTripDelayMillis = max(0.0, delay),
            rootDispersionMillis = rootDispersion,
            stratum = stratum,
            sample = ClockSample(
                utcMillis = t4Wall + offset,
                monotonicNanos = t4Mono,
                uncertaintyMillis = uncertainty,
                source = ClockSource.NTP,
                detail = "NTP $host stratum=$stratum rtt=${max(0.0, delay).toInt()}ms",
                roundTripDelayMillis = max(0.0, delay),
                consensusMembers = 1,
            ),
        )
    }

    private fun validateResponse(host: String, response: ByteArray, requestTransmit: ByteArray) {
        val leap = (response[0].toInt() ushr 6) and 0x3
        val version = (response[0].toInt() ushr 3) and 0x7
        val mode = response[0].toInt() and 0x7
        val stratum = response[1].toInt() and 0xff
        if (leap == LEAP_UNSYNCHRONIZED) {
            throw NtpProtocolException("NTP $host is unsynchronized")
        }
        if (version !in 3..4 || mode !in setOf(MODE_SERVER, MODE_BROADCAST)) {
            throw NtpProtocolException("NTP $host invalid version/mode $version/$mode")
        }
        if (stratum == 0) {
            val code = String(response, REFERENCE_ID_OFFSET, 4, StandardCharsets.US_ASCII)
            throw NtpProtocolException("NTP $host kiss-of-death $code")
        }
        if (stratum > MAX_STRATUM) {
            throw NtpProtocolException("NTP $host invalid stratum $stratum")
        }
        val originate = response.copyOfRange(ORIGINATE_OFFSET, ORIGINATE_OFFSET + 8)
        if (!originate.contentEquals(requestTransmit)) {
            throw NtpProtocolException("NTP $host originate timestamp mismatch")
        }
    }

    private companion object {
        const val NTP_PORT = 123
        const val PACKET_SIZE = 48
        const val NTP_VERSION = 4
        const val MODE_CLIENT = 3
        const val MODE_SERVER = 4
        const val MODE_BROADCAST = 5
        const val LEAP_UNSYNCHRONIZED = 3
        const val MAX_STRATUM = 15
        const val ROOT_DISPERSION_OFFSET = 8
        const val REFERENCE_ID_OFFSET = 12
        const val ORIGINATE_OFFSET = 24
        const val RECEIVE_OFFSET = 32
        const val TRANSMIT_OFFSET = 40
        const val UNIX_TO_NTP_SECONDS = 2_208_988_800L
        const val NTP_ERA_SECONDS = 4_294_967_296L
        const val MAX_WALL_JUMP_DURING_QUERY_MS = 100.0
        const val MAX_NEGATIVE_DELAY_MS = 5.0
        const val MAX_ACCEPTED_DELAY_MS = 5_000.0
        const val MIN_UNCERTAINTY_MS = 1.0

        fun writeNtpTimestamp(buffer: ByteArray, offset: Int, unixMillis: Double) {
            val ntpSeconds = unixMillis / 1_000.0 + UNIX_TO_NTP_SECONDS
            val seconds = ntpSeconds.toLong()
            val fraction = ((ntpSeconds - seconds) * 4_294_967_296.0).toLong()
            writeUnsigned32(buffer, offset, seconds)
            writeUnsigned32(buffer, offset + 4, fraction)
        }

        fun readNtpTimestamp(buffer: ByteArray, offset: Int, referenceUnixMillis: Double): Double {
            val seconds32 = readUnsigned32(buffer, offset)
            val fraction = readUnsigned32(buffer, offset + 4) / 4_294_967_296.0
            if (seconds32 == 0L && fraction == 0.0) return 0.0
            var unixSeconds = seconds32 - UNIX_TO_NTP_SECONDS
            val referenceSeconds = (referenceUnixMillis / 1_000.0).toLong()
            while (unixSeconds - referenceSeconds > NTP_ERA_SECONDS / 2) unixSeconds -= NTP_ERA_SECONDS
            while (referenceSeconds - unixSeconds > NTP_ERA_SECONDS / 2) unixSeconds += NTP_ERA_SECONDS
            return (unixSeconds + fraction) * 1_000.0
        }

        fun readUnsignedFixed16_16(buffer: ByteArray, offset: Int): Double {
            val raw = readUnsigned32(buffer, offset)
            return raw / 65_536.0
        }

        fun readUnsigned32(buffer: ByteArray, offset: Int): Long =
            ((buffer[offset].toLong() and 0xffL) shl 24) or
                ((buffer[offset + 1].toLong() and 0xffL) shl 16) or
                ((buffer[offset + 2].toLong() and 0xffL) shl 8) or
                (buffer[offset + 3].toLong() and 0xffL)

        fun writeUnsigned32(buffer: ByteArray, offset: Int, value: Long) {
            buffer[offset] = (value ushr 24).toByte()
            buffer[offset + 1] = (value ushr 16).toByte()
            buffer[offset + 2] = (value ushr 8).toByte()
            buffer[offset + 3] = value.toByte()
        }
    }
}

/** 多源采样后按 offset 中位数剔除离群值，保留最低不确定度样本的时刻精度。 */
class MultiSourceNtpDiscipline(
    private val client: SntpClient = SntpClient(),
) {
    @Throws(IOException::class)
    fun synchronize(preferredServer: String? = null): NtpMeasurement {
        val servers = buildList {
            preferredServer?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            addAll(DEFAULT_SERVERS)
        }.distinct().take(MAX_SERVERS)
        val executor = Executors.newFixedThreadPool(servers.size) { runnable ->
            Thread(runnable, "ft8cn-ntp-query").apply { isDaemon = true }
        }
        val failures = mutableListOf<String>()
        val measurements = try {
            val futures = executor.invokeAll(
                servers.map { server -> Callable { client.query(server) } },
                QUERY_BATCH_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
            futures.mapIndexedNotNull { index, future ->
                if (future.isCancelled) {
                    failures += "${servers[index]} timeout"
                    null
                } else {
                    try {
                        future.get()
                    } catch (e: Exception) {
                        failures += "${servers[index]} ${e.cause?.message ?: e.message}"
                        null
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
        if (measurements.isEmpty()) {
            throw IOException("all NTP sources failed: ${failures.joinToString("; ")}")
        }

        val medianOffset = measurements.map { it.offsetMillis }.sorted().let { values ->
            val middle = values.size / 2
            if (values.size % 2 == 0) (values[middle - 1] + values[middle]) / 2.0 else values[middle]
        }
        val inliers = measurements.filter {
            abs(it.offsetMillis - medianOffset) <= max(MIN_OUTLIER_WINDOW_MS, it.sample.uncertaintyMillis * 4.0)
        }
        if (inliers.isEmpty()) {
            throw IOException("NTP sources do not agree")
        }
        val best = inliers.minByOrNull { it.sample.uncertaintyMillis }!!
        val fusedOffset = inliers.map { it.offsetMillis }.sorted().let { values -> values[values.size / 2] }
        val correction = fusedOffset - best.offsetMillis
        return best.copy(
            server = best.server,
            offsetMillis = fusedOffset,
            sample = best.sample.copy(
                utcMillis = best.sample.utcMillis + correction,
                uncertaintyMillis = max(
                    best.sample.uncertaintyMillis,
                    inliers.maxOf { abs(it.offsetMillis - fusedOffset) },
                ),
                detail = "NTP ${inliers.size}/${servers.size} sources rtt=${best.roundTripDelayMillis.toInt()}ms",
                roundTripDelayMillis = best.roundTripDelayMillis,
                consensusMembers = inliers.size,
            ),
        )
    }

    companion object {
        val DEFAULT_SERVERS = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")
        private const val MAX_SERVERS = 4
        private const val QUERY_BATCH_TIMEOUT_MS = 4_500L
        private const val MIN_OUTLIER_WINDOW_MS = 250.0
    }
}
