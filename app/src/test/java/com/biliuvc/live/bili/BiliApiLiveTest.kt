package com.biliuvc.live.bili

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** startLive / isFaceAuthDone / stopLive / updateRoomTitle。 */
class BiliApiLiveTest {

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

    private val versionBody = """{"code":0,"data":{"build":7850,"curr_version":"7.85.0"}}"""

    private val versionQuery =
        "appkey=aae92bc66f3edfab&system_version=2&ts=1700000000&sign=a98e8814240417bed555873f3892c131"

    private val startBody =
        "appkey=aae92bc66f3edfab&area_v2=270&backup_stream=0&build=7850" +
            "&csrf=abcdef123456&csrf_token=abcdef123456&platform=pc_link" +
            "&room_id=987654&ts=1700000000&version=7.85.0" +
            "&sign=4cb52545d49df1762ae06b90ae3f7620"

    // 参考实现中 update/stop 使用未签名的普通表单（只有 startLive/版本信息走 appkey 签名）
    private val titleBody =
        "room_id=987654&platform=pc_link&title=%E6%96%B0%E6%A0%87%E9%A2%98" +
            "&csrf_token=abcdef123456&csrf=abcdef123456"

    private fun startLive(): StartLiveResult =
        api.startLive(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, ApiHarness.AREA_ID, ApiHarness.UID)

    // ------------------------------------------------------------ startLive

