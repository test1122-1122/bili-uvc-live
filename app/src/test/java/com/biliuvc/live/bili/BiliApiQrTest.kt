package com.biliuvc.live.bili

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 扫码登录：qrcode/generate + qrcode/poll（含 Set-Cookie 聚合与 data.url 补票）。 */
class BiliApiQrTest {

    private lateinit var harness: ApiHarness
    private lateinit var api: BiliApi

    @Before
    fun setUp() {
        harness = ApiHarness()
        api = harness.api()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // ------------------------------------------------------------ generateQr

    @Test
    fun `generateQr parses url and key and sends expected headers`() {
        harness.enqueue(
            """{"code":0,"data":{"url":"https://passport.bilibili.com/h5-app/passport/login/scan?navhide=1&qrcode_key=key123","qrcode_key":"key123"}}"""
        )
        val ticket = api.generateQr()
        assertEquals("key123", ticket.key)
        assertTrue(ticket.url.startsWith("https://passport.bilibili.com/"))
        assertEquals(false, ticket.isEmpty)

        val request = harness.take()
        assertEquals("GET", request.method)
        assertEquals("/x/passport-login/web/qrcode/generate", request.path)
        assertEquals("https://link.bilibili.com", request.getHeader("Origin"))
        assertEquals("https://link.bilibili.com/p/center/index", request.getHeader("Referer"))
        assertEquals("application/json, text/plain, */*", request.getHeader("Accept"))
        assertTrue(request.getHeader("User-Agent")!!.contains("Chrome/129.0.0.0"))
    }

    @Test
    fun `generateQr returns empty ticket on http error without throwing`() {
        harness.enqueue("server error", status = 500)
        val ticket = api.generateQr()
        assertTrue(ticket.isEmpty)
        assertEquals("", ticket.url)
        assertEquals("", ticket.key)
    }

    @Test
    fun `generateQr returns empty ticket on invalid json`() {
        harness.enqueue("<html>blocked</html>")
        assertTrue(api.generateQr().isEmpty)
    }

    @Test
    fun `generateQr returns empty ticket on api error code`() {
        harness.enqueue("""{"code":-412,"message":"请求被拦截"}""")
        assertTrue(api.generateQr().isEmpty)
    }

    @Test
    fun `generateQr returns empty ticket on network failure`() {
        val offline = ApiHarness.offlineApi()
        assertTrue(offline.generateQr().isEmpty)
    }

    // ------------------------------------------------------------ pollQr

    @Test
    fun `pollQr maps 86101 to Pending and passes qrcode_key`() {
        harness.enqueue("""{"code":0,"data":{"code":86101,"message":"未扫码"}}""")
        assertEquals(QrPollResult.Pending, api.pollQr("key/with space"))
        val request = harness.take()
        assertEquals("GET", request.method)
        assertEquals("/x/passport-login/web/qrcode/poll?qrcode_key=key%2Fwith%20space", request.path)
    }

    @Test
    fun `pollQr maps 86090 to Scanned`() {
        harness.enqueue("""{"code":0,"data":{"code":86090,"message":"已扫码"}}""")
        assertEquals(QrPollResult.Scanned, api.pollQr("key"))
    }

    @Test
    fun `pollQr maps 86038 to Expired`() {
        harness.enqueue("""{"code":0,"data":{"code":86038,"message":"二维码已失效"}}""")
        assertEquals(QrPollResult.Expired, api.pollQr("key"))
    }

    @Test
    fun `pollQr aggregates cookies from set-cookie headers on success`() {
        harness.enqueue(
            """{"code":0,"data":{"code":0,"url":""}}""",
            setCookies = listOf(
                "SESSDATA=sess%2Cvalue; Path=/; Domain=.bilibili.com; HttpOnly",
                "bili_jct=tok123; Path=/",
                "DedeUserID=42; Path=/",
                "buvid3=other; Path=/"
            )
        )
        val result = api.pollQr("key") as QrPollResult.Success
        assertEquals("SESSDATA=sess%2Cvalue; bili_jct=tok123; DedeUserID=42", result.cookies)
        assertEquals(42L, result.uid)
        assertEquals(1, harness.server.requestCount)
    }

    @Test
    fun `pollQr falls back to cookies embedded in data url without extra request`() {
        val loginUrl = harness.url("/crossDomain?SESSDATA=sess%2Cvalue&bili_jct=tok123&DedeUserID=42&gourl=x")
        harness.enqueue("""{"code":0,"data":{"code":0,"url":"$loginUrl"}}""")
        val result = api.pollQr("key") as QrPollResult.Success
        assertEquals("SESSDATA=sess%2Cvalue; bili_jct=tok123; DedeUserID=42", result.cookies)
        assertEquals(42L, result.uid)
        assertEquals(1, harness.server.requestCount)
    }

    @Test
    fun `pollQr fetches login ticket and follows trusted redirect`() {
        val loginUrl = harness.url("/ticket")
        harness.enqueue("""{"code":0,"data":{"code":0,"url":"$loginUrl"}}""")
        // 第一跳：302 + 部分 cookie
        harness.enqueueRedirect("/final", setCookies = listOf("bili_jct=tok123; Path=/"))
        // 第二跳：200 + 剩余 cookie
        harness.enqueue(
            """{"code":0}""",
            setCookies = listOf("SESSDATA=sess%2Cvalue; Path=/", "DedeUserID=42; Path=/")
        )

        val result = api.pollQr("key") as QrPollResult.Success
        assertEquals("SESSDATA=sess%2Cvalue; bili_jct=tok123; DedeUserID=42", result.cookies)
        assertEquals(42L, result.uid)

        assertEquals(3, harness.server.requestCount)
        harness.take() // poll
        val ticketRequest = harness.take()
        assertEquals("/ticket", ticketRequest.path)
        assertNull(ticketRequest.getHeader("Cookie")) // 补票请求不能带已有 cookie
        val finalRequest = harness.take()
        assertEquals("/final", finalRequest.path)
    }

    @Test
    fun `pollQr rejects untrusted login redirect url`() {
        harness.enqueue("""{"code":0,"data":{"code":0,"url":"https://evil.example.com/ticket"}}""")
        val result = api.pollQr("key")
        assertTrue(result is QrPollResult.Failure)
        assertTrue((result as QrPollResult.Failure).message.contains("登录跳转地址无效"))
        assertEquals(1, harness.server.requestCount)
    }

    @Test
    fun `pollQr fails when ticket response has no cookies`() {
        val loginUrl = harness.url("/ticket")
        harness.enqueue("""{"code":0,"data":{"code":0,"url":"$loginUrl"}}""")
        harness.enqueue("""{"code":0}""")
        val result = api.pollQr("key")
        assertTrue(result is QrPollResult.Failure)
        assertTrue((result as QrPollResult.Failure).message.contains("未获取到完整登录凭据"))
    }

    @Test
    fun `pollQr fails on api level error code`() {
        harness.enqueue("""{"code":-400,"message":"请求错误"}""")
        val result = api.pollQr("key")
        assertTrue(result is QrPollResult.Failure)
        assertTrue((result as QrPollResult.Failure).message.contains("请求错误"))
    }

    @Test
    fun `pollQr fails when polling code is missing`() {
        harness.enqueue("""{"code":0,"data":{}}""")
        assertTrue(api.pollQr("key") is QrPollResult.Failure)
    }

    @Test
    fun `pollQr fails on unknown data code`() {
        harness.enqueue("""{"code":0,"data":{"code":99999,"message":"未知状态"}}""")
        val result = api.pollQr("key")
        assertTrue(result is QrPollResult.Failure)
        assertTrue((result as QrPollResult.Failure).message.contains("未知状态"))
    }

    @Test
    fun `pollQr fails on network error`() {
        val offline = ApiHarness.offlineApi()
        val result = offline.pollQr("key")
        assertTrue(result is QrPollResult.Failure)
        assertNotEquals("", (result as QrPollResult.Failure).message)
    }

    @Test
    fun `pollQr rejects blank key without network call`() {
        val result = api.pollQr(" ")
        assertTrue(result is QrPollResult.Failure)
        assertEquals(0, harness.server.requestCount)
    }
}
