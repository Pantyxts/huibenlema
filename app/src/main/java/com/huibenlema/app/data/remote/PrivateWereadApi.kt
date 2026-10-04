package com.huibenlema.app.data.remote

import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * 微信读书网页版私有 API（扫码登录 Cookie）——定价补全通道。
 * 无官方文档、随时可能变更：仅用于批量获取书籍定价，失败不影响主流程。
 */
interface PrivateWereadApi {

    /** 单书详情（i.weread.qq.com 子域）：含 price/centPrice/originalPrice/payingStatus/bookStatus/isbn 等 */
    @GET("book/info")
    suspend fun bookInfo(
        @Query("bookId") bookId: String,
        @Header("Cookie") cookie: String
    ): JsonObject

    /**
     * 单书详情（网页版同域接口）：与 WebView 登录 Cookie 同域，登录态一定有效。
     * 401 场景下优先使用。
     */
    @GET("https://weread.qq.com/web/book/info")
    suspend fun webBookInfo(
        @Query("bookId") bookId: String,
        @Header("Cookie") cookie: String,
        @Header("Referer") referer: String
    ): JsonObject

    /** 用户信息（登录账号昵称），尽力获取 */
    @GET("user/info")
    suspend fun userInfo(@Header("Cookie") cookie: String): JsonObject

    /** 网页版书架同步（可能一次携带全量书籍价格） */
    @GET("https://weread.qq.com/web/shelf/sync")
    suspend fun webShelfSync(
        @Header("Cookie") cookie: String,
        @Header("Referer") referer: String
    ): JsonObject

    /** 官方 API Key 获取接口（登录后直接返回 wrk- 开头的 Key，文档确认的移动端获取方式） */
    @GET("https://weread.qq.com/api/skills/apikeyGet")
    suspend fun apiKeyGet(@Header("Cookie") cookie: String): okhttp3.ResponseBody
}
