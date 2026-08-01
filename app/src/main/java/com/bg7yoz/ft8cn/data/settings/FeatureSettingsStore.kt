package com.bg7yoz.ft8cn.data.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

data class FeatureSettings(
    val schemaVersion: Int = FeatureSettingsStore.CURRENT_SCHEMA_VERSION,
    val selectedDestination: String = "decode",
    val gnssTimeEnabled: Boolean = true,
    val rigctldHost: String = "127.0.0.1",
    val rigctldPort: Int = 4_532,
    val radioBackend: String = "RIGCTLD",
    val hamlibModelId: Long = 0L,
    val hamlibModelName: String = "",
    val hamlibEndpoint: String = "",
    val hamlibPollIntervalMs: Int = 1_000,
    val hamlibBaud: Int = 4_800,
    val hamlibDataBits: Int = 0,
    val hamlibStopBits: Int = 0,
    val hamlibHandshake: String = "DEFAULT",
    val hamlibForceDtr: String = "DEFAULT",
    val hamlibForceRts: String = "DEFAULT",
    val hamlibPttMethod: String = "VOX",
    val hamlibPttEndpoint: String = "",
    val hamlibAudioSource: String = "FRONT",
    val hamlibDataMode: String = "DATA_USB",
    val hamlibTxDelayMs: Int = 100,
    val hamlibAutoPowerOn: Boolean = false,
    val hamlibAutoPowerOff: Boolean = false,
    val hamlibQuerySMeter: Boolean = false,
    val splitStrategy: String = "NONE",
    val emeModeEnabled: Boolean = false,
    val satelliteModeEnabled: Boolean = false,
    val previousFtxMode: Int = 0,
    val q65Submode: Int = 0,
    val q65TrPeriodSeconds: Int = 60,
    val emeBaseFrequencyHz: Long = 144_120_000L,
)

