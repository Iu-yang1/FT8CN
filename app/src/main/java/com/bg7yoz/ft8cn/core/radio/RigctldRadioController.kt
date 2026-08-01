package com.bg7yoz.ft8cn.core.radio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.math.abs

data class RigctldProfile(
    val host: String,
    val port: Int = 4_532,
    val modelName: String = "Hamlib rigctld",
    val connectTimeoutMillis: Int = 3_000,
    val commandTimeoutMillis: Int = 2_000,
)

class RigctldProtocolException(message: String) : IOException(message)

/** Hamlib rigctld 的标准文本协议客户端，一次只允许一个未完成命令。 */
class RigctldClient(private val profile: RigctldProfile) : Closeable {
    private val lock = Any()
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    fun connect() = synchronized(lock) {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        require(profile.host.isNotBlank()) { "rigctld host must not be blank" }
        require(profile.port in 1..65_535) { "rigctld port is invalid" }
        val newSocket = Socket()
        try {
            newSocket.connect(InetSocketAddress(profile.host, profile.port), profile.connectTimeoutMillis)
            newSocket.soTimeout = profile.commandTimeoutMillis
            newSocket.tcpNoDelay = true
            socket = newSocket
            reader = BufferedReader(InputStreamReader(newSocket.getInputStream(), StandardCharsets.US_ASCII))
            writer = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.US_ASCII))
        } catch (failure: IOException) {
            newSocket.close()
            throw failure
        }
    }

    fun command(command: String) = synchronized(lock) {
        writeCommand(command)
        requireSuccess(command, readLine())
    }

    fun query(command: String, lineCount: Int): List<String> = synchronized(lock) {
        require(lineCount > 0)
        writeCommand(command)
        buildList(lineCount) {
            repeat(lineCount) {
                val line = readLine()
                if (line.startsWith(REPORT_PREFIX)) requireSuccess(command, line)
                add(line)
            }
        }
    }

    override fun close(): Unit = synchronized(lock) {
        try {
            socket?.close()
        } finally {
            socket = null
            reader = null
            writer = null
        }
    }

    private fun writeCommand(command: String) {
        if (command.indexOfAny(charArrayOf('\r', '\n')) >= 0) {
            throw IllegalArgumentException("rigctld command contains a line break")
        }
        val output = writer ?: throw IOException("rigctld is not connected")
        output.write(command)
        output.newLine()
        output.flush()
    }

    private fun readLine(): String = reader?.readLine()
        ?: throw IOException("rigctld disconnected while reading response")

    private fun requireSuccess(command: String, response: String) {
        if (!response.startsWith(REPORT_PREFIX)) {
            throw RigctldProtocolException("unexpected rigctld response for $command")
        }
        val code = response.removePrefix(REPORT_PREFIX).trim().toIntOrNull()
            ?: throw RigctldProtocolException("invalid rigctld status for $command")
        if (code != 0) throw RigctldProtocolException("rigctld $command failed with code $code")
    }

    private companion object {
        const val REPORT_PREFIX = "RPRT "
    }
}

