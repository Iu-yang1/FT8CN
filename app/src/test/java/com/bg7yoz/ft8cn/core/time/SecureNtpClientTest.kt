package com.bg7yoz.ft8cn.core.time

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs

class SecureNtpClientTest {
    @Test
    fun parsesValidatedFourTimestampResponse() {
        val server = LocalNtpServer(ResponseMode.VALID, serverOffsetMillis = 120.0)
        server.use {
            val result = SecureNtpClient(1_000, JvmTimeSource).query("127.0.0.1", server.port)

            assertTrue(abs(result.offsetMillis - 120.0) < 35.0)
            assertTrue(result.roundTripDelayMillis >= 0.0)
            assertTrue(result.sample.uncertaintyMillis >= 1.0)
            assertTrue(result.stratum == 2)
        }
    }

    @Test(expected = NtpProtocolException::class)
    fun rejectsOriginateTimestampMismatch() {
        LocalNtpServer(ResponseMode.BAD_ORIGINATE).use { server ->
            SecureNtpClient(1_000, JvmTimeSource).query("127.0.0.1", server.port)
        }
    }

    @Test(expected = NtpProtocolException::class)
    fun rejectsKissOfDeath() {
        LocalNtpServer(ResponseMode.KISS_OF_DEATH).use { server ->
            SecureNtpClient(1_000, JvmTimeSource).query("127.0.0.1", server.port)
        }
    }

    private enum class ResponseMode { VALID, BAD_ORIGINATE, KISS_OF_DEATH }

    private class LocalNtpServer(
        private val mode: ResponseMode,
        private val serverOffsetMillis: Double = 0.0,
    ) : AutoCloseable {
        private val socket = DatagramSocket(0)
        private val completed = CountDownLatch(1)
        private val error = AtomicReference<Throwable?>()
        val port: Int = socket.localPort

        init {
            thread(name = "local-ntp-test", isDaemon = true) {
                try {
                    val request = ByteArray(48)
                    val packet = DatagramPacket(request, request.size)
                    socket.receive(packet)
                    val response = ByteArray(48)
                    response[0] = 0x24
                    response[1] = if (mode == ResponseMode.KISS_OF_DEATH) 0 else 2
                    if (mode == ResponseMode.KISS_OF_DEATH) {
                        "RATE".toByteArray(Charsets.US_ASCII).copyInto(response, 12)
                    }
                    request.copyInto(response, 24, 40, 48)
                    if (mode == ResponseMode.BAD_ORIGINATE) response[24] = (response[24] + 1).toByte()
                    val now = System.currentTimeMillis() + serverOffsetMillis
                    writeTimestamp(response, 32, now)
                    writeTimestamp(response, 40, now + 1.0)
                    val reply = DatagramPacket(response, response.size, packet.address, packet.port)
                    socket.send(reply)
                } catch (failure: Throwable) {
                    if (!socket.isClosed) error.set(failure)
                } finally {
                    completed.countDown()
                }
            }
        }

        override fun close() {
            completed.await(2, TimeUnit.SECONDS)
            socket.close()
            error.get()?.let { throw AssertionError("local NTP server failed", it) }
        }

        private fun writeTimestamp(buffer: ByteArray, offset: Int, unixMillis: Double) {
            val ntp = unixMillis / 1_000.0 + 2_208_988_800L
            val seconds = ntp.toLong()
            val fraction = ((ntp - seconds) * 4_294_967_296.0).toLong()
            write32(buffer, offset, seconds)
            write32(buffer, offset + 4, fraction)
        }

        private fun write32(buffer: ByteArray, offset: Int, value: Long) {
            buffer[offset] = (value ushr 24).toByte()
            buffer[offset + 1] = (value ushr 16).toByte()
            buffer[offset + 2] = (value ushr 8).toByte()
            buffer[offset + 3] = value.toByte()
        }
    }

    private object JvmTimeSource : MonotonicTimeSource {
        override fun elapsedRealtimeNanos(): Long = System.nanoTime()

        override fun wallClockMillis(): Long = System.currentTimeMillis()
    }
}
