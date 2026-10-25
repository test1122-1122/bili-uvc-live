package com.biliuvc.live.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签名向量：期望值由独立的 Python 实现（排序 + percent-encode + MD5 + appsecret）
 * 预先算出并硬编码，避免“用被测代码算期望值”的自证循环。
 */
class AppSignerTest {

    @Test
    fun `signs version query with fixed timestamp`() {
        val params = linkedMapOf("system_version" to "2", "ts" to "1700000000")
        assertEquals(
            "appkey=aae92bc66f3edfab&system_version=2&ts=1700000000" +
                "&sign=a98e8814240417bed555873f3892c131",
            AppSigner.sign(params)
        )
    }

    @Test
    fun `sorts params and encodes special characters before hashing`() {
        val params = linkedMapOf(
            "title" to "测试 & 直播",
            "room_id" to "12345",
            "platform" to "pc_link",
            "csrf" to "abc123"
        )
        assertEquals(
            "appkey=aae92bc66f3edfab&csrf=abc123&platform=pc_link&room_id=12345" +
                "&title=%E6%B5%8B%E8%AF%95%20%26%20%E7%9B%B4%E6%92%AD" +
                "&sign=047740de352395d18f862b47e1406566",
            AppSigner.sign(params)
        )
    }

    @Test
    fun `signs start live form body`() {
        val params = linkedMapOf(
            "room_id" to "987654",
            "platform" to "pc_link",
            "area_v2" to "270",
            "backup_stream" to "0",
            "csrf_token" to "abcdef123456",
            "csrf" to "abcdef123456",
            "build" to "7850",
            "version" to "7.85.0",
            "ts" to "1700000000"
        )
        assertEquals(
            "appkey=aae92bc66f3edfab&area_v2=270&backup_stream=0&build=7850" +
                "&csrf=abcdef123456&csrf_token=abcdef123456&platform=pc_link" +
                "&room_id=987654&ts=1700000000&version=7.85.0" +
                "&sign=4cb52545d49df1762ae06b90ae3f7620",
            AppSigner.sign(params)
        )
    }

    @Test
    fun `escapes ampersand equals in values`() {
        val body = AppSigner.sign(linkedMapOf("value" to "a&b=c d"))
        assertTrue(body.contains("value=a%26b%3Dc%20d"))
        // &sign= 只出现一次，且为最后一个参数
        assertEquals(1, Regex("&sign=").findAll(body).count())
        assertTrue(body.endsWith(Regex("&sign=[0-9a-f]{32}").find(body)!!.value))
    }

    @Test
    fun `does not mutate caller map`() {
        val params = linkedMapOf("ts" to "1", "system_version" to "2")
        AppSigner.sign(params)
        assertEquals(linkedMapOf("ts" to "1", "system_version" to "2"), params)
        assertFalse(params.containsKey("appkey"))
        assertFalse(params.containsKey("sign"))
    }

    @Test
    fun `empty params still signs appkey only`() {
        assertEquals(
            "appkey=aae92bc66f3edfab&sign=" + md5Hex("appkey=aae92bc66f3edfab" + AppSigner.APP_SECRET),
            AppSigner.sign(linkedMapOf())
        )
    }
}
