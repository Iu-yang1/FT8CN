package com.bg7yoz.ft8cn.data.logbook

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

data class Tq8ValidationResult(
    val qsoFields: List<Map<String, String>>,
    val uncompressedBytes: Int,
)

data class StoredTq8Artifact(
    val file: File,
    val sha256: String,
    val compressedBytes: Long,
    val validation: Tq8ValidationResult,
)

/** 只接受 TQSL 产生的 gzip/ADIF 结构；密码和私钥从不进入 FT8CN。 */
object Tq8StructureValidator {
    const val MAX_COMPRESSED_BYTES = 16L * 1024L * 1024L
    const val MAX_UNCOMPRESSED_BYTES = 64 * 1024 * 1024

    fun validate(file: File): Tq8ValidationResult {
        require(file.isFile && file.length() in 1..MAX_COMPRESSED_BYTES) { "TQ8 文件大小无效" }
        FileInputStream(file).use { return validate(it, file.length()) }
    }

    fun validate(input: InputStream, compressedSizeHint: Long? = null): Tq8ValidationResult {
        if (compressedSizeHint != null) {
            require(compressedSizeHint in 1..MAX_COMPRESSED_BYTES) { "TQ8 文件超过 16 MiB 上限" }
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        GZIPInputStream(input).use { gzip ->
            while (true) {
                val count = gzip.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_UNCOMPRESSED_BYTES) { "TQ8 解压内容超过 64 MiB 上限" }
                output.write(buffer, 0, count)
            }
        }
        val text = output.toString(Charsets.US_ASCII.name())
        val parsed = AdifCodec.parse(text)
        require(text.contains("<TQSL_IDENT:", ignoreCase = true)) { "缺少 TQSL_IDENT" }
        require(parsed.records.any {
            it["REC_TYPE"]?.equals("tCERT", ignoreCase = true) == true && !it["CERTIFICATE"].isNullOrBlank()
        }) { "缺少 TQSL 呼号证书" }
        val contacts = parsed.records.filter { it["REC_TYPE"]?.equals("tCONTACT", ignoreCase = true) == true }
        require(contacts.isNotEmpty()) { "TQ8 不包含已签名 QSO" }
        contacts.forEachIndexed { index, fields ->
            require(!fields["SIGNDATA"].isNullOrBlank()) { "第 ${index + 1} 条 QSO 缺少 SIGNDATA" }
            require(!fields["SIGN_LOTW_1.0"].isNullOrBlank()) { "第 ${index + 1} 条 QSO 缺少数字签名" }
            AdifCodec.fieldsToQso(fields)
        }
        return Tq8ValidationResult(contacts, total)
    }
}

/** 将 SAF 输入复制到 noBackupFilesDir 下，避免 URI 权限失效和云备份泄露。 */
class SignedTq8ArtifactStore(private val rootDirectory: File) {
    fun import(input: InputStream): StoredTq8Artifact {
        require(rootDirectory.mkdirs() || rootDirectory.isDirectory) { "无法创建 LoTW 私有目录" }
        val temporary = File.createTempFile("signed-", ".part", rootDirectory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            DigestInputStream(input, digest).use { source ->
                temporary.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= Tq8StructureValidator.MAX_COMPRESSED_BYTES) { "TQ8 文件超过 16 MiB 上限" }
                        sink.write(buffer, 0, count)
                    }
                }
            }
            require(total > 0) { "TQ8 文件为空" }
            val validation = Tq8StructureValidator.validate(temporary)
            val sha = digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
            val destination = File(rootDirectory, "$sha.tq8")
            if (!destination.exists()) {
                runCatching {
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }.getOrElse {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            require(destination.canonicalFile.parentFile == rootDirectory.canonicalFile) { "非法 TQ8 存储路径" }
            return StoredTq8Artifact(destination, sha, total, validation)
        } finally {
            temporary.delete()
        }
    }
}
