package com.huibenlema.app.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份文件新旧版本兼容性测试：
 * - 旧版备份（携带 dailyStats、部分书籍字段缺省）必须可解析；
 * - 新版导出不再携带 dailyStats（每日价值只由同步重建生成），且可再解析。
 */
class BackupFileTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `旧版备份含 dailyStats 与缺省字段可解析`() {
        val old = """
            {
              "exportedAt": 1791219628785,
              "books": [
                {
                  "bookId": "3300043488",
                  "title": "古都",
                  "author": "[日]川端康成",
                  "priceFen": 599,
                  "priceSource": "WEREAD",
                  "progress": 1.0,
                  "removed": true
                }
              ],
              "costs": [],
              "dailyStats": [
                {"date": "2026-10-05", "readSeconds": 35, "valueFen": 52006, "bookCount": 13}
              ]
            }
        """.trimIndent()
        val file = json.decodeFromString(BackupFile.serializer(), old)
        assertEquals(1, file.books.size)
        assertEquals("古都", file.books[0].title)
        // 旧版缺省的字段落到默认值
        assertTrue(file.books[0].onShelf)
        assertFalse(file.books[0].hidden)
        assertFalse(file.books[0].progressManual)
        // dailyStats 仅兼容解析（导入时忽略内容）
        assertEquals(1, file.dailyStats.size)
    }

    @Test
    fun `新导出的备份不包含 dailyStats 且可再解析`() {
        val file = BackupFile(exportedAt = 123L, books = emptyList(), costs = emptyList())
        val encoded = json.encodeToString(BackupFile.serializer(), file)
        assertFalse(encoded.contains("dailyStats"))
        val back = json.decodeFromString(BackupFile.serializer(), encoded)
        assertTrue(back.dailyStats.isEmpty())
    }
}
