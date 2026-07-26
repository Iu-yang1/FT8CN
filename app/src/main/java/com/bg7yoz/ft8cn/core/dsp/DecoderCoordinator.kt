package com.bg7yoz.ft8cn.core.dsp

import com.bg7yoz.ft8cn.core.model.DecodeResultSummary
import com.bg7yoz.ft8cn.core.model.DecodeStage
import com.bg7yoz.ft8cn.core.model.FtxMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** PCM 由调用方按块提供，避免把完整 slot 放入 UI state。 */
interface PcmChunkSource {
    val sampleRate: Int
    val sampleCount: Long

    fun read(offset: Long, destination: FloatArray, destinationOffset: Int, length: Int): Int
}

data class DecodeRequest(
    val requestId: Long,
    val triggerUtcMillis: Long,
    val mode: FtxMode,
    val stage: DecodeStage,
    val inputIsLive: Boolean,
    val qsoFrequencyHz: Int,
    val txFrequencyHz: Int,
    val source: PcmChunkSource,
)

data class DecodeBatch(
    val requestId: Long,
    val results: List<DecodeResultSummary>,
    val elapsedMillis: Long,
)

data class DecoderState(
    val activeRequestId: Long? = null,
    val queuedRequestCount: Int = 0,
)

interface DecoderCoordinator {
    val state: StateFlow<DecoderState>

    suspend fun decode(request: DecodeRequest): DecodeBatch

    fun cancel(requestId: Long)
}

class FakeDecoderCoordinator(
    private val decodeBlock: suspend (DecodeRequest) -> DecodeBatch,
) : DecoderCoordinator {
    private val mutableState = MutableStateFlow(DecoderState())
    private val cancelledRequests = mutableSetOf<Long>()

    override val state: StateFlow<DecoderState> = mutableState.asStateFlow()

    override suspend fun decode(request: DecodeRequest): DecodeBatch {
        check(request.requestId !in cancelledRequests) { "请求已取消: ${request.requestId}" }
        mutableState.value = DecoderState(activeRequestId = request.requestId)
        return try {
            decodeBlock(request)
        } finally {
            mutableState.value = DecoderState()
        }
    }

    override fun cancel(requestId: Long) {
        cancelledRequests += requestId
        if (mutableState.value.activeRequestId == requestId) {
            mutableState.value = DecoderState()
        }
    }
}
