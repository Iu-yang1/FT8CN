package com.bg7yoz.ft8cn.core.radio

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class RigctldRadioControllerTest {
    private var server: FakeRigctldServer? = null

    @After
    fun tearDown() {
        server?.close()
    }

    @Test
    fun loopbackSupportsReadbackSplitPttAndPower() = runBlocking {
        val fakeServer = FakeRigctldServer().also {
            server = it
            it.start()
        }
        val controller = RigctldRadioController(
            profileProvider = {
                RigctldProfile("127.0.0.1", fakeServer.port, commandTimeoutMillis = 1_000)
            },
        )

        assertTrue(controller.connect(1).isSuccess)
        assertTrue(controller.setMode(RadioMode.DATA_USB, 3_000).isSuccess)
        assertTrue(controller.setFrequency(14_074_000, 14_076_000).isSuccess)
        assertTrue(controller.setVfo(RadioVfo.A).isSuccess)
        assertTrue(controller.setPower(0.35f).isSuccess)
        assertTrue(controller.setPtt(true).isSuccess)

        val state = controller.refreshState().getOrThrow()
        assertEquals(14_074_000, state.rxFrequencyHz)
        assertEquals(14_076_000, state.txFrequencyHz)
        assertEquals(RadioMode.DATA_USB, state.mode)
        assertTrue(state.splitEnabled)
        assertTrue(state.transmitting)
        assertEquals(0.35f, state.powerFraction ?: 0f, 0.001f)

        assertTrue(controller.emergencyStop().isSuccess)
        assertFalse(controller.state.value.transmitting)
        controller.disconnect()
        assertFalse(controller.state.value.connected)
    }

    @Test
    fun frequencyReadbackMismatchFailsAndRestoresPreviousState() = runBlocking {
        val fakeServer = FakeRigctldServer().also {
            server = it
            it.start()
        }
        val controller = RigctldRadioController(
            profileProvider = { RigctldProfile("127.0.0.1", fakeServer.port) },
        )
        assertTrue(controller.connect(1).isSuccess)
        val before = controller.state.value

        fakeServer.forceFrequencyReadbackOffsetHz = 25
        assertTrue(controller.setFrequency(14_075_000).isFailure)
        assertEquals(before.rxFrequencyHz, controller.state.value.rxFrequencyHz)
        assertEquals(before.txFrequencyHz, controller.state.value.txFrequencyHz)
    }

    @Test
    fun clientRejectsLineBreakInjectionBeforeSending() {
        val fakeServer = FakeRigctldServer().also {
            server = it
            it.start()
        }
        val client = RigctldClient(RigctldProfile("127.0.0.1", fakeServer.port))
        client.connect()
        val failure = runCatching { client.command("F 14074000\nT 1") }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertFalse(fakeServer.ptt)
        client.close()
    }

    private class FakeRigctldServer : Closeable {
        private val socket = ServerSocket(0, 1)
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile private var running = true
        @Volatile var frequencyHz = 14_074_000L
        @Volatile var txFrequencyHz = 14_074_000L
        @Volatile var mode = "USB"
        @Volatile var passbandHz = 3_000
        @Volatile var split = false
        @Volatile var vfo = "VFOA"
        @Volatile var ptt = false
        @Volatile var power = 0.5f
        @Volatile var forceFrequencyReadbackOffsetHz = 0L

        val port: Int get() = socket.localPort

        fun start() {
            executor.execute {
                while (running) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    serve(client)
                }
            }
        }

        private fun serve(client: Socket) = client.use { connected ->
            val reader = BufferedReader(
                InputStreamReader(connected.getInputStream(), StandardCharsets.US_ASCII),
            )
            val writer = BufferedWriter(
                OutputStreamWriter(connected.getOutputStream(), StandardCharsets.US_ASCII),
            )
            while (running) {
                val command = reader.readLine() ?: break
                val response = handle(command)
                response.forEach {
                    writer.write(it)
                    writer.newLine()
                }
                writer.flush()
            }
        }

        private fun handle(command: String): List<String> {
            val parts = command.split(' ')
            return when (parts[0]) {
                "f" -> listOf((frequencyHz + forceFrequencyReadbackOffsetHz).toString())
                "F" -> commandResult { frequencyHz = parts[1].toLong() }
                "i" -> listOf(txFrequencyHz.toString())
                "I" -> commandResult { txFrequencyHz = parts[1].toLong() }
                "m" -> listOf(mode, passbandHz.toString())
                "M" -> commandResult {
                    mode = parts[1]
                    passbandHz = parts[2].toInt()
                }
                "s" -> listOf(if (split) "1" else "0", vfo)
                "S" -> commandResult {
                    split = parts[1] == "1"
                    vfo = parts[2]
                }
                "v" -> listOf(vfo)
                "V" -> commandResult { vfo = parts[1] }
                "t" -> listOf(if (ptt) "1" else "0")
                "T" -> commandResult { ptt = parts[1] == "1" }
                "l" -> when (parts.getOrNull(1)) {
                    "RFPOWER" -> listOf(power.toString())
                    "STRENGTH" -> listOf("-91.0")
                    else -> listOf("RPRT -1")
                }
                "L" -> commandResult { power = parts[2].toFloat() }
                else -> listOf("RPRT -1")
            }
        }

        private inline fun commandResult(update: () -> Unit): List<String> {
            update()
            return listOf("RPRT 0")
        }

        override fun close() {
            running = false
            socket.close()
            executor.shutdownNow()
        }
    }
}
