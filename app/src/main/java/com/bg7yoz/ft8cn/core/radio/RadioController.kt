package com.bg7yoz.ft8cn.core.radio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

enum class RadioMode(val hamlibName: String) {
    USB("USB"),
    DATA_USB("PKTUSB"),
    FM("FM"),
    CW("CW"),
}

enum class RadioVfo(val hamlibName: String) {
    CURRENT("VFO"),
    A("VFOA"),
    B("VFOB"),
}

enum class RadioTransport {
    LEGACY_USB,
    LEGACY_BLUETOOTH,
    LEGACY_NETWORK,
    RIGCTLD,
    NATIVE_HAMLIB,
    FAKE,
}

enum class SplitStrategy {
    NONE,
    RIG_SPLIT,
    FAKE_IT,
}

data class RadioCapabilities(
    val canGetFrequency: Boolean = false,
    val canSetFrequency: Boolean = false,
    val canGetMode: Boolean = false,
    val canSetMode: Boolean = false,
    val canSetVfo: Boolean = false,
    val canSplit: Boolean = false,
    val canPtt: Boolean = false,
    val canSetPower: Boolean = false,
    val canReadStrength: Boolean = false,
    val supportedVfos: Set<RadioVfo> = setOf(RadioVfo.CURRENT),
)

data class RadioModel(
    val id: Long,
    val manufacturer: String,
    val model: String,
    val backend: String,
)

data class RadioState(
    val connected: Boolean = false,
    val model: String = "",
    val transport: RadioTransport = RadioTransport.FAKE,
    val capabilities: RadioCapabilities = RadioCapabilities(),
    val rxFrequencyHz: Long = 0,
    val txFrequencyHz: Long = 0,
    val mode: RadioMode = RadioMode.DATA_USB,
    val passbandHz: Int = 0,
    val activeVfo: RadioVfo = RadioVfo.CURRENT,
    val splitEnabled: Boolean = false,
    val transmitting: Boolean = false,
    val powerFraction: Float? = null,
    val strengthDbm: Float? = null,
    val lastReadbackMonotonicMillis: Long = 0,
    val lastError: String? = null,
)

/** 统一电台控制契约，所有实现都必须将失败作为 Result 返回。 */
interface RadioController {
    val state: StateFlow<RadioState>

    suspend fun discoverModels(): List<RadioModel> = emptyList()
    suspend fun connect(profileId: Long): Result<Unit>
    suspend fun disconnect()
    suspend fun setFrequency(rxFrequencyHz: Long, txFrequencyHz: Long = rxFrequencyHz): Result<Unit>
    suspend fun setMode(mode: RadioMode, passbandHz: Int): Result<Unit>
    suspend fun setPtt(enabled: Boolean): Result<Unit>

    suspend fun setVfo(vfo: RadioVfo): Result<Unit> =
        Result.failure(UnsupportedOperationException("电台不支持 VFO 切换"))

    suspend fun setSplit(enabled: Boolean, txVfo: RadioVfo = RadioVfo.B): Result<Unit> =
        Result.failure(UnsupportedOperationException("电台不支持 split"))

    suspend fun setPower(fraction: Float): Result<Unit> =
        Result.failure(UnsupportedOperationException("电台不支持功率控制"))

    suspend fun refreshState(): Result<RadioState> = Result.success(state.value)

    suspend fun emergencyStop(): Result<Unit> = setPtt(false)
}

/** 测试替身保留完整命令轨迹，并可在指定操作注入一次失败。 */
class FakeRadioController(initial: RadioState = RadioState()) : RadioController {
    private val mutableState = MutableStateFlow(initial)
    private val failures = ArrayDeque<String>()
    val commandLog = mutableListOf<String>()
    private var failNextPttReadback = false

    override val state: StateFlow<RadioState> = mutableState.asStateFlow()

    fun failNext(operation: String) {
        failures.addLast(operation)
    }

    fun failNextPttReadback() {
        failNextPttReadback = true
    }

