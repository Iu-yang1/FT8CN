package com.bg7yoz.ft8cn.satellite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class SatelliteHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

fun interface SatelliteHttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): SatelliteHttpResponse
}

/** 只接受 HTTPS，并限制重定向、超时和响应体，避免目录接口拖垮录音进程。 */
class UrlConnectionSatelliteHttpTransport(
    private val maximumResponseBytes: Int = TleCatalogParser.MAXIMUM_CATALOG_BYTES,
) : SatelliteHttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): SatelliteHttpResponse =
        withContext(Dispatchers.IO) {
            require(url.startsWith("https://")) { "卫星数据只允许 HTTPS" }
            val connection = URL(url).openConnection() as? HttpsURLConnection
                ?: throw IllegalArgumentException("不是 HTTPS 连接")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "text/plain, application/json")
                connection.setRequestProperty("User-Agent", "FT8CN-satellite/1")
                headers.forEach(connection::setRequestProperty)
                val status = connection.responseCode
                val body = if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    ByteArray(0)
                } else {
                    readBounded(
                        if (status in 200..299) connection.inputStream else connection.errorStream,
                        maximumResponseBytes,
                    )
                }
                SatelliteHttpResponse(
                    statusCode = status,
                    headers = mapOf(
                        "ETag" to connection.getHeaderField("ETag").orEmpty(),
                        "Last-Modified" to connection.getHeaderField("Last-Modified").orEmpty(),
                        "Content-Type" to connection.getHeaderField("Content-Type").orEmpty(),
                    ),
                    body = body,
                )
            } finally {
                connection.disconnect()
            }
        }

    private fun readBounded(input: java.io.InputStream?, maximumBytes: Int): ByteArray {
        if (input == null) return ByteArray(0)
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maximumBytes) { "卫星目录响应超过安全上限" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
