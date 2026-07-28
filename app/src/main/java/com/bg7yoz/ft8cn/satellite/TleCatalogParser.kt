package com.bg7yoz.ft8cn.satellite

/** 解析标准 3LE 或成对 2LE；输入数量和单行长度均有上限。 */
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
        while (index < lines.size) {
            require(output.size < MAXIMUM_RECORDS) { "TLE 目录记录过多" }
            val first = lines[index]
            val name: String
            val line1: String
            if (first.startsWith("1 ")) {
                name = ""
                line1 = first
                index++
            } else {
                require(first.length <= 80) { "卫星名称过长" }
                name = first.removePrefix("0 ").trim()
                require(index + 1 < lines.size) { "TLE line 1 缺失" }
                line1 = lines[index + 1]
                index += 2
            }
            require(index < lines.size) { "TLE line 2 缺失" }
            val line2 = lines[index++]
            output += Sgp4OrbitPropagator.parse(
                name = name,
                line1 = line1,
                line2 = line2,
                source = source,
                fetchedUtcMillis = fetchedUtcMillis,
            ).record
        }
        return output
    }
}
