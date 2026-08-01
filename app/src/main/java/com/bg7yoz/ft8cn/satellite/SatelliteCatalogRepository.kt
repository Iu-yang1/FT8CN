package com.bg7yoz.ft8cn.satellite

import com.bg7yoz.ft8cn.data.local.SatelliteDao
import com.bg7yoz.ft8cn.data.local.SatelliteEntity
import com.bg7yoz.ft8cn.data.local.SatelliteSourceMetadataEntity
import com.bg7yoz.ft8cn.data.local.TleEntity
import com.bg7yoz.ft8cn.data.local.TransponderEntity
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

sealed interface SatelliteRefreshResult {
    data class Updated(val recordCount: Int, val payloadSha256: String) : SatelliteRefreshResult
    object NotModified : SatelliteRefreshResult
    data class Throttled(val retryAfterUtcMillis: Long) : SatelliteRefreshResult
}

interface SatelliteCatalogRepository {
    fun observeSatellites(): Flow<List<SatelliteEntity>>
    suspend fun refreshCelesTrakGroup(group: String, nowUtcMillis: Long): SatelliteRefreshResult
    suspend fun importTle(payload: String, source: String, nowUtcMillis: Long): Int
    suspend fun latestPropagator(catalogNumber: Int): Sgp4OrbitPropagator?
    suspend fun transponders(catalogNumber: Int): List<SatelliteTransponder>
    suspend fun refreshSatNogsTransmitters(catalogNumber: Int, nowUtcMillis: Long): SatelliteRefreshResult
    suspend fun setFavorite(catalogNumber: Int, favorite: Boolean): Boolean
}

