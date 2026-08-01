package com.bg7yoz.ft8cn.core.radio

import com.bg7yoz.ft8cn.connector.ConnectMode
import com.bg7yoz.ft8cn.rigs.BaseRig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 将现有 USB、蓝牙和网络 CAT 设备接入统一接口。旧协议不支持可靠 split 时明确失败。 */
class LegacyRigRadioController(
    private val rigProvider: () -> BaseRig?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RadioController {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RadioState(transport = RadioTransport.LEGACY_USB))
    override val state: StateFlow<RadioState> = mutableState.asStateFlow()

    override suspend fun connect(profileId: Long): Result<Unit> = serialized {
        val rig = requireRig()
        if (!rig.isConnected) throw IllegalStateException("现有电台 transport 尚未连接")
        val transport = when (rig.controlMode) {
            ConnectMode.BLUE_TOOTH -> RadioTransport.LEGACY_BLUETOOTH
            ConnectMode.NETWORK -> RadioTransport.LEGACY_NETWORK
            else -> RadioTransport.LEGACY_USB
        }
        mutableState.value = mutableState.value.copy(
            connected = true,
            model = runCatching { rig.name }.getOrDefault(rig.javaClass.simpleName),
            transport = transport,
            capabilities = LEGACY_CAPABILITIES,
            rxFrequencyHz = rig.freq,
            txFrequencyHz = rig.freq,
            transmitting = rig.isPttOn,
            lastError = null,
        )
        Unit
    }

    override suspend fun disconnect() {
        withContext(dispatcher) {
            mutex.withLock {
                rigProvider()?.let { rig ->
                    runCatching { rig.setPTT(false) }
                    runCatching { rig.onDisconnecting() }
                }
                mutableState.value = RadioState(transport = mutableState.value.transport)
            }
        }
    }

    override suspend fun setFrequency(rxFrequencyHz: Long, txFrequencyHz: Long): Result<Unit> = serialized {
        require(rxFrequencyHz > 0 && txFrequencyHz > 0) { "frequency must be positive" }
        if (rxFrequencyHz != txFrequencyHz) {
            throw UnsupportedOperationException("现有 CAT 适配器未提供可验证的 split VFO")
        }
        val rig = requireConnectedRig()
        val before = rig.freq
        try {
            rig.setFreq(rxFrequencyHz)
            rig.setFreqToRig()
            if (rig.freq != rxFrequencyHz) throw IllegalStateException("电台频率缓存读回不一致")
            mutableState.value = mutableState.value.copy(
                rxFrequencyHz = rxFrequencyHz,
                txFrequencyHz = txFrequencyHz,
                splitEnabled = false,
                lastError = null,
            )
        } catch (failure: Exception) {
            if (before > 0) runCatching { rig.setFreq(before) }
            throw failure
        }
        Unit
    }

    override suspend fun setMode(mode: RadioMode, passbandHz: Int): Result<Unit> = serialized {
        require(passbandHz > 0) { "passband must be positive" }
        if (mode != RadioMode.USB && mode != RadioMode.DATA_USB) {
            throw UnsupportedOperationException("现有 CAT 适配器只提供 USB 数据模式")
        }
        requireConnectedRig().setUsbModeToRig()
        mutableState.value = mutableState.value.copy(mode = mode, passbandHz = passbandHz, lastError = null)
        Unit
    }

    override suspend fun setPtt(enabled: Boolean): Result<Unit> = serialized {
        val rig = requireConnectedRig()
        rig.setPTT(enabled)
        if (rig.isPttOn != enabled) throw IllegalStateException("PTT 状态缓存读回不一致")
        mutableState.value = mutableState.value.copy(transmitting = enabled, lastError = null)
        Unit
    }

    override suspend fun refreshState(): Result<RadioState> = serialized {
        val rig = requireConnectedRig()
        rig.readFreqFromRig()
        val current = mutableState.value.copy(
            rxFrequencyHz = rig.freq,
            txFrequencyHz = rig.freq,
            transmitting = rig.isPttOn,
            lastReadbackMonotonicMillis = System.nanoTime() / 1_000_000L,
            lastError = null,
        )
        mutableState.value = current
        current
    }

    override suspend fun emergencyStop(): Result<Unit> = serialized {
        requireRig().setPTT(false)
        mutableState.value = mutableState.value.copy(transmitting = false, lastError = null)
        Unit
    }

    private suspend fun <T> serialized(block: () -> T): Result<T> = withContext(dispatcher) {
        mutex.withLock {
            runCatching(block).onFailure { failure ->
                mutableState.value = mutableState.value.copy(lastError = failure.message)
            }
        }
    }

    private fun requireRig(): BaseRig = rigProvider()
        ?: throw IllegalStateException("没有已选择的电台")

    private fun requireConnectedRig(): BaseRig = requireRig().also {
        if (!it.isConnected) throw IllegalStateException("电台已断开")
    }

    private companion object {
        val LEGACY_CAPABILITIES = RadioCapabilities(
            canGetFrequency = true,
            canSetFrequency = true,
            canSetMode = true,
            canPtt = true,
        )
    }
}
