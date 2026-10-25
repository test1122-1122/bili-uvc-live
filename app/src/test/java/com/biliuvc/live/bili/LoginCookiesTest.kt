package com.biliuvc.live.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginCookiesTest {

    @Test
    fun `extracts cookie value by name`() {
        assertEquals("abc", LoginCookies.extract(" SESSDATA=abc ; bili_jct=def", "SESSDATA"))
        assertEquals("def", LoginCookies.extract("SESSDATA=abc; bili_jct=def", "bili_jct"))
        assertNull(LoginCookies.extract("SESSDATA=abc", "DedeUserID"))
        assertNull(LoginCookies.extract("", "SESSDATA"))
        assertNull(LoginCookies.extract("SESSDATA=; bili_jct=x", "SESSDATA"))
    }

    @Test
    fun `merges only required cookies with later source winning`() {
        val merged = LoginCookies.merge(
            "SESSDATA=old; buvid3=ignored; DedeUserID=111",
            "SESSDATA=new; bili_jct=tok; DedeUserID=222; extra=zzz"
        )
        assertEquals("SESSDATA=new; bili_jct=tok; DedeUserID=222", merged)
    }

    @Test
    fun `aggregates set-cookie headers stripping attributes`() {
        val cookies = LoginCookies.fromSetCookieHeaders(
            listOf(
                "SESSDATA=sess%2Cvalue; Path=/; Domain=.bilibili.com; Secure; HttpOnly",
                "bili_jct=tok123; Path=/",
                "DedeUserID=42; Path=/; Domain=bilibili.com",
                "buvid3=tracking; Path=/",
                "garbage-without-equals",
                "SESSDATA=; Path=/"
            )
        )
        assertEquals("SESSDATA=sess%2Cvalue; bili_jct=tok123; DedeUserID=42", cookies)
    }

    @Test
    fun `later set-cookie overrides earlier one`() {
        val cookies = LoginCookies.fromSetCookieHeaders(
            listOf("SESSDATA=first; Path=/", "SESSDATA=second; Path=/")
        )
        assertEquals("SESSDATA=second", cookies)
    }

    @Test
    fun `session is complete only with all three cookies`() {
        assertTrue(LoginCookies.isComplete(ApiHarness.COOKIES))
        assertFalse(LoginCookies.isComplete("SESSDATA=x; bili_jct=y"))
        assertFalse(LoginCookies.isComplete(""))
        assertFalse(LoginCookies.isComplete(null))
    }

    @Test
    fun `extracts legacy cookies from trusted login url`() {
        val url = "https://passport.biligame.com/crossDomain" +
            "?DedeUserID=42&DedeUserID__ckMd5=abc&Expires=1700000000" +
            "&SESSDATA=sess%2Cvalue&bili_jct=tok123&gourl=https%3A%2F%2Fwww.bilibili.com"
        val cookies = LoginCookies.fromLoginUrl(url, PROD_HOSTS, allowInsecure = false)
        assertEquals("SESSDATA=sess%2Cvalue; bili_jct=tok123; DedeUserID=42", cookies)
    }

    @Test
    fun `rejects login url from untrusted host`() {
        val url = "https://evil.example.com/crossDomain?SESSDATA=x&bili_jct=y&DedeUserID=1"
        assertEquals("", LoginCookies.fromLoginUrl(url, PROD_HOSTS, allowInsecure = false))
    }

    @Test
    fun `trusted login url validation`() {
        assertTrue(LoginCookies.isTrustedLoginUrl("https://passport.bilibili.com/x", PROD_HOSTS, false))
        assertTrue(LoginCookies.isTrustedLoginUrl("https://www.bilibili.com/blackboard/x?y=1", PROD_HOSTS, false))
        assertTrue(LoginCookies.isTrustedLoginUrl("https://bilibili.com:443/x", PROD_HOSTS, false))
        // 非 https
        assertFalse(LoginCookies.isTrustedLoginUrl("http://passport.bilibili.com/x", PROD_HOSTS, false))
        // 非白名单 host
        assertFalse(LoginCookies.isTrustedLoginUrl("https://evil.example.com/x", PROD_HOSTS, false))
        // userinfo 伪装的 host
        assertFalse(LoginCookies.isTrustedLoginUrl("https://evil.example.com@passport.bilibili.com/x", PROD_HOSTS, false))
        // fragment
        assertFalse(LoginCookies.isTrustedLoginUrl("https://passport.bilibili.com/x#frag", PROD_HOSTS, false))
        // 反斜杠
        assertFalse(LoginCookies.isTrustedLoginUrl("https://passport.bilibili.com\\@evil.com/x", PROD_HOSTS, false))
        // 非 443 端口
        assertFalse(LoginCookies.isTrustedLoginUrl("https://passport.bilibili.com:8443/x", PROD_HOSTS, false))
        // 测试放行 http
        assertTrue(LoginCookies.isTrustedLoginUrl("http://127.0.0.1:1234/ticket", setOf("127.0.0.1"), true))
    }

    @Test
    fun `encodes form components like the reference implementation`() {
        assertEquals("pc_link", LoginCookies.encodeComponent("pc_link"))
        assertEquals("a%20b", LoginCookies.encodeComponent("a b"))
        assertEquals("a%26b%3Dc", LoginCookies.encodeComponent("a&b=c"))
        assertEquals("%E6%B5%8B%E8%AF%95", LoginCookies.encodeComponent("测试"))
        assertEquals("%2C%2A", LoginCookies.encodeComponent(",*"))
        assertEquals("A-Z.a_z~09", LoginCookies.encodeComponent("A-Z.a_z~09"))
    }

    @Test
    fun `builds form body in given order`() {
        assertEquals(
            "room_id=1&platform=pc_link&csrf=a%26b",
            LoginCookies.buildFormBody(listOf("room_id" to "1", "platform" to "pc_link", "csrf" to "a&b"))
        )
    }

    private companion object {
        val PROD_HOSTS = BiliEndpoints.DEFAULT_TRUSTED_LOGIN_HOSTS
    }
}
