package com.bg7yoz.ft8cn.satellite

import org.json.JSONArray

object SatNogsTransmitterParser {
    const val MAXIMUM_TRANSMITTERS = 2_000

    fun parse(payload: String, expectedCatalogNumber: Int): List<SatelliteTransponder> {
        require(payload.length <= 2 * 1024 * 1024) { "SatNOGS 响应过大" }
        val array = JSONArray(payload)
        require(array.length() <= MAXIMUM_TRANSMITTERS) { "SatNOGS 转发器记录过多" }
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                if (item.optInt("norad_cat_id", -1) != expectedCatalogNumber) continue
                if (!item.optBoolean("alive", true)) continue
                add(
                    SatelliteTransponder(
                        name = item.optString("description", "SatNOGS").take(120),
                        uplinkLowHz = item.optionalLong("uplink_low"),
                        uplinkHighHz = item.optionalLong("uplink_high"),
                        downlinkLowHz = item.optionalLong("downlink_low"),
                        downlinkHighHz = item.optionalLong("downlink_high"),
                        mode = item.optString("mode", "").take(40),
                        inverted = item.optBoolean("invert", false),
                    ),
                )
            }
        }
    }

    private fun org.json.JSONObject.optionalLong(name: String): Long? =
        if (isNull(name) || !has(name)) null else optLong(name).takeIf { it > 0L }
}
