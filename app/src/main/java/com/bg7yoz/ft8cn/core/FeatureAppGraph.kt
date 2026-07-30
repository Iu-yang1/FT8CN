package com.bg7yoz.ft8cn.core

import android.content.Context
import com.bg7yoz.ft8cn.data.local.Ft8cnFeatureDatabase
import com.bg7yoz.ft8cn.data.logbook.QsoLogRepository
import com.bg7yoz.ft8cn.data.logbook.RoomQsoLogRepository
import com.bg7yoz.ft8cn.data.logbook.LotwWorkflow
import com.bg7yoz.ft8cn.data.settings.FeatureSettingsStore
import com.bg7yoz.ft8cn.core.radio.RadioController
import com.bg7yoz.ft8cn.core.radio.HamlibAudioSource
import com.bg7yoz.ft8cn.core.radio.HamlibBackend
import com.bg7yoz.ft8cn.core.radio.HamlibControlLine
import com.bg7yoz.ft8cn.core.radio.HamlibHandshake
import com.bg7yoz.ft8cn.core.radio.HamlibPttMethod
import com.bg7yoz.ft8cn.core.radio.NativeHamlibProfile
import com.bg7yoz.ft8cn.core.radio.NativeHamlibRadioController
import com.bg7yoz.ft8cn.core.radio.RigctldProfile
import com.bg7yoz.ft8cn.core.radio.RigctldRadioController
import com.bg7yoz.ft8cn.core.radio.RadioTransmitBridge
import com.bg7yoz.ft8cn.core.radio.SelectableHamlibRadioController
import com.bg7yoz.ft8cn.satellite.CelesTrakCatalogClient
import com.bg7yoz.ft8cn.satellite.RoomSatelliteCatalogRepository
import com.bg7yoz.ft8cn.satellite.SatelliteCatalogRepository
import com.bg7yoz.ft8cn.satellite.SatNogsCatalogClient
import com.bg7yoz.ft8cn.satellite.UrlConnectionSatelliteHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/** 应用级手工依赖图，避免页面自行创建数据库或 DataStore 实例。 */
class FeatureAppGraph private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: Ft8cnFeatureDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Ft8cnFeatureDatabase.create(applicationContext)
    }
    val settings: FeatureSettingsStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FeatureSettingsStore.create(applicationContext, applicationScope)
    }
    val qsoLogRepository: QsoLogRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomQsoLogRepository(database.qsoDao())
    }
    val lotwWorkflow: LotwWorkflow by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LotwWorkflow(applicationContext, qsoLogRepository, database.lotwUploadDao())
    }
    val satelliteCatalogRepository: SatelliteCatalogRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomSatelliteCatalogRepository(
            database.satelliteDao(),
            CelesTrakCatalogClient(UrlConnectionSatelliteHttpTransport()),
            SatNogsCatalogClient(UrlConnectionSatelliteHttpTransport(maximumResponseBytes = 2 * 1024 * 1024)),
        )
    }
    /** 机型私有 CAT 已退出产品入口，连接统一由进程内 Hamlib 或 rigctld 执行。 */
    val radioController: RadioController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val rigctld = RigctldRadioController(
            profileProvider = { _ ->
                val profile = settings.state.first()
                RigctldProfile(
                    host = profile.rigctldHost,
                    port = profile.rigctldPort,
                    modelName = "Hamlib ${profile.rigctldHost}:${profile.rigctldPort}",
                )
            },
        )
        val native = NativeHamlibRadioController(
            context = applicationContext,
            profileProvider = { _ ->
                val profile = settings.state.first()
                NativeHamlibProfile(
                    modelId = profile.hamlibModelId.toInt(),
                    modelName = profile.hamlibModelName,
                    endpoint = profile.hamlibEndpoint,
                    baud = profile.hamlibBaud,
                    dataBits = profile.hamlibDataBits,
                    stopBits = profile.hamlibStopBits,
                    handshake = enumValueOrDefault(profile.hamlibHandshake, HamlibHandshake.DEFAULT),
                    forceDtr = enumValueOrDefault(profile.hamlibForceDtr, HamlibControlLine.DEFAULT),
                    forceRts = enumValueOrDefault(profile.hamlibForceRts, HamlibControlLine.DEFAULT),
                    pttMethod = enumValueOrDefault(profile.hamlibPttMethod, HamlibPttMethod.VOX),
                    pttEndpoint = profile.hamlibPttEndpoint,
                    pollIntervalMs = profile.hamlibPollIntervalMs,
                    txDelayMs = profile.hamlibTxDelayMs,
                    audioSource = enumValueOrDefault(profile.hamlibAudioSource, HamlibAudioSource.FRONT),
                    autoPowerOn = profile.hamlibAutoPowerOn,
                    autoPowerOff = profile.hamlibAutoPowerOff,
                    querySMeter = profile.hamlibQuerySMeter,
                )
            },
        )
        SelectableHamlibRadioController(
            backendProvider = {
                when (settings.state.first().radioBackend) {
                    "NATIVE" -> HamlibBackend.NATIVE
                    else -> HamlibBackend.RIGCTLD
                }
            },
            nativeController = native,
            rigctldController = rigctld,
        )
    }
    val radioTransmitBridge: RadioTransmitBridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RadioTransmitBridge(radioController, applicationScope)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    companion object {
        @Volatile
        private var instance: FeatureAppGraph? = null

        @JvmStatic
        fun from(context: Context): FeatureAppGraph = instance ?: synchronized(this) {
            instance ?: FeatureAppGraph(context).also { instance = it }
        }
    }
}
