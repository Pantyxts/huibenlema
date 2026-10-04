package com.huibenlema.app.data.remote

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * 微信读书官方 Agent Gateway（见 tools/API-NOTES.md）：
 * 统一入口 POST /api/agent/gateway，body 内 api_name 指定接口，Bearer 鉴权。
 */
interface OfficialGatewayApi {

    @POST("api/agent/gateway")
    suspend fun call(
        @Header("Authorization") authorization: String,
        @Body body: JsonObject
    ): JsonObject
}
