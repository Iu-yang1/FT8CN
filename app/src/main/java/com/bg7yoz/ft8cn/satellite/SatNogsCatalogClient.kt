package com.bg7yoz.ft8cn.satellite

import com.bg7yoz.ft8cn.data.local.SatelliteSourceMetadataEntity

class SatNogsCatalogClient(private val transport: SatelliteHttpTransport) {
    suspend fun fetchTransmitters(
        catalogNumber: Int,
        metadata: SatelliteSourceMetadataEntity?,
    ): CatalogFetchResult {
        require(catalogNumber in 1..999_999)
        val headers = buildMap {
            metadata?.etag?.takeIf(String::isNotBlank)?.let { put("If-None-Match", it) }
            metadata?.lastModified?.takeIf(String::isNotBlank)?.let { put("If-Modified-Since", it) }
        }
        val response = transport.get(
            "https://db.satnogs.org/api/transmitters/?satellite__norad_cat_id=$catalogNumber",
            headers,
        )
        return when (response.statusCode) {
            304 -> CatalogFetchResult.NotModified
            200 -> CatalogFetchResult.Updated(
                payload = response.body.toString(Charsets.UTF_8),
                etag = response.headers["ETag"]?.takeIf(String::isNotBlank),
                lastModified = response.headers["Last-Modified"]?.takeIf(String::isNotBlank),
            )
            else -> throw IllegalStateException("SatNOGS HTTP ${response.statusCode}")
        }
    }
}
