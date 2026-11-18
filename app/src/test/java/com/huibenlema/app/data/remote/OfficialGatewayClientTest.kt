package com.huibenlema.app.data.remote

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialGatewayClientTest {

    @Test
    fun `请求携带官方 skill 1_0_6 版本`() = runTest {
        val api = FakeGatewayApi(buildJsonObject { put("errcode", 0) })
        val client = OfficialGatewayClient(api)

        client.shelfSync("wrk-test")

        assertEquals("1.0.6", api.lastBody?.str("skill_version"))
        assertEquals("/shelf/sync", api.lastBody?.str("api_name"))
    }

    @Test
    fun `upgrade_info 不阻塞 errcode 0 的业务回包`() = runTest {
        val response = buildJsonObject {
            put("errcode", 0)
            putJsonObject("upgrade_info") {
                put("message", "skill 已升级")
            }
            put(
                "books",
                JsonArray(listOf(
                    buildJsonObject {
                        put("bookId", "book-1")
                        put("title", "三体")
                    }
                ))
            )
        }
        val client = OfficialGatewayClient(FakeGatewayApi(response))

        val result = client.shelfSync("wrk-test")

        assertTrue(result is GatewayResult.Ok)
        val books = (result as GatewayResult.Ok).data.books
        assertEquals(listOf("book-1"), books.map { it.bookId })
        assertEquals("三体", books.single().title)
    }

    @Test
    fun `upgrade_info 不掩盖网关业务错误`() = runTest {
        val response = buildJsonObject {
            put("errcode", -2012)
            put("errmsg", "参数错误")
            putJsonObject("upgrade_info") {
                put("message", "skill 已升级")
            }
        }
        val client = OfficialGatewayClient(FakeGatewayApi(response))

        val result = client.shelfSync("wrk-test")

        assertTrue(result is GatewayResult.Err)
        assertEquals(-2012, (result as GatewayResult.Err).errcode)
        assertEquals("参数错误", result.message)
    }

    private class FakeGatewayApi(
        private val response: JsonObject
    ) : OfficialGatewayApi {
        var lastBody: JsonObject? = null

        override suspend fun call(
            authorization: String,
            body: JsonObject
        ): JsonObject {
            lastBody = body
            return response
        }
    }
}
