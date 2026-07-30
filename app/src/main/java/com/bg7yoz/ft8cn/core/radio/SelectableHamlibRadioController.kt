package com.bg7yoz.ft8cn.core.radio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HamlibBackend {
    NATIVE,
    RIGCTLD,
}

/** 在连接时选择进程内 Hamlib 或 rigctld，业务层始终只看到一个 RadioController。 */
class SelectableHamlibRadioController(
    private val backendProvider: suspend () -> HamlibBackend,
    private val nativeController: NativeHamlibRadioController,
    private val rigctldController: RigctldRadioController,
) : RadioController {
    private val mutableState = MutableStateFlow(RadioState(transport = RadioTransport.NATIVE_HAMLIB))
    private var active: RadioController? = null

    override val state: StateFlow<RadioState> = mutableState.asStateFlow()

    override suspend fun discoverModels(): List<RadioModel> {
        val native = nativeController.discoverModels()
        return native + rigctldController.discoverModels()
    }

    override suspend fun connect(profileId: Long): Result<Unit> {
        active?.disconnect()
        val selected = when (backendProvider()) {
            HamlibBackend.NATIVE -> nativeController
            HamlibBackend.RIGCTLD -> rigctldController
        }
        active = selected
        return selected.connect(profileId).also { syncState(selected) }
    }

    override suspend fun disconnect() {
        active?.disconnect()
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

    override suspend fun refreshState(): Result<RadioState> {
        val controller = active ?: return Result.failure(IllegalStateException("Hamlib 未连接"))
        return controller.refreshState().also { syncState(controller) }
    }

    override suspend fun emergencyStop(): Result<Unit> = withActive { it.emergencyStop() }

    private suspend fun <T> withActive(block: suspend (RadioController) -> Result<T>): Result<T> {
        val controller = active ?: return Result.failure(IllegalStateException("Hamlib 未连接"))
        return block(controller).also { syncState(controller) }
    }

    private fun syncState(controller: RadioController) {
        mutableState.value = controller.state.value
    }
}