/** Hamlib 网络后端。所有 socket/CAT 操作固定在 IO dispatcher 并由 Mutex 串行化。 */
class RigctldRadioController(
    private val profileProvider: suspend (Long) -> RigctldProfile,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RadioController {
    private val commandMutex = Mutex()
    private val mutableState = MutableStateFlow(RadioState(transport = RadioTransport.RIGCTLD))
    private var client: RigctldClient? = null
    override val state: StateFlow<RadioState> = mutableState.asStateFlow()

    override suspend fun discoverModels(): List<RadioModel> = listOf(
        RadioModel(NETRIGCTL_MODEL_ID, "Hamlib", "NET rigctl", "dummy/netrigctl"),
    )

    override suspend fun connect(profileId: Long): Result<Unit> {
        val profile = runCatching { profileProvider(profileId) }
            .getOrElse { return Result.failure(it) }
        return serializedResult {
            client?.close()
            val connectedClient = RigctldClient(profile)
            connectedClient.connect()
            client = connectedClient
            mutableState.value = mutableState.value.copy(
                connected = true,
                model = profile.modelName,
                capabilities = NETWORK_CAPABILITIES,
                lastError = null,
            )
            refreshStateLocked()
            Unit
        }
    }

    override suspend fun disconnect() {
        withContext(dispatcher) {
            commandMutex.withLock {
                try {
                    if (mutableState.value.transmitting) client?.command("T 0")
                } catch (_: IOException) {
                    // 断开时关闭 PTT 是 best-effort，内存状态仍必须立即回到安全值。
                } finally {
                    client?.close()
                    client = null
                    mutableState.value = RadioState(transport = RadioTransport.RIGCTLD)
                }
            }
        }
    }

    override suspend fun setFrequency(rxFrequencyHz: Long, txFrequencyHz: Long): Result<Unit> = serializedResult {
        require(rxFrequencyHz > 0 && txFrequencyHz > 0) { "frequency must be positive" }
        val transport = requireClient()
        val before = mutableState.value
        try {
            transport.command("F $rxFrequencyHz")
            if (txFrequencyHz != rxFrequencyHz) {
                transport.command("I $txFrequencyHz")
                transport.command("S 1 VFOB")
            } else if (before.splitEnabled) {
                transport.command("S 0 VFOA")
            }
            val readRx = transport.query("f", 1).single().trim().toLong()
            val readTx = if (txFrequencyHz != rxFrequencyHz) {
                transport.query("i", 1).single().trim().toLong()
            } else {
                readRx
            }
            if (abs(readRx - rxFrequencyHz) > READBACK_TOLERANCE_HZ ||
                abs(readTx - txFrequencyHz) > READBACK_TOLERANCE_HZ
            ) {
                throw RigctldProtocolException("frequency readback mismatch")
            }
            mutableState.value = before.copy(
                connected = true,
                rxFrequencyHz = readRx,
                txFrequencyHz = readTx,
                splitEnabled = readTx != readRx,
                lastError = null,
            )
        } catch (failure: Exception) {
            rollbackFrequency(transport, before)
            throw failure
        }
        Unit
    }

    override suspend fun setMode(mode: RadioMode, passbandHz: Int): Result<Unit> = serializedResult {
        require(passbandHz > 0) { "passband must be positive" }
        val transport = requireClient()
        val before = mutableState.value
        try {
            transport.command("M ${mode.hamlibName} $passbandHz")
            val readback = transport.query("m", 2)
            val actualMode = parseMode(readback[0])
            val actualPassband = readback[1].trim().toInt()
            if (actualMode != mode || actualPassband <= 0) {
                throw RigctldProtocolException("mode readback mismatch")
            }
            mutableState.value = before.copy(mode = actualMode, passbandHz = actualPassband, lastError = null)
        } catch (failure: Exception) {
            if (before.passbandHz > 0) {
                runCatching { transport.command("M ${before.mode.hamlibName} ${before.passbandHz}") }
            }
            throw failure
        }
        Unit
    }

    override suspend fun setPtt(enabled: Boolean): Result<Unit> = serializedResult {
        val transport = requireClient()
        transport.command("T ${if (enabled) 1 else 0}")
        val readback = transport.query("t", 1).single().trim() == "1"
        if (readback != enabled) throw RigctldProtocolException("PTT readback mismatch")
        mutableState.value = mutableState.value.copy(transmitting = enabled, lastError = null)
        Unit
    }

    override suspend fun setVfo(vfo: RadioVfo): Result<Unit> = serializedResult {
        require(vfo != RadioVfo.CURRENT) { "explicit VFO A/B is required" }
        val transport = requireClient()
        transport.command("V ${vfo.hamlibName}")
        val readback = parseVfo(transport.query("v", 1).single())
        if (readback != vfo) throw RigctldProtocolException("VFO readback mismatch")
        mutableState.value = mutableState.value.copy(activeVfo = readback, lastError = null)
        Unit
    }

    override suspend fun setSplit(enabled: Boolean, txVfo: RadioVfo): Result<Unit> = serializedResult {
        require(txVfo != RadioVfo.CURRENT) { "split TX VFO must be explicit" }
        val transport = requireClient()
        transport.command("S ${if (enabled) 1 else 0} ${txVfo.hamlibName}")
        val readback = transport.query("s", 2)
        val actualEnabled = readback[0].trim() == "1"
        if (actualEnabled != enabled) throw RigctldProtocolException("split readback mismatch")
        mutableState.value = mutableState.value.copy(splitEnabled = enabled, lastError = null)
        Unit
    }

    override suspend fun setPower(fraction: Float): Result<Unit> = serializedResult {
        require(fraction in 0f..1f) { "power must be in 0..1" }
        val transport = requireClient()
        transport.command("L RFPOWER $fraction")
        val readback = transport.query("l RFPOWER", 1).single().trim().toFloat()
        if (abs(readback - fraction) > POWER_READBACK_TOLERANCE) {
            throw RigctldProtocolException("power readback mismatch")
        }
        mutableState.value = mutableState.value.copy(powerFraction = readback, lastError = null)
        Unit
    }

    override suspend fun refreshState(): Result<RadioState> = serializedResult { refreshStateLocked() }

    override suspend fun emergencyStop(): Result<Unit> = serializedResult {
        requireClient().command("T 0")
        mutableState.value = mutableState.value.copy(transmitting = false, lastError = null)
        Unit
    }

    private fun refreshStateLocked(): RadioState {
        val transport = requireClient()
        val rxFrequency = transport.query("f", 1).single().trim().toLong()
        val modeReply = transport.query("m", 2)
        val splitReply = runCatching { transport.query("s", 2) }.getOrNull()
        val split = splitReply?.firstOrNull()?.trim() == "1"
        val txFrequency = if (split) {
            runCatching { transport.query("i", 1).single().trim().toLong() }.getOrDefault(rxFrequency)
        } else {
            rxFrequency
        }
        val ptt = runCatching { transport.query("t", 1).single().trim() == "1" }.getOrDefault(false)
        val power = runCatching { transport.query("l RFPOWER", 1).single().trim().toFloat() }.getOrNull()
        val strength = runCatching { transport.query("l STRENGTH", 1).single().trim().toFloat() }.getOrNull()
        val refreshed = mutableState.value.copy(
            connected = true,
            rxFrequencyHz = rxFrequency,
            txFrequencyHz = txFrequency,
            mode = parseMode(modeReply[0]),
            passbandHz = modeReply[1].trim().toInt(),
            activeVfo = splitReply?.getOrNull(1)?.let(::parseVfo) ?: mutableState.value.activeVfo,
            splitEnabled = split,
            transmitting = ptt,
            powerFraction = power,
            strengthDbm = strength,
            lastReadbackMonotonicMillis = System.nanoTime() / 1_000_000L,
            lastError = null,
        )
        mutableState.value = refreshed
        return refreshed
    }

    private fun rollbackFrequency(transport: RigctldClient, before: RadioState) {
        runCatching {
            if (before.rxFrequencyHz > 0) transport.command("F ${before.rxFrequencyHz}")
            if (before.splitEnabled && before.txFrequencyHz > 0) {
                transport.command("I ${before.txFrequencyHz}")
                transport.command("S 1 VFOB")
            } else {
                transport.command("S 0 VFOA")
            }
        }
        mutableState.value = before
    }

    private suspend fun <T> serializedResult(block: () -> T): Result<T> = withContext(dispatcher) {
        commandMutex.withLock {
            runCatching(block).onFailure { failure ->
                mutableState.value = mutableState.value.copy(
                    lastError = failure.message ?: failure.javaClass.simpleName,
                )
            }
        }
    }

    private fun requireClient(): RigctldClient = client
        ?: throw IOException("rigctld is not connected")

    private fun parseMode(value: String): RadioMode = when (value.trim().uppercase()) {
        "USB" -> RadioMode.USB
        "PKTUSB", "DATA", "DATAUSB" -> RadioMode.DATA_USB
        "FM", "WFM", "NFM" -> RadioMode.FM
        "CW", "CWR" -> RadioMode.CW
        else -> throw RigctldProtocolException("unsupported radio mode")
    }

    private fun parseVfo(value: String): RadioVfo = when (value.trim().uppercase()) {
        "VFOA", "A", "MAIN" -> RadioVfo.A
        "VFOB", "B", "SUB" -> RadioVfo.B
        "VFO", "CURR", "CURRENT" -> RadioVfo.CURRENT
        else -> throw RigctldProtocolException("unsupported VFO")
    }

    private companion object {
        const val NETRIGCTL_MODEL_ID = 2L
        const val READBACK_TOLERANCE_HZ = 2L
        const val POWER_READBACK_TOLERANCE = 0.02f
        val NETWORK_CAPABILITIES = RadioCapabilities(
            canGetFrequency = true,
            canSetFrequency = true,
            canGetMode = true,
            canSetMode = true,
            canSetVfo = true,
            canSplit = true,
            canPtt = true,
            canSetPower = true,
            canReadStrength = true,
            supportedVfos = RadioVfo.values().toSet(),
        )
    }
}
