package com.bg7yoz.ft8cn.core.radio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeHamlibCatalogInstrumentedTest {
    @Test
    fun packagedHamlibExposesCompleteUniqueModelCatalog() {
        assertTrue("当前 ABI 应包含 native Hamlib", NativeHamlibBridge.nativeAvailable())

        val rows = NativeHamlibBridge.nativeListModels().toList()
        assertTrue("Hamlib 型号目录异常过小: ${rows.size}", rows.size >= 50)

        val ids = rows.map { row ->
            val fields = row.split('\t', limit = 4)
            assertTrue("Hamlib 型号记录字段不完整: $row", fields.size == 4)
            fields.first().toLong()
        }
        assertEquals("Hamlib 型号 ID 不应重复", ids.size, ids.distinct().size)
        assertTrue(rows.any { it.contains("Icom", ignoreCase = true) })
        assertTrue(rows.any { it.contains("Yaesu", ignoreCase = true) })
        assertTrue(rows.any { it.contains("Kenwood", ignoreCase = true) })
    }
}