class FeatureSettingsStore(private val dataStore: DataStore<Preferences>) {
    val state: Flow<FeatureSettings> = dataStore.data.map { preferences ->
        FeatureSettings(
            schemaVersion = preferences[SCHEMA_VERSION] ?: 0,
            selectedDestination = preferences[SELECTED_DESTINATION] ?: "decode",
            gnssTimeEnabled = preferences[GNSS_TIME_ENABLED] ?: true,
            rigctldHost = preferences[RIGCTLD_HOST]?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
            rigctldPort = (preferences[RIGCTLD_PORT] ?: 4_532).coerceIn(1, 65_535),
            radioBackend = preferences[RADIO_BACKEND]?.takeIf { it in setOf("NATIVE", "RIGCTLD") }
                ?: "RIGCTLD",
            hamlibModelId = (preferences[HAMLIB_MODEL_ID] ?: 0L).coerceAtLeast(0L),
            hamlibModelName = preferences[HAMLIB_MODEL_NAME].orEmpty(),
            hamlibEndpoint = preferences[HAMLIB_ENDPOINT].orEmpty(),
            hamlibPollIntervalMs = (preferences[HAMLIB_POLL_INTERVAL] ?: 1_000).coerceIn(0, 60_000),
            hamlibBaud = (preferences[HAMLIB_BAUD] ?: 4_800).coerceIn(300, 1_000_000),
            hamlibDataBits = (preferences[HAMLIB_DATA_BITS] ?: 0).takeIf { it == 0 || it in 5..8 } ?: 0,
            hamlibStopBits = (preferences[HAMLIB_STOP_BITS] ?: 0).takeIf { it in 0..2 } ?: 0,
            hamlibHandshake = preferences[HAMLIB_HANDSHAKE]?.takeIf {
                it in setOf("DEFAULT", "NONE", "XON_XOFF", "HARDWARE")
            } ?: "DEFAULT",
            hamlibForceDtr = preferences[HAMLIB_FORCE_DTR]?.takeIf {
                it in setOf("DEFAULT", "HIGH", "LOW")
            } ?: "DEFAULT",
            hamlibForceRts = preferences[HAMLIB_FORCE_RTS]?.takeIf {
                it in setOf("DEFAULT", "HIGH", "LOW")
            } ?: "DEFAULT",
            hamlibPttMethod = preferences[HAMLIB_PTT_METHOD]?.takeIf {
                it in setOf("VOX", "CAT", "DTR", "RTS")
            } ?: "VOX",
            hamlibPttEndpoint = preferences[HAMLIB_PTT_ENDPOINT].orEmpty(),
            hamlibAudioSource = preferences[HAMLIB_AUDIO_SOURCE]?.takeIf {
                it in setOf("FRONT", "REAR_DATA")
            } ?: "FRONT",
            hamlibDataMode = preferences[HAMLIB_DATA_MODE]?.takeIf {
                it in setOf("NONE", "USB", "DATA_USB")
            } ?: "DATA_USB",
            hamlibTxDelayMs = (preferences[HAMLIB_TX_DELAY] ?: 100).coerceIn(0, 2_000),
            hamlibAutoPowerOn = preferences[HAMLIB_AUTO_POWER_ON] ?: false,
            hamlibAutoPowerOff = preferences[HAMLIB_AUTO_POWER_OFF] ?: false,
            hamlibQuerySMeter = preferences[HAMLIB_QUERY_S_METER] ?: false,
            splitStrategy = preferences[SPLIT_STRATEGY] ?: "NONE",
            emeModeEnabled = preferences[EME_MODE_ENABLED] ?: false,
            satelliteModeEnabled = preferences[SATELLITE_MODE_ENABLED] ?: false,
            previousFtxMode = (preferences[PREVIOUS_FTX_MODE] ?: 0).coerceIn(0, 1),
            q65Submode = (preferences[Q65_SUBMODE] ?: 0).coerceIn(0, 4),
            q65TrPeriodSeconds = (preferences[Q65_TR_PERIOD] ?: 60)
                .takeIf { it in setOf(15, 30, 60, 120, 300) } ?: 60,
            emeBaseFrequencyHz = (preferences[EME_BASE_FREQUENCY] ?: 144_120_000L)
                .coerceIn(100_000L, 100_000_000_000L),
        )
    }

    /** 仅供已有 Java 工作线程在建立不可变发射快照时调用，禁止在主线程使用。 */
    fun snapshotBlocking(): FeatureSettings = runBlocking { state.first() }

    suspend fun setSelectedDestination(route: String) {
        require(route.isNotBlank()) { "路由不能为空" }
        dataStore.edit { it[SELECTED_DESTINATION] = route }
    }

    suspend fun setGnssTimeEnabled(enabled: Boolean) {
        dataStore.edit { it[GNSS_TIME_ENABLED] = enabled }
    }

    suspend fun setRigctldProfile(host: String, port: Int) {
        val normalizedHost = host.trim()
        require(normalizedHost.isNotEmpty()) { "Hamlib 主机不能为空" }
        require(port in 1..65_535) { "Hamlib 端口无效" }
        dataStore.edit {
            it[RIGCTLD_HOST] = normalizedHost
            it[RIGCTLD_PORT] = port
        }
    }

    suspend fun setRadioBackend(backend: String) {
        require(backend in setOf("NATIVE", "RIGCTLD")) { "Hamlib 后端无效" }
        dataStore.edit { it[RADIO_BACKEND] = backend }
    }