class RoomSatelliteCatalogRepository(
    private val dao: SatelliteDao,
    private val celesTrak: CelesTrakCatalogClient,
    private val satNogs: SatNogsCatalogClient,
) : SatelliteCatalogRepository {
    override fun observeSatellites(): Flow<List<SatelliteEntity>> = dao.observeSatellites()

    override suspend fun refreshCelesTrakGroup(
        group: String,
        nowUtcMillis: Long,
    ): SatelliteRefreshResult {
        val sourceKey = "celestrak:$group"
        val existing = dao.sourceMetadata(sourceKey)
        if (existing != null && nowUtcMillis < existing.nextEligibleUtcMillis) {
            return SatelliteRefreshResult.Throttled(existing.nextEligibleUtcMillis)
        }
        return try {
            when (val response = celesTrak.fetchGroup(group, existing)) {
                CatalogFetchResult.NotModified -> {
                    dao.upsertSourceMetadata(successMetadata(
                        sourceKey,
                        existing,
                        nowUtcMillis,
                        existing?.payloadSha256,
                    ))
                    SatelliteRefreshResult.NotModified
                }
                is CatalogFetchResult.Updated -> {
                    val records = TleCatalogParser.parse(response.payload, sourceKey, nowUtcMillis)
                    require(records.isNotEmpty()) { "CelesTrak 目录没有有效 TLE" }
                    val sha = sha256(response.payload.toByteArray(Charsets.UTF_8))
                    saveRecords(
                        records,
                        successMetadata(
                            sourceKey,
                            existing,
                            nowUtcMillis,
                            sha,
                            response.etag,
                            response.lastModified,
                        ),
                    )
                    SatelliteRefreshResult.Updated(records.size, sha)
                }
            }
        } catch (failure: Exception) {
            dao.upsertSourceMetadata(
                SatelliteSourceMetadataEntity(
                    sourceKey = sourceKey,
                    etag = existing?.etag,
                    lastModified = existing?.lastModified,
                    lastAttemptUtcMillis = nowUtcMillis,
                    lastSuccessUtcMillis = existing?.lastSuccessUtcMillis ?: 0L,
                    nextEligibleUtcMillis = nowUtcMillis + FAILURE_RETRY_MILLIS,
                    payloadSha256 = existing?.payloadSha256,
                    lastError = (failure.message ?: failure.javaClass.simpleName).take(240),
                ),
            )
            throw failure
        }
    }

    override suspend fun importTle(payload: String, source: String, nowUtcMillis: Long): Int {
        require(source.isNotBlank())
        val records = TleCatalogParser.parse(payload, source.take(80), nowUtcMillis)
        require(records.isNotEmpty()) { "导入内容没有有效 TLE" }
        val sourceKey = "manual:${source.take(60)}"
        saveRecords(
            records,
            successMetadata(
                sourceKey,
                null,
                nowUtcMillis,
                sha256(payload.toByteArray(Charsets.UTF_8)),
            ),
        )
        return records.size
    }

    override suspend fun latestPropagator(catalogNumber: Int): Sgp4OrbitPropagator? {
        val entity = dao.latestTle(catalogNumber) ?: return null
        val satellite = dao.findSatellite(catalogNumber)
        return Sgp4OrbitPropagator.parse(
            name = satellite?.name.orEmpty(),
            line1 = entity.line1,
            line2 = entity.line2,
            source = entity.source,
            fetchedUtcMillis = entity.fetchedUtcMillis,
        )
    }

    override suspend fun transponders(catalogNumber: Int): List<SatelliteTransponder> =
        dao.transponders(catalogNumber).map {
            SatelliteTransponder(
                name = it.name,
                uplinkLowHz = it.uplinkLowHz,
                uplinkHighHz = it.uplinkHighHz,
                downlinkLowHz = it.downlinkLowHz,
                downlinkHighHz = it.downlinkHighHz,
                mode = it.mode,
                inverted = it.inverted,
            )
        }

    override suspend fun refreshSatNogsTransmitters(
        catalogNumber: Int,
        nowUtcMillis: Long,
    ): SatelliteRefreshResult {
        val sourceKey = "satnogs:$catalogNumber"
        val existing = dao.sourceMetadata(sourceKey)
        if (existing != null && nowUtcMillis < existing.nextEligibleUtcMillis) {
            return SatelliteRefreshResult.Throttled(existing.nextEligibleUtcMillis)
        }
        return try {
            when (val response = satNogs.fetchTransmitters(catalogNumber, existing)) {
                CatalogFetchResult.NotModified -> {
                    dao.upsertSourceMetadata(successMetadata(
                        sourceKey,
                        existing,
                        nowUtcMillis,
                        existing?.payloadSha256,
                    ))
                    SatelliteRefreshResult.NotModified
                }
                is CatalogFetchResult.Updated -> {
                    val parsed = SatNogsTransmitterParser.parse(response.payload, catalogNumber)
                    val sha = sha256(response.payload.toByteArray(Charsets.UTF_8))
                    val metadata = successMetadata(
                        sourceKey,
                        existing,
                        nowUtcMillis,
                        sha,
                        response.etag,
                        response.lastModified,
                    )
                    dao.replaceTransponders(
                        catalogNumber,
                        parsed.map {
                            TransponderEntity(
                                satelliteCatalogNumber = catalogNumber,
                                name = it.name,
                                uplinkLowHz = it.uplinkLowHz,
                                uplinkHighHz = it.uplinkHighHz,
                                downlinkLowHz = it.downlinkLowHz,
                                downlinkHighHz = it.downlinkHighHz,
                                mode = it.mode,
                                inverted = it.inverted,
                            )
                        },
                        metadata,
                    )
                    SatelliteRefreshResult.Updated(parsed.size, sha)
                }
            }
        } catch (failure: Exception) {
            dao.upsertSourceMetadata(
                SatelliteSourceMetadataEntity(
                    sourceKey = sourceKey,
                    etag = existing?.etag,
                    lastModified = existing?.lastModified,
                    lastAttemptUtcMillis = nowUtcMillis,
                    lastSuccessUtcMillis = existing?.lastSuccessUtcMillis ?: 0L,
                    nextEligibleUtcMillis = nowUtcMillis + FAILURE_RETRY_MILLIS,
                    payloadSha256 = existing?.payloadSha256,
                    lastError = (failure.message ?: failure.javaClass.simpleName).take(240),
                ),
            )
            throw failure
        }
    }

    override suspend fun setFavorite(catalogNumber: Int, favorite: Boolean): Boolean =
        dao.setFavorite(catalogNumber, favorite) == 1

    private suspend fun saveRecords(
        records: List<TleRecord>,
        metadata: SatelliteSourceMetadataEntity,
    ) {
        dao.saveTleCatalog(
            satellites = records.map {
                SatelliteEntity(
                    catalogNumber = it.catalogNumber,
                    name = it.name,
                    favorite = false,
                    updatedUtcMillis = metadata.lastAttemptUtcMillis,
                )
            },
            tles = records.map {
                TleEntity(
                    satelliteCatalogNumber = it.catalogNumber,
                    epochUtcMillis = it.epochUtcMillis,
                    line1 = it.line1,
                    line2 = it.line2,
                    source = it.source,
                    fetchedUtcMillis = it.fetchedUtcMillis,
                )
            },
            metadata = metadata,
        )
    }

    private fun successMetadata(
        sourceKey: String,
        existing: SatelliteSourceMetadataEntity?,
        nowUtcMillis: Long,
        payloadSha256: String?,
        etag: String? = existing?.etag,
        lastModified: String? = existing?.lastModified,
    ) = SatelliteSourceMetadataEntity(
        sourceKey = sourceKey,
        etag = etag,
        lastModified = lastModified,
        lastAttemptUtcMillis = nowUtcMillis,
        lastSuccessUtcMillis = nowUtcMillis,
        nextEligibleUtcMillis = nowUtcMillis + MINIMUM_REFRESH_MILLIS,
        payloadSha256 = payloadSha256,
        lastError = null,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MINIMUM_REFRESH_MILLIS = 2L * 60L * 60L * 1_000L
        const val FAILURE_RETRY_MILLIS = 5L * 60L * 1_000L
    }
}
