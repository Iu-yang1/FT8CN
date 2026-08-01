package com.bg7yoz.ft8cn.core.radio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 优先使用 Hamlib；仅在 Hamlib 未连接时使用已连接的旧 BaseRig 适配器。
 * 外层互斥保证 backend 切换、轮询和命令不会交错，旧协议也不能绕过统一事务。
 */
class HamlibFirstRadioController(
    private val hamlib: RadioController,
    private val legacy: RadioController,
) : RadioController {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RadioState(transport = RadioTransport.NATIVE_HAMLIB))

    override val state: StateFlow<RadioState> = mutableState.asStateFlow()

    override suspend fun discoverModels(): List<RadioModel> = mutex.withLock {
        hamlib.discoverModels()
    }

    /** Radio 页面显式连接始终连接 Hamlib，不会静默改用旧协议。 */
    override suspend fun connect(profileId: Long): Result<Unit> = mutex.withLock {
        legacy.emergencyStop()
        hamlib.connect(profileId).also { syncState() }
    }

    /** 将已由旧连接页面建立的 BaseRig 纳入统一 radio transaction。 */
    suspend fun attachLegacy(): Result<Unit> = mutex.withLock {
        legacy.connect(LEGACY_PROFILE_ID).also { syncState() }
    }

    suspend fun detachLegacy() = mutex.withLock {
        legacy.emergencyStop()
        legacy.disconnect()
        syncState()
    }

    override suspend fun disconnect() = mutex.withLock {
        stopAndDisconnect(hamlib)
        stopAndDisconnect(legacy)
        syncState()
    }

    override suspend fun setFrequency(rxFrequencyHz: Long, txFrequencyHz: Long): Result<Unit> =
        withActive { it.setFrequency(rxFrequencyHz, txFrequencyHz) }

    override suspend fun setMode(mode: RadioMode, passbandHz: Int): Result<Unit> =
        withActive { it.setMode(mode, passbandHz) }

    override suspend fun setPtt(enabled: Boolean): Result<Unit> = withActive { it.setPtt(enabled) }

    override suspend fun setVfo(vfo: RadioVfo): Result<Unit> = withActive { it.setVfo(vfo) }

    override suspend fun setSplit(enabled: Boolean, txVfo: RadioVfo): Result<Unit> =
        withActive { it.setSplit(enabled, txVfo) }

    override suspend fun setPower(fraction: Float): Result<Unit> = withActive { it.setPower(fraction) }

    override suspend fun refreshState(): Result<RadioState> = mutex.withLock {
        val controller = activeController()
            ?: return@withLock Result.failure(IllegalStateException("电台未连接"))
        controller.refreshState().also { syncState() }
    }

    override suspend fun emergencyStop(): Result<Unit> = mutex.withLock {
        val failures = buildList {
            if (hamlib.state.value.connected) hamlib.emergencyStop().exceptionOrNull()?.let(::add)
            if (legacy.state.value.connected) legacy.emergencyStop().exceptionOrNull()?.let(::add)
        }
        syncState()
        if (failures.isEmpty()) {
            Result.success(Unit)
        } else {
            val failure = IllegalStateException("无法确认所有电台后端的 PTT 已关闭")
            failures.forEach(failure::addSuppressed)
            Result.failure(failure)
        }
    }

    private suspend fun <T> withActive(block: suspend (RadioController) -> Result<T>): Result<T> = mutex.withLock {
        val controller = activeController()
            ?: return@withLock Result.failure(IllegalStateException("电台未连接"))
        block(controller).also { syncState() }
    }

    private fun activeController(): RadioController? = when {
        hamlib.state.value.connected -> hamlib
        legacy.state.value.connected -> legacy
        else -> null
    }

    private suspend fun stopAndDisconnect(controller: RadioController) {
        if (!controller.state.value.connected) return
        controller.emergencyStop()
        controller.disconnect()
    }

    private fun syncState() {
        mutableState.value = activeController()?.state?.value
            ?: RadioState(transport = RadioTransport.NATIVE_HAMLIB)
    }

    private companion object {
        const val LEGACY_PROFILE_ID = 0L
    }
}