    @Test
    fun `startLive fetches version info then returns rtmp credentials`() {
        harness.enqueue(versionBody)
        harness.enqueue(
            """{"code":0,"data":{"rtmp":{"addr":"rtmp://live-push.bilivideo.com/live-bvc/","code":"?streamname=abc&key=xyz"}}}"""
        )

        val result = startLive()
        assertTrue(result is StartLiveResult.Success)
        result as StartLiveResult.Success
        assertEquals("rtmp://live-push.bilivideo.com/live-bvc/", result.addr)
        assertEquals("?streamname=abc&key=xyz", result.code)

        val versionRequest = harness.take()
        assertEquals("GET", versionRequest.method)
        assertEquals(
            "/xlive/app-blink/v1/liveVersionInfo/getHomePageLiveVersion?$versionQuery",
            versionRequest.path
        )
        assertEquals(ApiHarness.COOKIES, versionRequest.getHeader("Cookie"))

        val startRequest = harness.take()
        assertEquals("POST", startRequest.method)
        assertEquals("/room/v1/Room/startLive", startRequest.path)
        assertTrue(startRequest.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals(ApiHarness.COOKIES, startRequest.getHeader("Cookie"))
        assertEquals(startBody, startRequest.body.readUtf8())
    }

    @Test
    fun `startLive maps 60024 and 60028 to face auth with server qr`() {
        for (code in listOf(60024, 60028)) {
            harness.enqueue(versionBody)
            harness.enqueue("""{"code":$code,"message":"需要人脸认证","data":{"qr":"https://example.com/face?code=$code"}}""")
            val result = startLive()
            assertTrue(result is StartLiveResult.FaceAuthRequired)
            result as StartLiveResult.FaceAuthRequired
            assertEquals(code, result.faceAuthCode)
            assertEquals("https://example.com/face?code=$code", result.qrContent)
        }
    }

    @Test
    fun `startLive maps 60043 to face auth middle page with encoded mid`() {
        harness.enqueue(versionBody)
        harness.enqueue("""{"code":60043,"message":"需要人脸认证","data":{"risk_extra":{}}}""")
        val result = startLive() as StartLiveResult.FaceAuthRequired
        assertEquals(60043, result.faceAuthCode)
        assertEquals(
            "https://www.bilibili.com/blackboard/live/face-auth-middle.html?source_event=400&mid=42",
            result.qrContent
        )
    }

    @Test
    fun `startLive returns failure message for other business codes`() {
        harness.enqueue(versionBody)
        harness.enqueue("""{"code":60030,"message":"该分区需要粉丝勋章","data":{}}""")
        val result = startLive()
        assertTrue(result is StartLiveResult.Failure)
        assertTrue((result as StartLiveResult.Failure).message.contains("该分区需要粉丝勋章"))
    }

    @Test
    fun `startLive fails when rtmp credentials are missing`() {
        harness.enqueue(versionBody)
        harness.enqueue("""{"code":0,"data":{"rtmp":{"addr":"rtmp://x","code":""}}}""")
        val result = startLive()
        assertTrue(result is StartLiveResult.Failure)
        assertTrue((result as StartLiveResult.Failure).message.contains("RTMP"))
    }

    @Test
    fun `startLive aborts when version info fails`() {
        harness.enqueue("""{"code":-412,"message":"请求被拦截"}""")
        val result = startLive()
        assertTrue(result is StartLiveResult.Failure)
        assertTrue((result as StartLiveResult.Failure).message.contains("请求被拦截"))
        assertEquals(1, harness.server.requestCount)
    }

    @Test
    fun `startLive fails when version payload is invalid`() {
        harness.enqueue("""{"code":0,"data":{"build":0,"curr_version":""}}""")
        assertTrue(startLive() is StartLiveResult.Failure)
        harness.enqueue("""{"code":0,"data":{"curr_version":"7.0.0"}}""")
        assertTrue(startLive() is StartLiveResult.Failure)
    }

    @Test
    fun `startLive validates config without network call`() {
        assertTrue(api.startLive("", ApiHarness.CSRF, ApiHarness.ROOM_ID, 270, 42) is StartLiveResult.Failure)
        assertTrue(api.startLive(ApiHarness.COOKIES, "", ApiHarness.ROOM_ID, 270, 42) is StartLiveResult.Failure)
        assertTrue(api.startLive(ApiHarness.COOKIES, ApiHarness.CSRF, 0L, 270, 42) is StartLiveResult.Failure)
        assertEquals(0, harness.server.requestCount)
    }

    @Test
    fun `startLive handles http errors and network failure`() {
        harness.enqueue("boom", status = 500)
        assertTrue(startLive() is StartLiveResult.Failure)
        assertTrue(ApiHarness.offlineApi().startLive(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, 270, 42)
            is StartLiveResult.Failure)
    }

    // ------------------------------------------------------------ isFaceAuthDone

    @Test
    fun `isFaceAuthDone posts form and parses identified flag variants`() {
        harness.enqueue("""{"code":0,"data":{"is_identified":true}}""")
        assertEquals(true, api.isFaceAuthDone(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, 60024))
        val request = harness.take()
        assertEquals("POST", request.method)
        assertEquals("/xlive/app-blink/v1/preLive/IsUserIdentifiedByFaceAuth", request.path)
        assertEquals(ApiHarness.COOKIES, request.getHeader("Cookie"))
        assertEquals(
            "room_id=987654&face_auth_code=60024&csrf_token=abcdef123456&csrf=abcdef123456",
            request.body.readUtf8()
        )

        harness.enqueue("""{"code":0,"data":{"is_identified":1}}""")
        assertEquals(true, api.isFaceAuthDone(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, 60028))
        harness.enqueue("""{"code":0,"data":{"is_identified":"1"}}""")
        assertEquals(true, api.isFaceAuthDone(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, 60043))
        harness.enqueue("""{"code":0,"data":{"is_identified":false}}""")
        assertEquals(false, api.isFaceAuthDone(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, 60024))
    }

    @Test
    fun `isFaceAuthDone returns null on invalid request or response`() {
        assertNull(api.isFaceAuthDone(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, 0))
        assertNull(api.isFaceAuthDone(ApiHarness.COOKIES, ApiHarness.CSRF, 0L, 60024))
        assertNull(api.isFaceAuthDone(ApiHarness.COOKIES, "", ApiHarness.ROOM_ID, 60024))
        assertEquals(0, harness.server.requestCount)

        harness.enqueue("""{"code":-1,"message":"err","data":{}}""")
        assertNull(api.isFaceAuthDone(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, 60024))
        harness.enqueue("""{"code":0,"data":{}}""")
        assertNull(api.isFaceAuthDone(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, 60024))
        harness.enqueue("""{"code":0,"data":{"is_identified":"maybe"}}""")
        assertNull(api.isFaceAuthDone(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, 60024))
        assertNull(ApiHarness.offlineApi().isFaceAuthDone(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, 60024))
    }

    // ------------------------------------------------------------ stopLive / updateRoomTitle

    @Test
    fun `stopLive posts form and returns true on code zero`() {
        harness.enqueue("""{"code":0,"data":{}}""")
        assertTrue(api.stopLive(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID))
        val request = harness.take()
        assertEquals("POST", request.method)
        assertEquals("/room/v1/Room/stopLive", request.path)
        assertEquals(
            "room_id=987654&platform=pc_link&csrf_token=abcdef123456&csrf=abcdef123456",
            request.body.readUtf8()
        )
    }

    @Test
    fun `stopLive returns false on api error network error or invalid config`() {
        harness.enqueue("""{"code":-1,"message":"err"}""")
        assertFalse(api.stopLive(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID))
        assertFalse(api.stopLive(ApiHarness.COOKIES, "", ApiHarness.ROOM_ID))
        assertFalse(api.stopLive(ApiHarness.COOKIES, ApiHarness.CSRF, 0L))
        assertFalse(ApiHarness.offlineApi().stopLive(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID))
    }

    @Test
    fun `updateRoomTitle posts signed title form`() {
        harness.enqueue("""{"code":0}""")
        assertTrue(api.updateRoomTitle(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, "新标题"))
        val request = harness.take()
        assertEquals("/room/v1/Room/update", request.path)
        assertEquals(titleBody, request.body.readUtf8())
    }

    @Test
    fun `updateRoomTitle returns false on errors`() {
        harness.enqueue("""{"code":-1,"message":"err"}""")
        assertFalse(api.updateRoomTitle(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, "t"))
        harness.enqueue("oops", status = 500)
        assertFalse(api.updateRoomTitle(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, "t"))
        assertFalse(api.updateRoomTitle(ApiHarness.COOKIES, "", ApiHarness.ROOM_ID, "t"))
        assertFalse(ApiHarness.offlineApi().updateRoomTitle(ApiHarness.COOKIES, ApiHarness.CSRF, ApiHarness.ROOM_ID, "t"))
    }
}