    @Suppress("LongParameterList")
    suspend fun setNativeHamlibProfile(
        modelId: Long,
        modelName: String,
        endpoint: String,
        pollIntervalMs: Int,
        baud: Int,
        dataBits: Int,
        stopBits: Int,
        handshake: String,
        forceDtr: String,
        forceRts: String,
        pttMethod: String,
        pttEndpoint: String,
        audioSource: String,
        dataMode: String,
        txDelayMs: Int,
        autoPowerOn: Boolean,
        autoPowerOff: Boolean,
        querySMeter: Boolean,
    ) {
        require(modelId > 0) { "请选择 Hamlib 电台型号" }
        require(endpoint.isNotBlank()) { "CAT 端点不能为空" }
        require(pollIntervalMs in 0..60_000 && txDelayMs in 0..2_000) { "轮询或发射延迟无效" }
        require(baud in 300..1_000_000) { "串口波特率无效" }
        require(dataBits == 0 || dataBits in 5..8) { "数据位无效" }
        require(stopBits in 0..2) { "停止位无效" }
        require(handshake in setOf("DEFAULT", "NONE", "XON_XOFF", "HARDWARE")) { "握手方式无效" }
        require(forceDtr in setOf("DEFAULT", "HIGH", "LOW")) { "DTR 设置无效" }
        require(forceRts in setOf("DEFAULT", "HIGH", "LOW")) { "RTS 设置无效" }
        require(pttMethod in setOf("VOX", "CAT", "DTR", "RTS")) { "PTT 方法无效" }
        require(audioSource in setOf("FRONT", "REAR_DATA")) { "发射音频源无效" }
        require(dataMode in setOf("NONE", "USB", "DATA_USB")) { "数据模式无效" }
        dataStore.edit {
            it[HAMLIB_MODEL_ID] = modelId
            it[HAMLIB_MODEL_NAME] = modelName.trim()
            it[HAMLIB_ENDPOINT] = endpoint.trim()
            it[HAMLIB_POLL_INTERVAL] = pollIntervalMs
            it[HAMLIB_BAUD] = baud
            it[HAMLIB_DATA_BITS] = dataBits
            it[HAMLIB_STOP_BITS] = stopBits
            it[HAMLIB_HANDSHAKE] = handshake
            it[HAMLIB_FORCE_DTR] = forceDtr
            it[HAMLIB_FORCE_RTS] = forceRts
            it[HAMLIB_PTT_METHOD] = pttMethod
            it[HAMLIB_PTT_ENDPOINT] = pttEndpoint.trim()
            it[HAMLIB_AUDIO_SOURCE] = audioSource
            it[HAMLIB_DATA_MODE] = dataMode
            it[HAMLIB_TX_DELAY] = txDelayMs
            it[HAMLIB_AUTO_POWER_ON] = autoPowerOn
            it[HAMLIB_AUTO_POWER_OFF] = autoPowerOff
            it[HAMLIB_QUERY_S_METER] = querySMeter
        }
    }

    suspend fun setSplitStrategy(strategy: String) {
        require(strategy in setOf("NONE", "RIG_SPLIT", "FAKE_IT")) { "split 策略无效" }
        dataStore.edit { it[SPLIT_STRATEGY] = strategy }
    }

    suspend fun setEmeMode(enabled: Boolean, previousFtxMode: Int) {
        require(previousFtxMode in 0..1) { "只能恢复到 FT8 或 FT4" }
        dataStore.edit {
            it[EME_MODE_ENABLED] = enabled
            if (enabled) it[SATELLITE_MODE_ENABLED] = false
            it[PREVIOUS_FTX_MODE] = previousFtxMode
        }
    }

    suspend fun setSatelliteMode(enabled: Boolean, previousFtxMode: Int) {
        require(previousFtxMode in 0..1) { "只能恢复到 FT8 或 FT4" }
        dataStore.edit {
            it[SATELLITE_MODE_ENABLED] = enabled
            if (enabled) it[EME_MODE_ENABLED] = false
            it[PREVIOUS_FTX_MODE] = previousFtxMode
        }
    }

    /** 保存普通工作档位中的 FT8/FT4 选择，供退出 EME 或卫星模式时恢复。 */
    suspend fun setPreviousFtxMode(mode: Int) {
        require(mode in 0..1) { "只能保存 FT8 或 FT4 模式" }
        dataStore.edit { it[PREVIOUS_FTX_MODE] = mode }
    }

