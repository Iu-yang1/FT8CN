package com.bg7yoz.ft8cn.satellite

/** 解析标准 3LE 或成对 2LE；损坏记录被逐条隔离，不影响同目录中的有效记录。 */
object TleCatalogParser {
    const val MAXIMUM_CATALOG_BYTES = 8 * 1024 * 1024
    const val MAXIMUM_RECORDS = 20_000

    fun parse(
        payload: String,
        source: String,
        fetchedUtcMillis: Long,
    ): List<TleRecord> {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAXIMUM_CATALOG_BYTES) { "TLE 目录超过 8 MiB" }
        val lines = payload.lineSequence()
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .toList()
        val output = ArrayList<TleRecord>(minOf(lines.size / 2, MAXIMUM_RECORDS))
        var index = 0
        while (index < lines.size && output.size < MAXIMUM_RECORDS) {
            val first = lines[index]
            val isTwoLine = first.startsWith("1 ")
            val nameIndex = if (isTwoLine) -1 else index
            val line1Index = if (isTwoLine) index else index + 1
            val line2Index = line1Index + 1

            if (line2Index >= lines.size ||
                !lines[line1Index].startsWith("1 ") ||
                !lines[line2Index].startsWith("2 ")
            ) {
                index++
                continue
            }

            val name = if (nameIndex < 0) "" else lines[nameIndex].removePrefix("0 ").trim()
            val record = runCatching {
                require(name.length <= 80) { "卫星名称过长" }
                Sgp4OrbitPropagator.parse(
                    name = name,
                    line1 = lines[line1Index],
                    line2 = lines[line2Index],
                    source = source,
                    fetchedUtcMillis = fetchedUtcMillis,
                ).record
            }.getOrNull()
            if (record != null && epochIsPlausible(record.epochUtcMillis, fetchedUtcMillis)) {
                output += record
            }
            index = line2Index + 1
        }
        return output
    }

    private fun epochIsPlausible(epochUtcMillis: Long, fetchedUtcMillis: Long): Boolean {
        if (fetchedUtcMillis < MINIMUM_REALISTIC_UTC_MILLIS) return true
        return epochUtcMillis <= fetchedUtcMillis + MAXIMUM_FUTURE_EPOCH_MILLIS
    }

    private const val MINIMUM_REALISTIC_UTC_MILLIS = 946_684_800_000L // 2000-01-01 UTC
    private const val MAXIMUM_FUTURE_EPOCH_MILLIS = 7L * 24L * 60L * 60L * 1_000L
}
