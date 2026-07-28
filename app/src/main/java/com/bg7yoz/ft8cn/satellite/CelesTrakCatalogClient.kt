package com.bg7yoz.ft8cn.satellite

import com.bg7yoz.ft8cn.data.local.SatelliteSourceMetadataEntity
import java.net.URLEncoder

sealed interface CatalogFetchResult {
    data class Updated(
        val payload: String,
        val etag: String?,
        val lastModified: String?,
    ) : CatalogFetchResult

    object NotModified : CatalogFetchResult
}

class CelesTrakCatalogClient(private val transport: SatelliteHttpTransport) {
    suspend fun fetchGroup(
        group: String,
        metadata: SatelliteSourceMetadataEntity?,
    ): CatalogFetchResult {
        require(group.matches(Regex("[a-z0-9-]{1,40}"))) { "CelesTrak group 无效" }
        val encoded = URLEncoder.encode(group, Charsets.UTF_8.name())
        val headers = buildMap {
            metadata?.etag?.takeIf(String::isNotBlank)?.let { put("If-None-Match", it) }
            metadata?.lastModified?.takeIf(String::isNotBlank)?.let { put("If-Modified-Since", it) }
        }
        val response = transport.get(
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=$encoded&FORMAT=TLE",
            headers,
        )
        return when (response.statusCode) {
            HttpURLConnectionCodes.NOT_MODIFIED -> CatalogFetchResult.NotModified
            HttpURLConnectionCodes.OK -> {
                require(response.body.isNotEmpty()) { "CelesTrak 返回空目录" }
                CatalogFetchResult.Updated(
                    payload = response.body.toString(Charsets.UTF_8),
                    etag = response.headers["ETag"].nullIfBlank(),
                    lastModified = response.headers["Last-Modified"].nullIfBlank(),
                )
            }
            else -> throw IllegalStateException("CelesTrak HTTP ${response.statusCode}")
        }
    }

    private fun String?.nullIfBlank(): String? = this?.takeIf(String::isNotBlank)

    private object HttpURLConnectionCodes {
        const val OK = 200
        const val NOT_MODIFIED = 304
    }
}
