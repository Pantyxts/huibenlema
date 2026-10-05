package com.huibenlema.app.data.remote

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 官方 Agent Gateway 客户端：防御式解析（回包字段经服务端裁剪，逐字段容错）。
 */
@Singleton
class OfficialGatewayClient @Inject constructor(
    private val api: OfficialGatewayApi
) {

    suspend fun shelfSync(key: String): GatewayResult<ShelfSyncDto> {
        return when (val r = call(key, "/shelf/sync")) {
            is GatewayResult.Ok -> GatewayResult.Ok(
                ShelfSyncDto(
                    books = (r.data["books"] as? JsonArray)
                        ?.mapNotNull { it as? JsonObject }
                        ?.mapNotNull { b ->
                            val id = b.str("bookId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            ShelfBookDto(
                                bookId = id,
                                title = b.str("title"),
                                author = b.str("author"),
                                cover = b.str("cover"),
                                category = b.str("category"),
                                readUpdateTime = b.lng("readUpdateTime"),
                                finishReading = b.lng("finishReading") == 1L
                            )
                        } ?: emptyList()
                )
            )
            is GatewayResult.Err -> r
            is GatewayResult.UpgradeRequired -> r
        }
    }

    suspend fun getProgress(key: String, bookId: String): GatewayResult<ProgressDto> {
        return when (val r = call(key, "/book/getprogress", mapOf("bookId" to JsonPrimitive(bookId)))) {
            is GatewayResult.Ok -> {
                val b = r.data["book"] as? JsonObject
                GatewayResult.Ok(
                    ProgressDto(
                        bookId = bookId,
                        progress = b?.int("progress") ?: 0,
                        recordReadingTime = b?.lng("recordReadingTime") ?: 0L,
                        updateTime = b?.lng("updateTime") ?: 0L,
                        finishTime = b?.lng("finishTime") ?: 0L,
                        isStartReading = b?.lng("isStartReading") == 1L
                    )
                )
            }
            is GatewayResult.Err -> r
            is GatewayResult.UpgradeRequired -> r
        }
    }

    /** 官方批量定价：/user/notebooks（只覆盖有笔记的书，其余由私有 API/ISBN/手动补全） */
    suspend fun notebooks(key: String): GatewayResult<List<BookPriceDto>> {
        return when (val r = call(key, "/user/notebooks", mapOf("count" to JsonPrimitive(100)))) {
            is GatewayResult.Ok -> {
                val books = (r.data["books"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                GatewayResult.Ok(books.mapNotNull { nb ->
                    val b = nb["book"] as? JsonObject ?: return@mapNotNull null
                    val id = b.str("bookId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    BookPriceDto(
                        bookId = id,
                        priceFen = parsePriceFen(b),
                        // 任一价格字段存在且 >0 才算"有价"；全 0/缺失 = 系统内无定价数据
                        hasPrice = b.lng("centPrice") > 0L || b.lng("originalPrice") > 0L || b.dbl("price") > 0.0,
                        isFree = b.lng("centPrice") == 0L && b.lng("bookStatus") == 1L
                    )
                })
            }
            is GatewayResult.Err -> r
            is GatewayResult.UpgradeRequired -> r
        }
    }

    /** 阅读统计（monthly）：readTimes 按天分桶 → 每日阅读秒数 */
    suspend fun readMonthly(key: String, baseTime: Long = 0L): GatewayResult<ReadDataDto> {
        return when (val r = call(
            key, "/readdata/detail",
            mapOf("mode" to JsonPrimitive("monthly"), "baseTime" to JsonPrimitive(baseTime))
        )) {
            is GatewayResult.Ok -> {
                val readTimes = r.data["readTimes"] as? JsonObject
                val zone = ZoneId.systemDefault()
                val daily = readTimes?.entries?.mapNotNull { (k, v) ->
                    val ts = k.toLongOrNull() ?: return@mapNotNull null
                    val date = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().toString()
                    date to ((v as? JsonPrimitive)?.longOrNull ?: 0L)
                }?.toMap() ?: emptyMap()
                // readLongest：累计时长榜（含已移出书架的书）
                val longest = (r.data["readLongest"] as? JsonArray)?.mapNotNull { el ->
                    val b = (el as? JsonObject)?.get("book") as? JsonObject ?: return@mapNotNull null
                    val id = b.str("bookId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    LongestBookDto(bookId = id, title = b.str("title"))
                } ?: emptyList()
                GatewayResult.Ok(
                    ReadDataDto(
                        totalReadTime = r.data.lng("totalReadTime"),
                        dailySeconds = daily,
                        registTime = r.data.lng("registTime"),
                        longestBooks = longest
                    )
                )
            }
            is GatewayResult.Err -> r
            is GatewayResult.UpgradeRequired -> r
        }
    }

    private suspend fun call(
        key: String,
        apiName: String,
        params: Map<String, JsonPrimitive> = emptyMap()
    ): GatewayResult<JsonObject> {
        val body = buildJsonObject {
            put("api_name", apiName)
            put("skill_version", SKILL_VERSION)
            params.forEach { (k, v) -> put(k, v) }
        }
        return try {
            val resp = api.call("Bearer $key", body)
            val upgrade = resp["upgrade_info"] as? JsonObject
            if (upgrade != null) {
                return GatewayResult.UpgradeRequired(upgrade.str("message").ifBlank { "微信读书 Skill 版本已升级" })
            }
            val errcode = resp.lng("errcode")
            if (errcode != 0L) {
                GatewayResult.Err(
                    errcode.toInt(),
                    resp.str("errmsg").ifBlank { "网关返回错误码 $errcode" }
                )
            } else {
                GatewayResult.Ok(resp)
            }
        } catch (e: IOException) {
            GatewayResult.Err(-1, "网络错误：${e.message}")
        } catch (e: Exception) {
            GatewayResult.Err(-2, "响应解析失败：${e.message}")
        }
    }

    companion object {
        /** 官方 Skill 包版本（v1.0.4）；请求必带 */
        const val SKILL_VERSION = "1.0.4"

        /**
         * 价格解析：centPrice(分) 优先；originalPrice/price 为元（实测 originalPrice 常为 0）。
         * 取三者最大值，兼顾"原价"语义。
         */
        fun parsePriceFen(book: JsonObject): Long {
            val cent = book.lng("centPrice")
            val originalYuan = book.lng("originalPrice")
            val priceYuan = book.dbl("price")
            return maxOf(cent, originalYuan * 100L, (priceYuan * 100).toLong())
        }
    }
}

// ---- JsonObject 防御式取值 ----

internal fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

internal fun JsonObject.lng(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

internal fun JsonObject.dbl(key: String): Double =
    (this[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0

internal fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: 0

/** centPrice 取价（分）：兼容整数与浮点序列化（1699 / 1699.0），无字段返回 null */
internal fun JsonObject.priceFenOrNull(): Long? {
    val p = this["centPrice"] as? JsonPrimitive ?: return null
    return p.longOrNull ?: p.doubleOrNull?.toLong()
}
