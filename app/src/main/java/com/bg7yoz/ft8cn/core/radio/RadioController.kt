package com.bg7yoz.ft8cn.core.radio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RadioMode {
    USB,
    DATA_USB,
    FM,
    CW,
}

data class RadioState(
    val connected: Boolean = false,
    val model: String = "",
    val rxFrequencyHz: Long = 0,
    val txFrequencyHz: Long = 0,
    val mode: RadioMode = RadioMode.DATA_USB,
    val splitEnabled: Boolean = false,
    val transmitting: Boolean = false,
    val lastError: String? = null,
)

interface RadioController {
    val state: StateFlow<RadioState>

    suspend fun connect(profileId: Long): Result<Unit>
    suspend fun disconnect()
    suspend fun setFrequency(rxFrequencyHz: Long, txFrequencyHz: Long = rxFrequencyHz): Result<Unit>
    suspend fun setMode(mode: RadioMode, passbandHz: Int): Result<Unit>
    suspend fun setPtt(enabled: Boolean): Result<Unit>
}

class FakeRadioController(initial: RadioState = RadioState()) : RadioController {
    private val mutableState = MutableStateFlow(initial)

    override val state: StateFlow<RadioState> = mutableState.asStateFlow()

    override suspend fun connect(profileId: Long): Result<Unit> {
        mutableState.value = mutableState.value.copy(connected = true, model = "FAKE-$profileId")
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        mutableState.value = RadioState()
    }

    override suspend fun setFrequency(rxFrequencyHz: Long, txFrequencyHz: Long): Result<Unit> {
        if (!mutableState.value.connected || rxFrequencyHz <= 0 || txFrequencyHz <= 0) {
            return Result.failure(IllegalStateException("电台未连接或频率无效"))
        }
        mutableState.value = mutableState.value.copy(
            rxFrequencyHz = rxFrequencyHz,
            txFrequencyHz = txFrequencyHz,
            splitEnabled = rxFrequencyHz != txFrequencyHz,
        )
        return Result.success(Unit)
    }

    override suspend fun setMode(mode: RadioMode, passbandHz: Int): Result<Unit> {
        if (!mutableState.value.connected || passbandHz <= 0) {
            return Result.failure(IllegalStateException("电台未连接或通带无效"))
        }
        mutableState.value = mutableState.value.copy(mode = mode)
        return Result.success(Unit)
    }

    override suspend fun setPtt(enabled: Boolean): Result<Unit> {
        if (!mutableState.value.connected) {
            return Result.failure(IllegalStateException("电台未连接"))
        }
        mutableState.value = mutableState.value.copy(transmitting = enabled)
        return Result.success(Unit)
    }
}
