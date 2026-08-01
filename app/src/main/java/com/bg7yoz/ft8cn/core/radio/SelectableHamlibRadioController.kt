package com.bg7yoz.ft8cn.core.radio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class HamlibBackend {
    NATIVE,
    RIGCTLD,
}

/** 在连接时选择进程内 Hamlib 或 rigctld，业务层始终只看到一个 RadioController。 */
class SelectableHamlibRadioController(
    private val backendProvider: suspend () -> HamlibBackend,
    private val nativeController: RadioController,
    private val rigctldController: RadioController,
) : RadioController {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RadioState(transport = RadioTransport.NATIVE_HAMLIB))
    private var active: RadioController? = null

    override val state: StateFlow<RadioState> = mutableState.asStateFlow()

    override suspend fun discoverModels(): List<RadioModel> = mutex.withLock {
        nativeController.discoverModels() + rigctldController.discoverModels()
    }

    override suspend fun connect(profileId: Long): Result<Unit> = mutex.withLock {
        active?.let { previous ->
            previous.emergencyStop()
            previous.disconnect()
        }
        active = null
        val selected = when (backendProvider()) {
            HamlibBackend.NATIVE -> nativeController
            HamlibBackend.RIGCTLD -> rigctldController
        }
        selected.connect(profileId).also { result ->
            if (result.isSuccess) {
                active = selected
                syncState(selected)
            } else {
                mutableState.value = RadioState(
                    transport = selected.state.value.transport,
                    lastError = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    override suspend fun disconnect() = mutex.withLock {
        active?.let { controller ->
            controller.emergencyStop()
            controller.disconnect()
        }
        active = null
        mutableState.value = RadioState(transport = RadioTransport.NATIVE_HAMLIB)
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

    override suspend fun refreshState(): Result<RadioState> = withActive { it.refreshState() }

    override suspend fun emergencyStop(): Result<Unit> = withActive { it.emergencyStop() }

    private suspend fun <T> withActive(block: suspend (RadioController) -> Result<T>): Result<T> = mutex.withLock {
        val controller = active ?: return@withLock Result.failure(IllegalStateException("Hamlib 未连接"))
        block(controller).also { syncState(controller) }
    }

    private fun syncState(controller: RadioController) {
        mutableState.value = controller.state.value
    }
}
