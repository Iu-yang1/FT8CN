package com.bg7yoz.ft8cn.data.logbook

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

data class LotwServerResponse(
    val accepted: Boolean,
    val message: String,
)

object LotwUploadResponseParser {
    private val resultRegex = Regex("<!--\\s*\\.UPL\\.\\s*(accepted|rejected)\\s*-->", RegexOption.IGNORE_CASE)
    private val messageRegex = Regex("<!--\\s*\\.UPLMESSAGE\\.\\s*(.*?)\\s*-->", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun parse(html: String): LotwServerResponse {
        require(html.length <= 1_048_576) { "LoTW 响应超过 1 MiB 上限" }
        val result = resultRegex.find(html)?.groupValues?.get(1)?.lowercase(Locale.US)
            ?: error("LoTW 响应缺少 .UPL. 状态")
        val message = messageRegex.find(html)?.groupValues?.get(1)
            ?.replace(Regex("[\\r\\n\\t ]+"), " ")
            ?.trim()
            ?.take(4_096)
            .orEmpty()
        return LotwServerResponse(result == "accepted", message)
    }
}

interface LotwUploadTransport {
    fun upload(signedTq8: File): LotwServerResponse
}

/** 使用官方 RFC1867 端点；依赖平台 TLS 校验，不关闭证书或主机名验证。 */
class HttpsLotwUploadTransport : LotwUploadTransport {
    override fun upload(signedTq8: File): LotwServerResponse {
        Tq8StructureValidator.validate(signedTq8)
        val boundary = "----FT8CN-${UUID.randomUUID()}"
        val connection = (URL(OFFICIAL_UPLOAD_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            instanceFollowRedirects = false
            setChunkedStreamingMode(16 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "FT8CN LoTW signed-file uploader")
        }
        try {
            connection.outputStream.buffered().use { output ->
                output.write("--$boundary\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.write(
                    "Content-Disposition: form-data; name=\"upfile\"; filename=\"ft8cn.tq8\"\r\n".toByteArray(
                        StandardCharsets.US_ASCII,
                    ),
                )
                output.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                signedTq8.inputStream().buffered().use { it.copyTo(output, 16 * 1024) }
                output.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
            val status = connection.responseCode
            val responseStream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = responseStream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(result.length + count <= 1_048_576) { "LoTW 响应超过 1 MiB 上限" }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }.orEmpty()
            require(status in 200..299) { "LoTW HTTP $status" }
            return LotwUploadResponseParser.parse(body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val OFFICIAL_UPLOAD_URL = "https://lotw.arrl.org/lotw/upload"
    }
}
