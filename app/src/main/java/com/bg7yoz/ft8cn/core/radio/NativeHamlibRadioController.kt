package com.bg7yoz.ft8cn.core.radio

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class HamlibPttMethod(val nativeValue: String) {
    VOX("None"),
    CAT("RIG"),
    DTR("DTR"),
    RTS("RTS"),
}

enum class HamlibHandshake(val nativeValue: String) {
    DEFAULT(""),
    NONE("None"),
    XON_XOFF("XONXOFF"),
    HARDWARE("Hardware"),
}

enum class HamlibControlLine(val nativeValue: String) {
    DEFAULT(""),
    HIGH("ON"),
    LOW("OFF"),
}

enum class HamlibAudioSource {
    FRONT,
    REAR_DATA,
}

data class NativeHamlibProfile(
    val modelId: Int,
    val modelName: String,
    val endpoint: String,
    val baud: Int = 4_800,
    val dataBits: Int = 0,
    val stopBits: Int = 0,
    val handshake: HamlibHandshake = HamlibHandshake.DEFAULT,
    val forceDtr: HamlibControlLine = HamlibControlLine.DEFAULT,
    val forceRts: HamlibControlLine = HamlibControlLine.DEFAULT,
    val pttMethod: HamlibPttMethod = HamlibPttMethod.VOX,
    val pttEndpoint: String = "",
    val pollIntervalMs: Int = 1_000,
    val txDelayMs: Int = 100,
    val audioSource: HamlibAudioSource = HamlibAudioSource.FRONT,
    val autoPowerOn: Boolean = false,
    val autoPowerOff: Boolean = false,
    val querySMeter: Boolean = false,
)