    override suspend fun discoverModels(): List<RadioModel> =
        listOf(RadioModel(1, "FT8CN", "Fake Rig", "fake"))

    override suspend fun connect(profileId: Long): Result<Unit> {
        commandLog += "connect:$profileId"
        failIfRequested("connect")?.let { return it }
        mutableState.value = mutableState.value.copy(
            connected = true,
            model = "FAKE-$profileId",
            transport = RadioTransport.FAKE,
            capabilities = FULL_FAKE_CAPABILITIES,
            lastError = null,
        )
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        commandLog += "disconnect"
        mutableState.value = RadioState()
    }

    override suspend fun setFrequency(rxFrequencyHz: Long, txFrequencyHz: Long): Result<Unit> {
        commandLog += "frequency:$rxFrequencyHz:$txFrequencyHz"
        failIfRequested("frequency")?.let { return it }
        if (!mutableState.value.connected || rxFrequencyHz <= 0 || txFrequencyHz <= 0) {
            return Result.failure(IllegalStateException("电台未连接或频率无效"))
        }
        mutableState.value = mutableState.value.copy(
            rxFrequencyHz = rxFrequencyHz,
            txFrequencyHz = txFrequencyHz,
            splitEnabled = rxFrequencyHz != txFrequencyHz,
            lastError = null,
        )
        return Result.success(Unit)
    }

    override suspend fun setMode(mode: RadioMode, passbandHz: Int): Result<Unit> {
        commandLog += "mode:${mode.name}:$passbandHz"
        failIfRequested("mode")?.let { return it }
        if (!mutableState.value.connected || passbandHz <= 0) {
            return Result.failure(IllegalStateException("电台未连接或通带无效"))
        }
        mutableState.value = mutableState.value.copy(mode = mode, passbandHz = passbandHz, lastError = null)
        return Result.success(Unit)
    }

    override suspend fun setPtt(enabled: Boolean): Result<Unit> {
        commandLog += "ptt:$enabled"
        failIfRequested("ptt")?.let { return it }
        if (!mutableState.value.connected) {
            return Result.failure(IllegalStateException("电台未连接"))
        }
        mutableState.value = mutableState.value.copy(transmitting = enabled, lastError = null)
        return Result.success(Unit)
    }

    override suspend fun setVfo(vfo: RadioVfo): Result<Unit> {
        commandLog += "vfo:${vfo.name}"
        failIfRequested("vfo")?.let { return it }
        mutableState.value = mutableState.value.copy(activeVfo = vfo)
        return Result.success(Unit)
    }

    override suspend fun setSplit(enabled: Boolean, txVfo: RadioVfo): Result<Unit> {
        commandLog += "split:$enabled:${txVfo.name}"
        failIfRequested("split")?.let { return it }
        mutableState.value = mutableState.value.copy(splitEnabled = enabled)
        return Result.success(Unit)
    }

    override suspend fun setPower(fraction: Float): Result<Unit> {
        commandLog += "power:$fraction"
        failIfRequested("power")?.let { return it }
        if (fraction !in 0f..1f) return Result.failure(IllegalArgumentException("功率必须在 0..1"))
        mutableState.value = mutableState.value.copy(powerFraction = fraction)
        return Result.success(Unit)
    }

    override suspend fun refreshState(): Result<RadioState> {
        commandLog += "refresh"
        if (failNextPttReadback && mutableState.value.transmitting) {
            failNextPttReadback = false
            return Result.failure(IllegalStateException("injected PTT readback failure"))
        }
        return Result.success(mutableState.value)
    }

    private fun failIfRequested(operation: String): Result<Unit>? {
        if (failures.peekFirst() != operation) return null
        failures.removeFirst()
        val error = IllegalStateException("injected $operation failure")
        mutableState.value = mutableState.value.copy(lastError = error.message)
        return Result.failure(error)
    }

    private companion object {
        val FULL_FAKE_CAPABILITIES = RadioCapabilities(
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
