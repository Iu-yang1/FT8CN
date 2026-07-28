package com.bg7yoz.ft8cn.core

import android.content.Context
import com.bg7yoz.ft8cn.data.local.Ft8cnFeatureDatabase
import com.bg7yoz.ft8cn.data.logbook.QsoLogRepository
import com.bg7yoz.ft8cn.data.logbook.RoomQsoLogRepository
import com.bg7yoz.ft8cn.data.logbook.LotwWorkflow
import com.bg7yoz.ft8cn.data.settings.FeatureSettingsStore
import com.bg7yoz.ft8cn.satellite.CelesTrakCatalogClient
import com.bg7yoz.ft8cn.satellite.RoomSatelliteCatalogRepository
import com.bg7yoz.ft8cn.satellite.SatelliteCatalogRepository
import com.bg7yoz.ft8cn.satellite.SatNogsCatalogClient
import com.bg7yoz.ft8cn.satellite.UrlConnectionSatelliteHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    companion object {
        @Volatile
        private var instance: FeatureAppGraph? = null

        fun from(context: Context): FeatureAppGraph = instance ?: synchronized(this) {
            instance ?: FeatureAppGraph(context).also { instance = it }
        }
    }
}
