package com.biliuvc.live.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RtmpTargetParserTest {

    @Test
    fun parsesBilibiliAddrAndCode() {
        val target = RtmpTargetParser.fromParts(
            "rtmp://live-push.bilivideo.com/live-bvc/",
            "?streamname=live_123456_7890&key=abcdef123456"
        )
        assertNotNull(target)
        target!!
        assertEquals("rtmp", target.scheme)
        assertEquals("live-push.bilivideo.com", target.host)
        assertEquals(1935, target.port)
        assertEquals("live-bvc", target.app)
        // The leading '?' of the Bilibili code must be preserved.
        assertEquals("?streamname=live_123456_7890&key=abcdef123456", target.streamName)
        assertEquals("rtmp://live-push.bilivideo.com/live-bvc", target.tcUrl)
        assertEquals(
            "rtmp://live-push.bilivideo.com/live-bvc/?streamname=live_123456_7890&key=abcdef123456",
            target.originalUrl
        )
    }

    @Test
    fun parsesBilibiliAddrWithoutTrailingSlash() {
        val target = RtmpTargetParser.fromParts(
            "rtmp://live-push.bilivideo.com/live-bvc",
            "?streamname=abc&key=def"
        )
        assertNotNull(target)
        target!!
        assertEquals("live-bvc", target.app)
        assertEquals("?streamname=abc&key=def", target.streamName)
        assertEquals("rtmp://live-push.bilivideo.com/live-bvc", target.tcUrl)
    }

    @Test
    fun fromPartsReturnsNullForBlankInput() {
        assertNull(RtmpTargetParser.fromParts("", "?streamname=abc"))
        assertNull(RtmpTargetParser.fromParts("rtmp://host/live-bvc/", ""))
        assertNull(RtmpTargetParser.fromParts("", ""))
        assertNull(RtmpTargetParser.fromParts("not-a-url", "?streamname=abc"))
    }

    @Test
    fun parsesCompleteBilibiliUrl() {
        val target = RtmpTargetParser.parse(
            "rtmp://live-push.bilivideo.com/live-bvc/?streamname=live_123&key=secret"
        )
        assertNotNull(target)
        target!!
        assertEquals("live-bvc", target.app)
        assertEquals("?streamname=live_123&key=secret", target.streamName)
        assertEquals("rtmp://live-push.bilivideo.com/live-bvc", target.tcUrl)
    }

    @Test
    fun parsesStandardUrlWithAppAndStream() {
        val target = RtmpTargetParser.parse("rtmp://example.com/live/stream123")
        assertNotNull(target)
        target!!
        assertEquals("example.com", target.host)
        assertEquals(1935, target.port)
        assertEquals("live", target.app)
        assertEquals("stream123", target.streamName)
        assertEquals("rtmp://example.com/live", target.tcUrl)
    }

    @Test
    fun parsesExplicitPortIntoPortAndTcUrl() {
        val target = RtmpTargetParser.parse("rtmp://example.com:1936/live/stream")
        assertNotNull(target)
        target!!
        assertEquals(1936, target.port)
        assertEquals("rtmp://example.com:1936/live", target.tcUrl)
    }

    @Test
    fun keepsQueryInStreamName() {
        val target = RtmpTargetParser.parse("rtmp://example.com/app/stream?token=abc")
        assertNotNull(target)
        assertEquals("stream?token=abc", target!!.streamName)
    }

    @Test
    fun parsesRtmpsDefaultPort() {
        val target = RtmpTargetParser.parse("rtmps://example.com:443/live/stream")
        assertNotNull(target)
        target!!
        assertEquals("rtmps", target.scheme)
        assertEquals(443, target.port)
        // default port omitted from tcUrl
        assertEquals("rtmps://example.com/live", target.tcUrl)
    }

    @Test
    fun returnsNullForInvalidUrls() {
        assertNull(RtmpTargetParser.parse(""))
        assertNull(RtmpTargetParser.parse("   "))
        assertNull(RtmpTargetParser.parse("http://example.com/live/stream"))
        assertNull(RtmpTargetParser.parse("rtmp://example.com/"))
        assertNull(RtmpTargetParser.parse("rtmp://example.com/only-app"))
        assertNull(RtmpTargetParser.parse("rtmp://:1935/app/stream"))
        assertNull(RtmpTargetParser.parse("rtmp://example.com:notaport/app/stream"))
    }
}