    suspend fun setQ65Configuration(submode: Int, trPeriodSeconds: Int) {
        require(submode in 0..4) { "正式 Q65 子模式仅支持 A-E" }
        require(trPeriodSeconds in setOf(15, 30, 60, 120, 300)) { "Q65 周期无效" }
        dataStore.edit {
            it[Q65_SUBMODE] = submode
            it[Q65_TR_PERIOD] = trPeriodSeconds
        }
    }

    suspend fun setEmeBaseFrequency(frequencyHz: Long) {
        require(frequencyHz in 100_000L..100_000_000_000L) { "EME 基准频率无效" }
        dataStore.edit { it[EME_BASE_FREQUENCY] = frequencyHz }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 6
        const val FILE_NAME = "feature_settings.preferences_pb"

        internal val SCHEMA_VERSION = intPreferencesKey("schema_version")
        internal val SELECTED_DESTINATION = stringPreferencesKey("selected_destination")
        internal val GNSS_TIME_ENABLED = booleanPreferencesKey("gnss_time_enabled")
        internal val RIGCTLD_HOST = stringPreferencesKey("rigctld_host")
        internal val RIGCTLD_PORT = intPreferencesKey("rigctld_port")
        internal val RADIO_BACKEND = stringPreferencesKey("radio_backend")
        internal val HAMLIB_MODEL_ID = longPreferencesKey("hamlib_model_id")
        internal val HAMLIB_MODEL_NAME = stringPreferencesKey("hamlib_model_name")
        internal val HAMLIB_ENDPOINT = stringPreferencesKey("hamlib_endpoint")
        internal val HAMLIB_POLL_INTERVAL = intPreferencesKey("hamlib_poll_interval_ms")
        internal val HAMLIB_BAUD = intPreferencesKey("hamlib_baud")
        internal val HAMLIB_DATA_BITS = intPreferencesKey("hamlib_data_bits")
        internal val HAMLIB_STOP_BITS = intPreferencesKey("hamlib_stop_bits")
        internal val HAMLIB_HANDSHAKE = stringPreferencesKey("hamlib_handshake")
        internal val HAMLIB_FORCE_DTR = stringPreferencesKey("hamlib_force_dtr")
        internal val HAMLIB_FORCE_RTS = stringPreferencesKey("hamlib_force_rts")
        internal val HAMLIB_PTT_METHOD = stringPreferencesKey("hamlib_ptt_method")
        internal val HAMLIB_PTT_ENDPOINT = stringPreferencesKey("hamlib_ptt_endpoint")
        internal val HAMLIB_AUDIO_SOURCE = stringPreferencesKey("hamlib_audio_source")
        internal val HAMLIB_DATA_MODE = stringPreferencesKey("hamlib_data_mode")
        internal val HAMLIB_TX_DELAY = intPreferencesKey("hamlib_tx_delay_ms")
        internal val HAMLIB_AUTO_POWER_ON = booleanPreferencesKey("hamlib_auto_power_on")
        internal val HAMLIB_AUTO_POWER_OFF = booleanPreferencesKey("hamlib_auto_power_off")
        internal val HAMLIB_QUERY_S_METER = booleanPreferencesKey("hamlib_query_s_meter")
        internal val SPLIT_STRATEGY = stringPreferencesKey("split_strategy")
        internal val EME_MODE_ENABLED = booleanPreferencesKey("eme_mode_enabled")
        internal val SATELLITE_MODE_ENABLED = booleanPreferencesKey("satellite_mode_enabled")
        internal val PREVIOUS_FTX_MODE = intPreferencesKey("previous_ftx_mode")
        internal val Q65_SUBMODE = intPreferencesKey("q65_submode")
        internal val Q65_TR_PERIOD = intPreferencesKey("q65_tr_period_seconds")
        internal val EME_BASE_FREQUENCY = longPreferencesKey("eme_base_frequency_hz")

        fun create(context: Context, scope: CoroutineScope): FeatureSettingsStore {
            val dataStore = PreferenceDataStoreFactory.create(
                migrations = listOf(FeatureSettingsMigration),
                scope = scope,
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            )
            return FeatureSettingsStore(dataStore)
        }
    }
}

object FeatureSettingsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[FeatureSettingsStore.SCHEMA_VERSION] ?: 0) < FeatureSettingsStore.CURRENT_SCHEMA_VERSION

    override suspend fun migrate(currentData: Preferences): Preferences {
        val migrated = mutablePreferencesOf()
        currentData[FeatureSettingsStore.SELECTED_DESTINATION]?.let {
            migrated[FeatureSettingsStore.SELECTED_DESTINATION] = it
        }
        currentData[FeatureSettingsStore.GNSS_TIME_ENABLED]?.let {
            migrated[FeatureSettingsStore.GNSS_TIME_ENABLED] = it
        }
        currentData[FeatureSettingsStore.RIGCTLD_HOST]?.let {
            migrated[FeatureSettingsStore.RIGCTLD_HOST] = it
        }
        currentData[FeatureSettingsStore.RIGCTLD_PORT]?.let {
            migrated[FeatureSettingsStore.RIGCTLD_PORT] = it
        }
        currentData[FeatureSettingsStore.RADIO_BACKEND]?.let { migrated[FeatureSettingsStore.RADIO_BACKEND] = it }
        currentData[FeatureSettingsStore.HAMLIB_MODEL_ID]?.let { migrated[FeatureSettingsStore.HAMLIB_MODEL_ID] = it }
        currentData[FeatureSettingsStore.HAMLIB_MODEL_NAME]?.let { migrated[FeatureSettingsStore.HAMLIB_MODEL_NAME] = it }
        currentData[FeatureSettingsStore.HAMLIB_ENDPOINT]?.let { migrated[FeatureSettingsStore.HAMLIB_ENDPOINT] = it }
        currentData[FeatureSettingsStore.HAMLIB_POLL_INTERVAL]?.let { migrated[FeatureSettingsStore.HAMLIB_POLL_INTERVAL] = it }
        currentData[FeatureSettingsStore.HAMLIB_BAUD]?.let { migrated[FeatureSettingsStore.HAMLIB_BAUD] = it }
        currentData[FeatureSettingsStore.HAMLIB_DATA_BITS]?.let { migrated[FeatureSettingsStore.HAMLIB_DATA_BITS] = it }
        currentData[FeatureSettingsStore.HAMLIB_STOP_BITS]?.let { migrated[FeatureSettingsStore.HAMLIB_STOP_BITS] = it }
        currentData[FeatureSettingsStore.HAMLIB_HANDSHAKE]?.let { migrated[FeatureSettingsStore.HAMLIB_HANDSHAKE] = it }
        currentData[FeatureSettingsStore.HAMLIB_FORCE_DTR]?.let { migrated[FeatureSettingsStore.HAMLIB_FORCE_DTR] = it }
        currentData[FeatureSettingsStore.HAMLIB_FORCE_RTS]?.let { migrated[FeatureSettingsStore.HAMLIB_FORCE_RTS] = it }
        currentData[FeatureSettingsStore.HAMLIB_PTT_METHOD]?.let { migrated[FeatureSettingsStore.HAMLIB_PTT_METHOD] = it }
        currentData[FeatureSettingsStore.HAMLIB_PTT_ENDPOINT]?.let { migrated[FeatureSettingsStore.HAMLIB_PTT_ENDPOINT] = it }
        currentData[FeatureSettingsStore.HAMLIB_AUDIO_SOURCE]?.let { migrated[FeatureSettingsStore.HAMLIB_AUDIO_SOURCE] = it }
        currentData[FeatureSettingsStore.HAMLIB_DATA_MODE]?.let { migrated[FeatureSettingsStore.HAMLIB_DATA_MODE] = it }
        currentData[FeatureSettingsStore.HAMLIB_TX_DELAY]?.let { migrated[FeatureSettingsStore.HAMLIB_TX_DELAY] = it }
        currentData[FeatureSettingsStore.HAMLIB_AUTO_POWER_ON]?.let { migrated[FeatureSettingsStore.HAMLIB_AUTO_POWER_ON] = it }
        currentData[FeatureSettingsStore.HAMLIB_AUTO_POWER_OFF]?.let { migrated[FeatureSettingsStore.HAMLIB_AUTO_POWER_OFF] = it }
        currentData[FeatureSettingsStore.HAMLIB_QUERY_S_METER]?.let { migrated[FeatureSettingsStore.HAMLIB_QUERY_S_METER] = it }
        currentData[FeatureSettingsStore.SPLIT_STRATEGY]?.let {
            migrated[FeatureSettingsStore.SPLIT_STRATEGY] = it
        }
        currentData[FeatureSettingsStore.EME_MODE_ENABLED]?.let {
            migrated[FeatureSettingsStore.EME_MODE_ENABLED] = it
        }
        currentData[FeatureSettingsStore.SATELLITE_MODE_ENABLED]?.let {
            migrated[FeatureSettingsStore.SATELLITE_MODE_ENABLED] = it
        }
        currentData[FeatureSettingsStore.PREVIOUS_FTX_MODE]?.let {
            migrated[FeatureSettingsStore.PREVIOUS_FTX_MODE] = it
        }
        currentData[FeatureSettingsStore.Q65_SUBMODE]?.let {
            migrated[FeatureSettingsStore.Q65_SUBMODE] = it
        }
        currentData[FeatureSettingsStore.Q65_TR_PERIOD]?.let {
            migrated[FeatureSettingsStore.Q65_TR_PERIOD] = it
        }
        currentData[FeatureSettingsStore.EME_BASE_FREQUENCY]?.let {
            migrated[FeatureSettingsStore.EME_BASE_FREQUENCY] = it
        }
        migrated[FeatureSettingsStore.SCHEMA_VERSION] = FeatureSettingsStore.CURRENT_SCHEMA_VERSION
        if (migrated[FeatureSettingsStore.SELECTED_DESTINATION] == null) {
            migrated[FeatureSettingsStore.SELECTED_DESTINATION] = "decode"
        }
        if (migrated[FeatureSettingsStore.GNSS_TIME_ENABLED] == null) {
            migrated[FeatureSettingsStore.GNSS_TIME_ENABLED] = true
        }
        if (migrated[FeatureSettingsStore.RIGCTLD_HOST] == null) {
            migrated[FeatureSettingsStore.RIGCTLD_HOST] = "127.0.0.1"
        }
        if (migrated[FeatureSettingsStore.RIGCTLD_PORT] == null) {
            migrated[FeatureSettingsStore.RIGCTLD_PORT] = 4_532
        }
        if (migrated[FeatureSettingsStore.RADIO_BACKEND] == null) migrated[FeatureSettingsStore.RADIO_BACKEND] = "RIGCTLD"
        if (migrated[FeatureSettingsStore.HAMLIB_MODEL_ID] == null) migrated[FeatureSettingsStore.HAMLIB_MODEL_ID] = 0L
        if (migrated[FeatureSettingsStore.HAMLIB_MODEL_NAME] == null) migrated[FeatureSettingsStore.HAMLIB_MODEL_NAME] = ""
        if (migrated[FeatureSettingsStore.HAMLIB_ENDPOINT] == null) migrated[FeatureSettingsStore.HAMLIB_ENDPOINT] = ""
        if (migrated[FeatureSettingsStore.HAMLIB_POLL_INTERVAL] == null) migrated[FeatureSettingsStore.HAMLIB_POLL_INTERVAL] = 1_000
        if (migrated[FeatureSettingsStore.HAMLIB_BAUD] == null) migrated[FeatureSettingsStore.HAMLIB_BAUD] = 4_800
        if (migrated[FeatureSettingsStore.HAMLIB_DATA_BITS] == null) migrated[FeatureSettingsStore.HAMLIB_DATA_BITS] = 0
        if (migrated[FeatureSettingsStore.HAMLIB_STOP_BITS] == null) migrated[FeatureSettingsStore.HAMLIB_STOP_BITS] = 0
        if (migrated[FeatureSettingsStore.HAMLIB_HANDSHAKE] == null) migrated[FeatureSettingsStore.HAMLIB_HANDSHAKE] = "DEFAULT"
        if (migrated[FeatureSettingsStore.HAMLIB_FORCE_DTR] == null) migrated[FeatureSettingsStore.HAMLIB_FORCE_DTR] = "DEFAULT"
        if (migrated[FeatureSettingsStore.HAMLIB_FORCE_RTS] == null) migrated[FeatureSettingsStore.HAMLIB_FORCE_RTS] = "DEFAULT"
        if (migrated[FeatureSettingsStore.HAMLIB_PTT_METHOD] == null) migrated[FeatureSettingsStore.HAMLIB_PTT_METHOD] = "VOX"
        if (migrated[FeatureSettingsStore.HAMLIB_PTT_ENDPOINT] == null) migrated[FeatureSettingsStore.HAMLIB_PTT_ENDPOINT] = ""
        if (migrated[FeatureSettingsStore.HAMLIB_AUDIO_SOURCE] == null) migrated[FeatureSettingsStore.HAMLIB_AUDIO_SOURCE] = "FRONT"
        if (migrated[FeatureSettingsStore.HAMLIB_DATA_MODE] == null) migrated[FeatureSettingsStore.HAMLIB_DATA_MODE] = "DATA_USB"
        if (migrated[FeatureSettingsStore.HAMLIB_TX_DELAY] == null) migrated[FeatureSettingsStore.HAMLIB_TX_DELAY] = 100
        if (migrated[FeatureSettingsStore.HAMLIB_AUTO_POWER_ON] == null) migrated[FeatureSettingsStore.HAMLIB_AUTO_POWER_ON] = false
        if (migrated[FeatureSettingsStore.HAMLIB_AUTO_POWER_OFF] == null) migrated[FeatureSettingsStore.HAMLIB_AUTO_POWER_OFF] = false
        if (migrated[FeatureSettingsStore.HAMLIB_QUERY_S_METER] == null) migrated[FeatureSettingsStore.HAMLIB_QUERY_S_METER] = false
        if (migrated[FeatureSettingsStore.SPLIT_STRATEGY] == null) {
            migrated[FeatureSettingsStore.SPLIT_STRATEGY] = "NONE"
        }
        if (migrated[FeatureSettingsStore.EME_MODE_ENABLED] == null) {
            migrated[FeatureSettingsStore.EME_MODE_ENABLED] = false
        }
        if (migrated[FeatureSettingsStore.SATELLITE_MODE_ENABLED] == null) {
            migrated[FeatureSettingsStore.SATELLITE_MODE_ENABLED] = false
        }
        if (migrated[FeatureSettingsStore.PREVIOUS_FTX_MODE] == null) {
            migrated[FeatureSettingsStore.PREVIOUS_FTX_MODE] = 0
        }
        if (migrated[FeatureSettingsStore.Q65_SUBMODE] == null) {
            migrated[FeatureSettingsStore.Q65_SUBMODE] = 0
        }
        if (migrated[FeatureSettingsStore.Q65_TR_PERIOD] == null) {
            migrated[FeatureSettingsStore.Q65_TR_PERIOD] = 60
        }
        if (migrated[FeatureSettingsStore.EME_BASE_FREQUENCY] == null) {
            migrated[FeatureSettingsStore.EME_BASE_FREQUENCY] = 144_120_000L
        }
        return migrated
    }

    override suspend fun cleanUp() = Unit
}
