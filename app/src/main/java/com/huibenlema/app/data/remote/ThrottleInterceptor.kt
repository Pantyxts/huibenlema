package com.huibenlema.app.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 全局请求节流：确保相邻请求间隔 ≥ minIntervalMs，避免触发风控。
 * 阻塞式实现（400ms 量级，OkHttp 线程可接受）。
 */
class ThrottleInterceptor(private val minIntervalMs: Long) : Interceptor {

    @Volatile
    private var lastRequestAt = 0L
    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val wait = lastRequestAt + minIntervalMs - now
            if (wait > 0) Thread.sleep(wait)
            lastRequestAt = System.currentTimeMillis()
        }
        return chain.proceed(chain.request())
    }
}