/** 直接链接 LGPL Hamlib 的控制器；同一进程只允许一个活动 rig handle。 */
class NativeHamlibRadioController(
    context: Context,
    private val profileProvider: suspend (Long) -> NativeHamlibProfile,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RadioController {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RadioState(transport = RadioTransport.NATIVE_HAMLIB))
    private var handle = 0L
    private var profile: NativeHamlibProfile? = null
    private val usbCatBridge = UsbCatTransportBridge(context)

    override val state: StateFlow<RadioState> = mutableState.asStateFlow()

    override suspend fun discoverModels(): List<RadioModel> = withContext(dispatcher) {
        if (!isAvailable()) return@withContext emptyList()
        NativeHamlibBridge.nativeListModels().mapNotNull { row ->
            val fields = row.split('\t', limit = 4)
            val id = fields.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            RadioModel(
                id = id,
                manufacturer = fields.getOrElse(1) { "" },
                model = fields.getOrElse(2) { "" },
                backend = fields.getOrElse(3) { "" },
            )
        }.sortedWith(compareBy(RadioModel::manufacturer, RadioModel::model, RadioModel::id))
    }

    override suspend fun connect(profileId: Long): Result<Unit> = serialized {
        check(isAvailable()) { "当前 ABI 未包含 native Hamlib" }
        val selected = profileProvider(profileId)
        require(selected.modelId > 0) { "请选择 Hamlib 电台型号" }
        require(selected.endpoint.isNotBlank()) { "CAT 端点不能为空" }
        closeLocked()
        val usesUsbBridge = UsbCatEndpointScanner.isUsbToken(selected.endpoint)
        val effectiveEndpoint = if (usesUsbBridge) {
            usbCatBridge.open(selected.endpoint, selected.baud, selected.dataBits, selected.stopBits)
        } else {
            selected.endpoint
        }
        val effectivePttEndpoint = if (selected.pttEndpoint.isBlank() || selected.pttEndpoint == selected.endpoint) {
            effectiveEndpoint
        } else {
            selected.pttEndpoint
        }
        handle = try {
            NativeHamlibBridge.nativeOpen(
                selected.modelId,
                effectiveEndpoint,
                selected.baud,
                selected.dataBits,
                selected.stopBits,
                selected.handshake.nativeValue,
                selected.forceDtr.nativeValue,
                selected.forceRts.nativeValue,
                if (usesUsbBridge && selected.pttMethod in USB_CONTROL_LINE_METHODS) {
                    HamlibPttMethod.VOX.nativeValue
                } else {
                    selected.pttMethod.nativeValue
                },
                effectivePttEndpoint,
                selected.pollIntervalMs,
                selected.txDelayMs,
                selected.autoPowerOn,
                selected.autoPowerOff,
            )
        } catch (error: Throwable) {
            usbCatBridge.close()
            throw error
        }
        check(handle != 0L) { "Hamlib 未返回有效连接" }
        profile = selected
        mutableState.value = RadioState(
            connected = true,
            model = selected.modelName,
            transport = RadioTransport.NATIVE_HAMLIB,
            capabilities = NATIVE_CAPABILITIES,
        )
        refreshLocked()
        Unit
    }

    override suspend fun disconnect() {
        withContext(dispatcher) { mutex.withLock { closeLocked() } }
    }

    override suspend fun setFrequency(rxFrequencyHz: Long, txFrequencyHz: Long): Result<Unit> = serialized {
        require(rxFrequencyHz > 0 && txFrequencyHz > 0) { "频率必须大于 0" }
        NativeHamlibBridge.nativeSetFrequency(requireHandle(), rxFrequencyHz, txFrequencyHz)
        val readback = NativeHamlibBridge.nativeGetFrequency(requireHandle())
        check(readback.size >= 2) { "Hamlib 频率读回无效" }
        mutableState.value = mutableState.value.copy(
            rxFrequencyHz = readback[0],
            txFrequencyHz = readback[1],
            splitEnabled = readback[0] != readback[1],
            lastError = null,
        )
        Unit
    }

    override suspend fun setMode(mode: RadioMode, passbandHz: Int): Result<Unit> = serialized {
        require(passbandHz > 0) { "通带必须大于 0" }
        NativeHamlibBridge.nativeSetMode(requireHandle(), mode.ordinal, passbandHz)
        val readback = NativeHamlibBridge.nativeGetMode(requireHandle())
        check(readback.size >= 2 && readback[0] in RadioMode.values().indices.map(Int::toLong)) {
            "Hamlib 模式读回无效"
        }
        mutableState.value = mutableState.value.copy(
            mode = RadioMode.values()[readback[0].toInt()],
            passbandHz = readback[1].toInt(),
            lastError = null,
        )
        Unit
    }

    override suspend fun setPtt(enabled: Boolean): Result<Unit> = serialized {
        val selected = profile ?: error("Hamlib 未连接")
        when (selected.pttMethod) {
            HamlibPttMethod.VOX -> {
                mutableState.value = mutableState.value.copy(transmitting = enabled, lastError = null)
                return@serialized Unit
            }
            HamlibPttMethod.DTR -> if (UsbCatEndpointScanner.isUsbToken(selected.endpoint)) {
                usbCatBridge.setDtr(enabled)
                mutableState.value = mutableState.value.copy(transmitting = enabled, lastError = null)
                return@serialized Unit
            }
            HamlibPttMethod.RTS -> if (UsbCatEndpointScanner.isUsbToken(selected.endpoint)) {
                usbCatBridge.setRts(enabled)
                mutableState.value = mutableState.value.copy(transmitting = enabled, lastError = null)
                return@serialized Unit
            }
            HamlibPttMethod.CAT -> Unit
        }
        NativeHamlibBridge.nativeSetPtt(
            requireHandle(),
            enabled,
            selected.audioSource == HamlibAudioSource.REAR_DATA,
        )
        val readback = NativeHamlibBridge.nativeGetPtt(requireHandle())
        check(readback == enabled) { "PTT 读回不一致" }
        mutableState.value = mutableState.value.copy(transmitting = readback, lastError = null)
        Unit
    }

    override suspend fun setVfo(vfo: RadioVfo): Result<Unit> = serialized {
        NativeHamlibBridge.nativeSetVfo(requireHandle(), vfo.ordinal)
        mutableState.value = mutableState.value.copy(activeVfo = vfo, lastError = null)
        Unit
    }

    override suspend fun setSplit(enabled: Boolean, txVfo: RadioVfo): Result<Unit> = serialized {
        require(txVfo != RadioVfo.CURRENT) { "split TX VFO 必须是 A 或 B" }
        NativeHamlibBridge.nativeSetSplit(requireHandle(), enabled, txVfo.ordinal)
        mutableState.value = mutableState.value.copy(splitEnabled = enabled, lastError = null)
        Unit
    }

    override suspend fun refreshState(): Result<RadioState> = serialized { refreshLocked() }

    override suspend fun emergencyStop(): Result<Unit> = serialized {
        val selected = profile
        if (handle != 0L && selected?.pttMethod != HamlibPttMethod.VOX) {
            when {
                selected?.pttMethod == HamlibPttMethod.DTR &&
                    UsbCatEndpointScanner.isUsbToken(selected.endpoint) -> usbCatBridge.setDtr(false)
                selected?.pttMethod == HamlibPttMethod.RTS &&
                    UsbCatEndpointScanner.isUsbToken(selected.endpoint) -> usbCatBridge.setRts(false)
                else -> NativeHamlibBridge.nativeSetPtt(handle, false, false)
            }
        }
        mutableState.value = mutableState.value.copy(transmitting = false)
        Unit
    }

    private fun refreshLocked(): RadioState {
        val frequency = NativeHamlibBridge.nativeGetFrequency(requireHandle())
        val mode = NativeHamlibBridge.nativeGetMode(requireHandle())
        val transmitting = if (profile?.pttMethod == HamlibPttMethod.VOX ||
            (profile?.endpoint?.let(UsbCatEndpointScanner::isUsbToken) == true && profile?.pttMethod in USB_CONTROL_LINE_METHODS)
        ) {
            mutableState.value.transmitting
        } else {
            NativeHamlibBridge.nativeGetPtt(requireHandle())
        }
        val strength = if (profile?.querySMeter == true) {
            NativeHamlibBridge.nativeGetStrength(requireHandle()).takeIf(Float::isFinite)
        } else {
            null
        }
        val refreshed = mutableState.value.copy(
            connected = true,
            rxFrequencyHz = frequency.getOrElse(0) { 0L },
            txFrequencyHz = frequency.getOrElse(1) { frequency.getOrElse(0) { 0L } },
            mode = mode.getOrNull(0)?.toInt()?.takeIf { it in RadioMode.values().indices }
                ?.let { RadioMode.values()[it] } ?: mutableState.value.mode,
            passbandHz = mode.getOrElse(1) { 0L }.toInt(),
            transmitting = transmitting,
            strengthDbm = strength,
            lastReadbackMonotonicMillis = System.nanoTime() / 1_000_000L,
            lastError = null,
        )
        mutableState.value = refreshed
        return refreshed
    }

    private fun requireHandle(): Long = handle.takeIf { it != 0L } ?: error("Hamlib 未连接")

    private fun closeLocked() {
        if (handle != 0L) runCatching { NativeHamlibBridge.nativeClose(handle) }
        handle = 0L
        profile = null
        usbCatBridge.close()
        mutableState.value = RadioState(transport = RadioTransport.NATIVE_HAMLIB)
    }

    private suspend fun <T> serialized(block: suspend () -> T): Result<T> = withContext(dispatcher) {
        mutex.withLock {
            runCatching { block() }.onFailure {
                mutableState.value = mutableState.value.copy(lastError = it.message)
            }
        }
    }

    companion object {
        fun isAvailable(): Boolean = runCatching { NativeHamlibBridge.nativeAvailable() }.getOrDefault(false)
        fun version(): String = runCatching { NativeHamlibBridge.nativeVersion() }.getOrDefault("unavailable")

        private val NATIVE_CAPABILITIES = RadioCapabilities(
            canGetFrequency = true,
            canSetFrequency = true,
            canGetMode = true,
            canSetMode = true,
            canSetVfo = true,
            canSplit = true,
            canPtt = true,
            canSetPower = false,
            canReadStrength = true,
            supportedVfos = setOf(RadioVfo.CURRENT, RadioVfo.A, RadioVfo.B),
        )
        private val USB_CONTROL_LINE_METHODS = setOf(HamlibPttMethod.DTR, HamlibPttMethod.RTS)
    }
}
