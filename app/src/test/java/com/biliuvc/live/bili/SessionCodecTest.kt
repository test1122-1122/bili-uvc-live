package com.biliuvc.live.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCodecTest {

    @Test
    fun `round trips session`() {
        val session = BiliSession(
            cookies = "SESSDATA=sess%2Cdata; bili_jct=abcdef123456; DedeUserID=42",
            uid = 42L,
            uname = "测试用户",
            roomId = 987654L
        )
        val decoded = SessionCodec.decode(SessionCodec.encode(session))
        assertEquals(session, decoded)
        assertEquals("abcdef123456", decoded!!.csrf)
        assertEquals(session.cookies, decoded.cookieHeader())
    }

    @Test
    fun `encodes as json object with stable field names`() {
        val session = BiliSession("a=b", 7L, "u", 0L)
        assertEquals(
            """{"cookies":"a=b","uid":7,"uname":"u","roomId":0}""",
            SessionCodec.encode(session)
        )
    }

    @Test
    fun `escapes quotes and newlines in uname`() {
        val session = BiliSession("SESSDATA=x", 1L, "line1\nline2 \"q\" \\", 2L)
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
    }

    @Test
    fun `decode rejects missing or invalid data`() {
        assertNull(SessionCodec.decode(null))
        assertNull(SessionCodec.decode(""))
        assertNull(SessionCodec.decode("   "))
        assertNull(SessionCodec.decode("not json"))
        assertNull(SessionCodec.decode("{}"))
        assertNull(SessionCodec.decode("""{"cookies":"x"}"""))
        assertNull(SessionCodec.decode("""{"cookies":"x","uid":0}"""))
        assertNull(SessionCodec.decode("""{"cookies":"","uid":42}"""))
        assertNull(SessionCodec.decode("""{"cookies":"x","uid":"abc"}"""))
    }

    @Test
    fun `decode tolerates missing optional fields`() {
        val session = SessionCodec.decode("""{"cookies":"SESSDATA=x","uid":42}""")
        assertEquals(BiliSession("SESSDATA=x", 42L, "", 0L), session)
    }
}
