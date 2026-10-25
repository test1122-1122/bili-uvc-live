package com.biliuvc.live.bili

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * 测试脚手架：把 [BiliEndpoints] 的三个基地址全部指向 MockWebServer，
 * 并注入固定时钟（签名中的 ts 可预测）。
 */
internal class ApiHarness {

    val server: MockWebServer = MockWebServer().apply { start() }

    /** 例如 `http://127.0.0.1:12345`（无结尾斜杠）。 */
    val baseUrl: String
        get() = server.url("").toString().trimEnd('/')

    fun url(path: String): String = server.url(path).toString()

    fun api(now: Long = FIXED_NOW): BiliApi = BiliApi(
        endpoints = BiliEndpoints(
            passportBase = baseUrl,
            webBase = baseUrl,
            liveBase = baseUrl,
            trustedLoginHosts = setOf(server.hostName),
            allowInsecureLoginTicket = true
        ),
        clock = { now }
    )

    /** 入队一个 JSON 响应，可附带多条 Set-Cookie。 */
    fun enqueue(body: String, status: Int = 200, setCookies: List<String> = emptyList()) {
        val response = MockResponse().setResponseCode(status).setBody(body)
        for (cookie in setCookies) response.addHeader("Set-Cookie", cookie)
        server.enqueue(response)
    }

    /** 入队一个 302 跳转响应。 */
    fun enqueueRedirect(location: String, setCookies: List<String> = emptyList()) {
        val response = MockResponse().setResponseCode(302).setHeader("Location", location)
        for (cookie in setCookies) response.addHeader("Set-Cookie", cookie)
        server.enqueue(response)
    }

    fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("没有收到请求")

    fun close() {
        runCatching { server.shutdown() }
    }

    companion object {
        const val FIXED_NOW = 1700000000L
        const val UID = 42L
        const val ROOM_ID = 987654L
        const val AREA_ID = 270
        const val CSRF = "abcdef123456"
        const val SESSDATA = "sess%2Cdata"
        const val COOKIES = "SESSDATA=sess%2Cdata; bili_jct=abcdef123456; DedeUserID=42"

        /** 构造一个指向已关闭端口的 [BiliApi]，用于验证网络异常不崩溃。 */
        fun offlineApi(): BiliApi {
            val harness = ApiHarness()
            val api = harness.api()
            harness.close()
            return api
        }
    }
}
